// Fake phone: serves the built web client and replays a pre-muxed clip over the same
// WebSocket protocol the app uses. Lets the car-side client be tested without a phone.
//
//   node server.mjs [--port 3333] [--clip ../clips/assets/clips/test-720p30.cmp4]
//                   [--ws-reject 0.5]   reject this fraction of WS handshakes (Tesla flakiness)
//                   [--ws-drop-every 5] close every media/control socket every N seconds (tests reconnect)
//                   [--delay-ms 200]    add latency to every media frame
//                   [--pts-stretch 4]   space frames N× further apart (pts and pacing) while each fragment
//                                       keeps its 33 ms duration: the gappy timeline a static phone screen
//                                       produces, which stalled MSE in the car
//                   [--video-silent]    accept /ws/video and send the init segment, then never a frame
//                   [--video-freeze]    init segment + the cached last keyframe (stamped 50 h into the stream,
//                                       like a phone whose encoder went idle), then never a frame — Model Y
//                                       2026.26 report #7/#8: the car must still show that one frame
//                   [--audio-clip ../clips/assets/clips/test-tone-48k.cmp4]  AAC test tone replayed on /ws/audio
//                   [--audio-silent]    accept /ws/audio and send the init segment, then never a frame: the phone's
//                                       capture died — the video must not care
//                   [--audio-off]       no audio at all (/api/status says so), like audio=none on the phone
//                   [--web ../../app/src/main/assets/web]
//                   [--addresses 192.168.43.1,10.136.114.168]  "phone" addresses reported in /api/status;
//                                       the diag page probes them as the private-IP control group
import { createServer } from 'node:http';
import { readFile, readFileSync, statSync } from 'node:fs';
import { extname, join, normalize, resolve } from 'node:path';
import { dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { WebSocketServer } from 'ws';

const here = dirname(fileURLToPath(import.meta.url));

const args = Object.fromEntries(process.argv.slice(2).reduce((acc, a, i, arr) => {
  if (a.startsWith('--')) acc.push([a.slice(2), arr[i + 1]?.startsWith('--') || arr[i + 1] === undefined ? 'true' : arr[i + 1]]);
  return acc;
}, []));

const PORT = Number(args.port ?? 3333);
const HOST = args.host ?? '0.0.0.0';
const WEB = resolve(here, args.web ?? '../../app/src/main/assets/web');
const CLIP = resolve(here, args.clip ?? '../clips/assets/clips/test-720p30.cmp4');
const WS_REJECT = Number(args['ws-reject'] ?? 0);
const WS_DROP_EVERY = Number(args['ws-drop-every'] ?? 0);
const DELAY_MS = Number(args['delay-ms'] ?? 0);
const VIDEO_SILENT = args['video-silent'] === 'true';
const PTS_STRETCH = Number(args['pts-stretch'] ?? 1);
const VIDEO_FREEZE = args['video-freeze'] === 'true';
const AUDIO_CLIP = resolve(here, args['audio-clip'] ?? '../clips/assets/clips/test-tone-48k.cmp4');
const AUDIO_SILENT = args['audio-silent'] === 'true';
const AUDIO_OFF = args['audio-off'] === 'true';
const FREEZE_PTS_US = 180_214_950_000; // what the car saw: buffered=180214.95-180214.98
const ADDRESSES = (args.addresses ?? 'swlan0=192.168.43.1').split(',').filter(Boolean);

const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.json': 'application/json' };

// ---- clip -------------------------------------------------------------------------------
function loadClip(path) {
  const buf = readFileSync(path);
  const records = [];
  let p = 0;
  while (p < buf.length) {
    const type = buf.readUInt8(p);
    const pts = Number(buf.readBigUInt64BE(p + 1));
    const len = buf.readUInt32BE(p + 9);
    records.push({ type, pts, payload: buf.subarray(p + 13, p + 13 + len) });
    p += 13 + len;
  }
  return records;
}
const clip = loadClip(CLIP);
const init = clip.find((r) => r.type === 0);
const frames = clip.filter((r) => r.type !== 0);
if (PTS_STRETCH !== 1) for (const f of frames) f.pts = Math.round(f.pts * PTS_STRETCH);
const clipDurationUs = frames.length ? frames[frames.length - 1].pts + 33_333 : 0;
console.log(`clip ${CLIP}: ${frames.length} frames, ${(clipDurationUs / 1e6).toFixed(1)} s`);

// Audio: the same record format, replayed on its own loop (its own duration, so the two clips need not match).
const audioClip = !AUDIO_OFF && statSync(AUDIO_CLIP, { throwIfNoEntry: false }) ? loadClip(AUDIO_CLIP) : [];
const audioInit = audioClip.find((r) => r.type === 0);
const audioFrames = audioClip.filter((r) => r.type !== 0);
const AUDIO_FRAME_US = 21_333;
const audioDurationUs = audioFrames.length ? audioFrames[audioFrames.length - 1].pts + AUDIO_FRAME_US : 0;
if (audioFrames.length) console.log(`audio ${AUDIO_CLIP}: ${audioFrames.length} frames, ${(audioDurationUs / 1e6).toFixed(1)} s${AUDIO_SILENT ? ' (silent: init only)' : ''}`);

// The clip's fragments carry their original timestamps in `tfdt`; when looping we re-stamp them
// so the MSE timeline keeps increasing (exactly what the app's ClipSource does).
function packet(type, ptsUs, payload) {
  const out = Buffer.alloc(9 + payload.length);
  out.writeUInt8(type, 0);
  out.writeBigUInt64BE(BigInt(ptsUs), 1);
  payload.copy(out, 9);
  if (type !== 0) {
    const i = out.indexOf('tfdt', 9, 'latin1');
    if (i > 0 && out.readUInt8(i + 4) === 1) out.writeBigUInt64BE(BigInt(ptsUs), i + 8);
  }
  return out;
}

// ---- state exposed to tests --------------------------------------------------------------
const state = { touches: [], keys: [], texts: [], videoClients: 0, audioClients: 0, controlClients: 0, framesSent: 0, audioFramesSent: 0, wsRejected: 0, wsAccepted: 0 };
// Diagnostic reports posted by /diag (same API as the phone's ReportStore, memory only).
const reports = [];

// ---- http -------------------------------------------------------------------------------
const server = createServer((req, res) => {
  const url = new URL(req.url, 'http://x');
  if (url.pathname === '/api/status') {
    res.writeHead(200, { 'content-type': 'application/json' });
    const last = reports[reports.length - 1];
    res.end(JSON.stringify({
      type: 'status', running: true, source: 'fake', width: 1280, height: 720, addresses: ADDRESSES,
      audio: AUDIO_OFF || !audioInit ? { source: 'none' } : AUDIO_SILENT ? { source: 'failed', error: 'fake: audio-silent' } : { source: 'clip', codec: 'mp4a.40.2' },
      reports: reports.length, lastReport: last ? { id: last.id, receivedAt: last.receivedAt, remote: last.remote, summary: last.summary } : null,
      ...state,
    }));
    return;
  }
  if (url.pathname === '/api/report' && req.method === 'POST') {
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => {
      let body;
      try { body = JSON.parse(Buffer.concat(chunks).toString('utf8')); } catch { body = null; }
      res.writeHead(200, { 'content-type': 'application/json' });
      if (!body || typeof body !== 'object' || Array.isArray(body)) { res.end(JSON.stringify({ ok: false, error: 'body is not a JSON object' })); return; }
      const r = { id: reports.length + 1, receivedAt: new Date().toISOString().replace(/\.\d+Z$/, 'Z'), remote: `${req.socket.remoteAddress}:${req.socket.remotePort}`, summary: String(body.summary ?? ''), report: body };
      reports.push(r);
      console.log(`report #${r.id} from ${r.remote}: ${r.summary}`);
      res.end(JSON.stringify({ ok: true, id: r.id, receivedAt: r.receivedAt, stored: reports.length }));
    });
    return;
  }
  if (url.pathname === '/api/app' && req.method === 'POST') {
    state.apps = state.apps ?? [];
    state.apps.push(url.searchParams.get('name'));
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end(JSON.stringify({ ok: true, result: `fake: started ${url.searchParams.get('name')}` }));
    return;
  }
  if (url.pathname === '/api/screen') {
    if (req.method === 'POST') state.screenOn = url.searchParams.get('on') !== '0';
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end(JSON.stringify({ ok: true, screenOn: state.screenOn ?? true }));
    return;
  }
  if (url.pathname === '/api/reports') {
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end(JSON.stringify([...reports].reverse()));
    return;
  }
  if (url.pathname === '/api/reset') {
    state.touches = []; state.keys = []; state.texts = [];
    res.writeHead(200); res.end('ok');
    return;
  }
  let path = url.pathname === '/' ? '/index.html' : url.pathname === '/diag' ? '/diag.html' : url.pathname;
  const file = normalize(join(WEB, path));
  if (!file.startsWith(WEB)) { res.writeHead(400); res.end(); return; }
  readFile(file, (err, data) => {
    if (err) { res.writeHead(404); res.end('404 ' + path); return; }
    res.writeHead(200, { 'content-type': MIME[extname(file)] ?? 'application/octet-stream', 'cache-control': 'no-store' });
    res.end(data);
  });
});

// ---- websocket ---------------------------------------------------------------------------
const wss = new WebSocketServer({ noServer: true });
server.on('upgrade', (req, socket, head) => {
  if (WS_REJECT > 0 && Math.random() < WS_REJECT) {
    state.wsRejected++;
    socket.write('HTTP/1.1 503 Service Unavailable\r\nConnection: close\r\n\r\n');
    socket.destroy();
    return;
  }
  state.wsAccepted++;
  wss.handleUpgrade(req, socket, head, (ws) => wss.emit('connection', ws, req));
});

const videoClients = new Set();
const audioClients = new Set();
wss.on('connection', (ws, req) => {
  const url = new URL(req.url, 'http://x');
  if (url.pathname === '/ws/video') {
    videoClients.add(ws);
    state.videoClients = videoClients.size;
    ws.waitingForKey = true;
    if (init) ws.send(packet(0, 0, init.payload));
    if (VIDEO_FREEZE && frames[0]?.type === 2) ws.send(packet(2, FREEZE_PTS_US, frames[0].payload));
    ws.on('close', () => { videoClients.delete(ws); state.videoClients = videoClients.size; });
  } else if (url.pathname === '/ws/control') {
    state.controlClients++;
    ws.send(JSON.stringify({ type: 'status', width: 1280, height: 720, source: 'fake' }));
    ws.on('message', (data, isBinary) => {
      if (!isBinary) return;
      const b = Buffer.from(data);
      const kind = b.readUInt8(0);
      if (kind === 1) state.touches.push({ action: b.readUInt8(1), id: b.readUInt8(2), x: b.readUInt16BE(3) / 65535, y: b.readUInt16BE(5) / 65535, pressure: b.readUInt16BE(7) / 65535, t: Date.now() });
      else if (kind === 2) state.keys.push({ action: b.readUInt8(1), keycode: b.readUInt16BE(2) });
      else if (kind === 3) state.texts.push(b.subarray(1).toString('utf8'));
    });
    ws.on('close', () => { state.controlClients--; });
  } else if (url.pathname === '/ws/audio') {
    audioClients.add(ws);
    state.audioClients = audioClients.size;
    if (audioInit) ws.send(packet(0, 0, audioInit.payload));
    ws.on('close', () => { audioClients.delete(ws); state.audioClients = audioClients.size; });
  } else {
    ws.close(1008, 'unknown path');
  }
});

// ---- replay loop --------------------------------------------------------------------------
let loop = 0;
let idx = 0;
const t0 = process.hrtime.bigint();
function tick() {
  const f = frames[idx];
  const pts = loop * clipDurationUs + f.pts;
  const dueNs = BigInt(pts) * 1000n;
  const nowNs = process.hrtime.bigint() - t0;
  if (nowNs < dueNs) {
    setTimeout(tick, Number((dueNs - nowNs) / 1_000_000n));
    return;
  }
  const key = f.type === 2;
  {
    const pkt = packet(f.type, pts, f.payload);
    const send = () => {
      for (const ws of videoClients) {
        if (VIDEO_SILENT || VIDEO_FREEZE) continue;
        if (ws.readyState !== ws.OPEN) continue;
        if (ws.waitingForKey && !key) continue;
        if (ws.bufferedAmount > 2_000_000) { ws.waitingForKey = true; continue; }
        ws.waitingForKey = false;
        ws.send(pkt);
        state.framesSent++;
      }
    };
    if (DELAY_MS > 0) setTimeout(send, DELAY_MS); else send();
  }
  idx++;
  if (idx >= frames.length) { idx = 0; loop++; }
  setImmediate(tick);
}

let audioLoop = 0;
let audioIdx = 0;
function tickAudio() {
  const f = audioFrames[audioIdx];
  const pts = audioLoop * audioDurationUs + f.pts;
  const dueNs = BigInt(pts) * 1000n;
  const nowNs = process.hrtime.bigint() - t0;
  if (nowNs < dueNs) {
    setTimeout(tickAudio, Number((dueNs - nowNs) / 1_000_000n));
    return;
  }
  const pkt = packet(2, pts, f.payload);
  const send = () => {
    for (const ws of audioClients) {
      if (AUDIO_SILENT || ws.readyState !== ws.OPEN) continue;
      if (ws.bufferedAmount > 500_000) continue;
      ws.send(pkt);
      state.audioFramesSent++;
    }
  };
  if (DELAY_MS > 0) setTimeout(send, DELAY_MS); else send();
  audioIdx++;
  if (audioIdx >= audioFrames.length) { audioIdx = 0; audioLoop++; }
  setImmediate(tickAudio);
}

if (WS_DROP_EVERY > 0) {
  setInterval(() => {
    for (const ws of wss.clients) ws.terminate();
    state.wsDropped = (state.wsDropped ?? 0) + 1;
  }, WS_DROP_EVERY * 1000);
}

server.listen(PORT, HOST, () => {
  console.log(`fake phone on http://${HOST}:${PORT}/  web=${WEB}  ws-reject=${WS_REJECT} ws-drop-every=${WS_DROP_EVERY}s delay=${DELAY_MS}ms`);
  if (frames.length) tick();
  if (audioFrames.length) tickAudio();
});

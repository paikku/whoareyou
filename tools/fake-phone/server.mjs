// Fake phone: serves the built web client and replays a pre-muxed clip over the same
// WebSocket protocol the app uses. Lets the car-side client be tested without a phone.
//
//   node server.mjs [--port 3333] [--clip ../clips/assets/clips/test-720p30.cmp4]
//                   [--ws-reject 0.5]   reject this fraction of WS handshakes (Tesla flakiness)
//                   [--ws-drop-every 5] close every media/control socket every N seconds (tests reconnect)
//                   [--delay-ms 200]    add latency to every media frame
//                   [--pts-base 131]    start the timeline this many seconds in, like a phone whose server
//                                       has been running a while before the car connects (the live encoder
//                                       stamps absolute time, the clip starts at 0 — see below)
//                   [--pts-stretch 4]   space frames N× further apart (pts and pacing) while each fragment
//                                       keeps its 33 ms duration: the gappy timeline a static phone screen
//                                       produces, which stalled MSE in the car
//                   [--video-silent]    accept /ws/video and send the init segment, then never a frame
//                   [--video-freeze]    init segment + the cached last keyframe (stamped 50 h into the stream,
//                                       like a phone whose encoder went idle), then never a frame — Model Y
//                                       2026.26 report #7/#8: the car must still show that one frame
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
// The phone's shell server stamps frames with the encoder's own clock, so a car that connects an hour after
// the server started sees a timeline that begins an hour in — never 0, which is where the clip starts.
const PTS_BASE_US = Math.round(Number(args['pts-base'] ?? 0) * 1_000_000);
const VIDEO_FREEZE = args['video-freeze'] === 'true';
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
const state = { touches: [], keys: [], texts: [], videoClients: 0, controlClients: 0, framesSent: 0, wsRejected: 0, wsAccepted: 0,
  // 실기기와 같은 진단 필드: 받은 연결 수와 accept 오류. 차에서 "죽었다"고 할 때 폰까지 닿았는지를
  // 가른다(StreamSession.statusJson 과 같은 이름이어야 리포트를 같은 눈으로 읽을 수 있다).
  accepts: 0, acceptErrors: 0, accepting: true, videoDropped: 0, lastAcceptAgoMs: null };
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
    // Same reply shape as the shell server: action says whether a task was started/restarted/moved/brought to front.
    const name = url.searchParams.get('name');
    // 진짜 서버의 기본값과 같다(core StreamSession.DEFAULT_RESTART = never): 옮기거나 앞으로.
    const restart = url.searchParams.get('restart') ?? 'never';
    state.apps = state.apps ?? [];
    state.apps.push(name);
    const from = state.appOnPhone ? 0 : null;
    // 진짜 서버: always 는 어디 있든 강제 종료 후 새로(restarted), auto 는 다른 화면에 있을 때만, never 는 절대.
    const forceStop = restart === 'always' || (restart === 'auto' && from !== null);
    const action = forceStop ? 'restarted' : from === null ? 'started' : 'moved';
    state.appOnPhone = false;
    // 진짜 서버는 띄운 뒤 그 앱의 task 가 어느 화면에 있는지를 상태에 적는다. 여기서 빼먹으면
    // 차는 앱을 띄우고도 "화면에 앱이 없다"고 믿는다 — 가짜 폰이 진짜 폰과 갈리던 자리다.
    state.appDisplay = 7;
    state.app = `${name}/.Main`;
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end(JSON.stringify({ ok: true, result: `fake: ${action} ${name}`, action, package: name, fromDisplay: from, display: 7 }));
    return;
  }
  // 차의 홈과 최근앱이 읽는 두 목록. 진짜 폰에서는 PackageManager 와 `am stack list` 에서 나온다;
  // 여기서는 UI 가 목록을 그리는지, 고른 것이 /api/app 으로 가는지만 보면 되므로 몇 개만 흉내 낸다.
  if (url.pathname === '/api/apps') {
    // 이름만. 아이콘은 /api/icon 이 하나씩 준다 — 목록에 다 싣던 것이 실기기에서 새 연결을 전부
    // 막아 버렸다(실차 리포트 #31~33).
    const all = [
      { package: 'com.google.android.youtube', label: 'YouTube', system: false },
      { package: 'com.android.settings', label: '설정', system: true },
      { package: 'com.spotify.music', label: 'Spotify', system: false },
    ];
    // 진짜 폰처럼 **최근 사용순**으로 준다. 차가 목록을 캐시해 두면 이 순서 변화를 놓친다.
    const used = state.apps ?? [];
    all.sort((a, b) => used.lastIndexOf(b.package) - used.lastIndexOf(a.package));
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end(JSON.stringify(all));
    return;
  }
  if (url.pathname === '/api/icon') {
    // 1x1 투명 PNG. 하나는 일부러 못 그리는 앱으로 둬서 "첫 글자 타일"이 남는지 보게 한다.
    const pkg = url.searchParams.get('pkg') ?? '';
    const dot = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=';
    state.iconRequests = (state.iconRequests ?? 0) + 1;
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end(JSON.stringify({ package: pkg, icon: pkg === 'com.spotify.music' ? null : dot }));
    return;
  }
  if (url.pathname === '/api/tasks') {
    const started = (state.apps ?? []).at(-1) ?? 'com.google.android.youtube';
    // 차 화면(7)에서 도는 것만. 폰으로 끌려갔거나(appOnPhone) 아예 닫혔으면(appDisplay=null) 빈다 —
    // 진짜 서버도 그 화면의 task 만 센다.
    const empty = state.appOnPhone || state.appDisplay === null;
    const tasks = empty
      ? []
      : [{ taskId: 41, name: `${started}/.Main`, package: started, display: 7, label: started, lastUsed: Date.now() }];
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end(JSON.stringify({ display: 7, tasks, elsewhere: state.appOnPhone ? 1 : 0 }));
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
  if (url.pathname === '/api/fake/app-on-phone') {
    // Test hook: pretend the phone's launcher pulled the launched app's task back to display 0
    // (the shell server's watcher sets these from `am stack list`).
    state.appOnPhone = url.searchParams.get('on') !== '0';
    state.appDisplay = state.appOnPhone ? 0 : 7;
    state.app = state.apps?.[state.apps.length - 1] ?? 'com.example.app';
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end(JSON.stringify({ ok: true, appOnPhone: state.appOnPhone }));
    return;
  }
  if (url.pathname === '/api/fake/no-app') {
    // Test hook: the app's task is gone from the virtual display (closed, force-stopped, swiped away).
    // frames keeps its count — the bug this guards was reading that cumulative counter as "nothing yet".
    state.appOnPhone = false;
    // 진짜 폰처럼 보이게 한다: source=display 일 때만 차가 "앱이 없다"를 말한다(클립 재생 중에는 아니다).
    state.source = 'display';
    state.frames = 5000; // 누적 프레임은 이미 쌓여 있다 — 이 값을 "아직 아무것도 없음"으로 읽던 것이 버그였다
    state.appDisplay = url.searchParams.get('on') === '0' ? 7 : null;
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end(JSON.stringify({ ok: true, appDisplay: state.appDisplay }));
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
    // No audio until M6.
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
  // 송출 시각은 클립 시작 기준, 타임라인에 찍는 pts 는 거기에 PTS_BASE_US 를 더한 값 — 둘을 섞으면
  // 시작하자마자 그만큼 기다려 버린다(실수로 한 번 그랬다).
  const elapsedUs = loop * clipDurationUs + f.pts;
  const pts = PTS_BASE_US + elapsedUs;
  const dueNs = BigInt(elapsedUs) * 1000n;
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

if (WS_DROP_EVERY > 0) {
  setInterval(() => {
    for (const ws of wss.clients) ws.terminate();
    state.wsDropped = (state.wsDropped ?? 0) + 1;
  }, WS_DROP_EVERY * 1000);
}

// 실기기와 같게: TCP 연결이 실제로 닿을 때마다 센다.
server.on('connection', () => { state.accepts++; state.lastAcceptAgoMs = 0; });

server.listen(PORT, HOST, () => {
  console.log(`fake phone on http://${HOST}:${PORT}/  web=${WEB}  ws-reject=${WS_REJECT} ws-drop-every=${WS_DROP_EVERY}s delay=${DELAY_MS}ms`);
  if (frames.length) tick();
});

// Diagnostics page: the only observability we have inside the car (no devtools).
import { parseMediaPacket } from './protocol';
import { H264_MIME, AAC_MIME, MseRenderer, mseSupported } from './renderer/mse';
import { wsUrl } from './transport/ws';

const $ = (id: string) => document.getElementById(id)!;
const logEl = $('log');
const log = (s: string) => { logEl.textContent += `${new Date().toISOString().slice(11, 19)} ${s}\n`; };
window.addEventListener('error', (e) => log(`error: ${e.message}`));

function row(table: HTMLElement, k: string, v: string | boolean): void {
  const tr = document.createElement('tr');
  const td1 = document.createElement('td');
  const td2 = document.createElement('td');
  td1.textContent = k;
  if (typeof v === 'boolean') { td2.textContent = v ? 'O' : 'X'; td2.className = v ? 'ok' : 'bad'; }
  else td2.textContent = v;
  tr.append(td1, td2);
  table.append(tr);
}

const env = $('env');
row(env, 'UA', navigator.userAgent);
row(env, 'viewport', `${innerWidth}x${innerHeight} (screen ${screen.width}x${screen.height})`);
row(env, 'devicePixelRatio', String(devicePixelRatio));
row(env, 'origin', location.origin);
row(env, 'secure context', isSecureContext);
row(env, 'language', navigator.language);

const api = $('api');
row(api, 'MediaSource', typeof MediaSource !== 'undefined');
row(api, `MSE ${H264_MIME}`, mseSupported(H264_MIME));
row(api, 'MSE avc1.640028 (High)', mseSupported('video/mp4; codecs="avc1.640028"'));
row(api, 'MSE hvc1 (H.265)', mseSupported('video/mp4; codecs="hvc1.1.6.L93.B0"'));
row(api, `MSE ${AAC_MIME}`, mseSupported(AAC_MIME));
row(api, 'WebCodecs VideoDecoder', typeof (window as any).VideoDecoder !== 'undefined');
row(api, 'AudioContext', typeof (window as any).AudioContext !== 'undefined');
row(api, 'RTCPeerConnection', typeof (window as any).RTCPeerConnection !== 'undefined');
row(api, 'requestVideoFrameCallback', 'requestVideoFrameCallback' in HTMLVideoElement.prototype);
row(api, 'Fullscreen API', typeof document.documentElement.requestFullscreen === 'function');
row(api, 'Pointer Events', typeof (window as any).PointerEvent !== 'undefined');
row(api, 'createImageBitmap', typeof createImageBitmap === 'function');

// WebSocket: 20 sequential handshakes, count successes. Tesla intermittently fails these.
async function wsProbe(): Promise<void> {
  const nEl = $('ws-n');
  const out = $('ws-result');
  let ok = 0;
  const times: number[] = [];
  for (let i = 0; i < 20; i++) {
    nEl.textContent = String(i + 1);
    const t0 = performance.now();
    const success = await new Promise<boolean>((resolve) => {
      let ws: WebSocket;
      try { ws = new WebSocket(wsUrl('/ws/control')); } catch { resolve(false); return; }
      const timer = setTimeout(() => { ws.close(); resolve(false); }, 3000);
      ws.onopen = () => { clearTimeout(timer); ws.close(); resolve(true); };
      ws.onerror = () => { clearTimeout(timer); resolve(false); };
    });
    if (success) { ok++; times.push(performance.now() - t0); }
  }
  const avg = times.length ? Math.round(times.reduce((a, b) => a + b, 0) / times.length) : 0;
  out.textContent = `${ok}/20 성공, 평균 ${avg}ms`;
  out.className = ok >= 18 ? 'ok' : ok > 0 ? '' : 'bad';
  log(`ws probe ${ok}/20 avg ${avg}ms`);
  (window as any).__diag.ws = { ok, avg };
}

// Video: connect once and report decode fps over 5 seconds.
async function videoProbe(): Promise<void> {
  const out = $('video-result');
  const video = document.getElementById('video') as HTMLVideoElement;
  if (!mseSupported()) { out.textContent = 'MSE 미지원'; out.className = 'bad'; return; }
  const r = new MseRenderer(video);
  r.attach(document.body);
  let packets = 0;
  const ws = new WebSocket(wsUrl('/ws/video'));
  ws.binaryType = 'arraybuffer';
  ws.onmessage = (ev) => {
    if (typeof ev.data === 'string') return;
    const p = parseMediaPacket(ev.data);
    if (p) { packets++; r.push(p); }
  };
  ws.onerror = () => log('video ws error');
  await new Promise<void>((resolve) => { ws.onopen = () => resolve(); ws.onclose = () => resolve(); });
  await r.resume();
  await new Promise((res) => setTimeout(res, 5000));
  const s = r.stats();
  ws.close();
  out.textContent = `패킷 ${packets}, 디코드 ${s.framesDecoded}프레임, ${s.fps}fps, lag ${Math.round(s.latencyMs)}ms${s.lastError ? `, err: ${s.lastError}` : ''}`;
  out.className = s.framesDecoded > 0 ? 'ok' : 'bad';
  log(`video probe packets=${packets} frames=${s.framesDecoded} fps=${s.fps}`);
  (window as any).__diag.video = { packets, frames: s.framesDecoded, fps: s.fps, latencyMs: s.latencyMs, error: s.lastError };
  r.destroy();
}

(window as any).__diag = { done: false };
(async () => {
  await wsProbe();
  await videoProbe();
  (window as any).__diag.done = true;
  log('done');
})();

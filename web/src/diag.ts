// Diagnostics page: the only observability we have inside the car (no devtools, no copy/paste).
// Everything measured here is also POSTed back to the phone (/api/report), so the car visit is
// "open the page, wait for 저장됨", and the numbers are read later from a laptop or the app.
import { parseMediaPacket } from './protocol';
import { H264_MIME, AAC_MIME, MseRenderer, mseSupported } from './renderer/mse';
import { wsUrl } from './transport/ws';

const $ = (id: string) => document.getElementById(id)!;
const logEl = $('log');
const log = (s: string) => { logEl.textContent += `${new Date().toISOString().slice(11, 19)} ${s}\n`; };
window.addEventListener('error', (e) => log(`error: ${e.message}`));

interface Report {
  version: number;
  page: string;
  clientTime: string;
  firmware: string;
  env: Record<string, string | boolean | number>;
  api: Record<string, boolean>;
  ws?: { ok: number; avg: number };
  /** `state` is the <video> element at the end of the probe (paused/readyState/currentTime/buffered), for stalls. */
  video?: { packets: number; frames: number; fps: number; latencyMs: number; error: string; state: string };
  /** Control group: the car must fail to reach the phone's real (private) addresses. */
  addresses?: Record<string, 'reachable' | 'blocked' | 'skipped'>;
  summary: string;
  log: string;
}

// Older firmware appended `Tesla/<version>` to the UA; 2026.26 (Model Y) sends a bare
// `Mozilla/5.0 (X11; Linux x86_64) ... Chrome/148.0.0.0 Safari/537.36`, so the token alone cannot
// tell the car from a laptop. Label by what the UA does say and let the reader judge.
function uaLabel(ua: string): string {
  const fw = /Tesla\/(\S+)/.exec(ua)?.[1];
  if (fw) return `Tesla ${fw}`;
  const platform = /\(([^)]*)\)/.exec(ua)?.[1]?.split(';').map((t) => t.trim()).filter(Boolean).slice(0, 2).join(' ') ?? 'unknown';
  const chrome = /Chrome\/(\d+)/.exec(ua)?.[1];
  return `${platform}${chrome ? ` Chrome/${chrome}` : ''} (no Tesla/ token)`;
}

const report: Report = {
  version: 1,
  page: location.href,
  clientTime: new Date().toISOString(),
  firmware: /Tesla\/(\S+)/.exec(navigator.userAgent)?.[1] ?? '',
  env: {},
  api: {},
  summary: '',
  log: '',
};

function row(table: HTMLElement, k: string, v: string | boolean, into?: Record<string, any>): void {
  const tr = document.createElement('tr');
  const td1 = document.createElement('td');
  const td2 = document.createElement('td');
  td1.textContent = k;
  if (typeof v === 'boolean') { td2.textContent = v ? 'O' : 'X'; td2.className = v ? 'ok' : 'bad'; }
  else td2.textContent = v;
  tr.append(td1, td2);
  table.append(tr);
  if (into) into[k] = v;
}

const env = $('env');
row(env, 'UA', navigator.userAgent, report.env);
row(env, 'viewport', `${innerWidth}x${innerHeight} (screen ${screen.width}x${screen.height})`, report.env);
row(env, 'devicePixelRatio', String(devicePixelRatio), report.env);
row(env, 'origin', location.origin, report.env);
row(env, 'secure context', isSecureContext, report.env);
row(env, 'language', navigator.language, report.env);
report.env.innerWidth = innerWidth; report.env.innerHeight = innerHeight;
report.env.screenWidth = screen.width; report.env.screenHeight = screen.height;
report.env.dpr = devicePixelRatio;

const api = $('api');
row(api, 'MediaSource', typeof MediaSource !== 'undefined', report.api);
row(api, `MSE ${H264_MIME}`, mseSupported(H264_MIME), report.api);
row(api, 'MSE avc1.640028 (High)', mseSupported('video/mp4; codecs="avc1.640028"'), report.api);
row(api, 'MSE hvc1 (H.265)', mseSupported('video/mp4; codecs="hvc1.1.6.L93.B0"'), report.api);
row(api, `MSE ${AAC_MIME}`, mseSupported(AAC_MIME), report.api);
row(api, 'WebCodecs VideoDecoder', typeof (window as any).VideoDecoder !== 'undefined', report.api);
row(api, 'AudioContext', typeof (window as any).AudioContext !== 'undefined', report.api);
row(api, 'RTCPeerConnection', typeof (window as any).RTCPeerConnection !== 'undefined', report.api);
row(api, 'requestVideoFrameCallback', 'requestVideoFrameCallback' in HTMLVideoElement.prototype, report.api);
row(api, 'Fullscreen API', typeof document.documentElement.requestFullscreen === 'function', report.api);
row(api, 'Pointer Events', typeof (window as any).PointerEvent !== 'undefined', report.api);
row(api, 'createImageBitmap', typeof createImageBitmap === 'function', report.api);

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
  report.ws = { ok, avg };
  (window as any).__diag.ws = report.ws;
}

// Video: connect once and report decode fps over 5 seconds.
// Nothing here may wait forever: in the car (Model Y 2026.26) `video.play()` never resolved — the
// element's play() promise only settles once the first frame is presented — and the page sat on
// "측정 중…" with nothing saved. Every await is bounded, and whatever we learned goes into the report.
const VIDEO_WS_OPEN_MS = 5000;
const VIDEO_PLAY_MS = 3000;
const VIDEO_MEASURE_MS = 5000;
const sleep = (ms: number) => new Promise<void>((res) => setTimeout(res, ms));
/** Resolves to true when `p` settles first, false when the timeout wins. */
const within = (p: Promise<unknown>, ms: number) => Promise.race([p.then(() => true, () => true), sleep(ms).then(() => false)]);

function videoState(v: HTMLVideoElement): string {
  const b = v.buffered;
  const buf = b.length ? `${b.start(0).toFixed(2)}-${b.end(b.length - 1).toFixed(2)}` : 'none';
  return `paused=${v.paused} ready=${v.readyState} t=${v.currentTime.toFixed(2)} buffered=${buf}${v.error ? ` mediaError=${v.error.code}` : ''}`;
}

async function videoProbe(): Promise<void> {
  const out = $('video-result');
  const video = document.getElementById('video') as HTMLVideoElement;
  const empty = (error: string) => ({ packets: 0, frames: 0, fps: 0, latencyMs: 0, error, state: videoState(video) });
  if (!mseSupported()) {
    out.textContent = 'MSE 미지원'; out.className = 'bad';
    report.video = empty('MSE unsupported');
    (window as any).__diag.video = report.video;
    return;
  }
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
  const opened = await within(new Promise<void>((resolve) => { ws.onopen = () => resolve(); ws.onclose = () => resolve(); }), VIDEO_WS_OPEN_MS);
  let error = '';
  if (!opened) {
    error = `video ws: no open/close in ${VIDEO_WS_OPEN_MS}ms`;
    log(error);
  } else if (ws.readyState !== WebSocket.OPEN) {
    error = 'video ws: closed before open';
    log(error);
  } else {
    // play() resolves only once playback actually starts, so a stream that never decodes would
    // hang here. Give it a moment, then measure regardless — a pending play() is itself the finding.
    const playing = await within(r.resume(), VIDEO_PLAY_MS);
    if (!playing) log(`video play() still pending after ${VIDEO_PLAY_MS}ms, measuring anyway`);
    await sleep(VIDEO_MEASURE_MS);
  }
  const s = r.stats();
  const state = videoState(video);
  ws.close();
  if (!error) error = s.lastError;
  if (!error && s.framesDecoded === 0) error = packets ? 'no frames decoded (play() never started)' : 'no packets received';
  else if (!error && s.fps === 0) error = 'stalled: frames stopped before the end of the probe';
  out.textContent = `패킷 ${packets}, 디코드 ${s.framesDecoded}프레임, ${s.fps}fps, lag ${Math.round(s.latencyMs)}ms${error ? `, err: ${error}` : ''}`;
  out.className = s.framesDecoded > 0 && !error ? 'ok' : 'bad';
  log(`video probe packets=${packets} frames=${s.framesDecoded} fps=${s.fps} ${state}${error ? ` err=${error}` : ''}`);
  report.video = { packets, frames: s.framesDecoded, fps: s.fps, latencyMs: Math.round(s.latencyMs), error, state };
  (window as any).__diag.video = report.video;
  r.destroy();
}

// Control group: the phone's real addresses (hotspot 10.x etc.) must be unreachable from the car,
// otherwise the tun address detour is unnecessary — and if a firmware update opens or closes them
// we want to know. Cross-origin, so use no-cors: an opaque response means "reachable", a network
// error means "blocked" (DNS/route/policy). Same-origin address = the one we came in on, skip it.
async function addressProbe(): Promise<void> {
  const out = $('addr-result');
  let addresses: string[] = [];
  try {
    const st = await (await fetch('/api/status')).json();
    addresses = Array.isArray(st.addresses) ? st.addresses : [];
  } catch (e) { log(`status fetch failed: ${String(e)}`); }
  const result: NonNullable<Report['addresses']> = {};
  if (!addresses.length) { out.textContent = '서버가 주소 목록을 주지 않음'; report.addresses = result; return; }
  const lines: string[] = [];
  for (const entry of addresses) {
    const addr = entry.includes('=') ? entry.slice(entry.indexOf('=') + 1) : entry;
    const url = `http://${addr}:${location.port || 80}/api/status`;
    if (addr === location.hostname) { result[entry] = 'skipped'; lines.push(`${entry}: 현재 주소`); continue; }
    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), 4000);
    try {
      await fetch(url, { mode: 'no-cors', cache: 'no-store', signal: ctrl.signal });
      result[entry] = 'reachable';
      lines.push(`${entry}: 접속됨 (차단 안 됨)`);
    } catch {
      result[entry] = 'blocked';
      lines.push(`${entry}: 차단됨`);
    } finally { clearTimeout(timer); }
  }
  out.textContent = lines.join(' · ');
  log(`address probe ${JSON.stringify(result)}`);
  report.addresses = result;
  (window as any).__diag.addresses = result;
}

function summarize(): string {
  const v = report.video;
  const w = report.ws;
  const blocked = Object.values(report.addresses ?? {}).filter((s) => s === 'blocked').length;
  const reachable = Object.values(report.addresses ?? {}).filter((s) => s === 'reachable').length;
  return [
    uaLabel(navigator.userAgent),
    `${innerWidth}x${innerHeight}@${devicePixelRatio}`,
    `mse=${report.api[`MSE ${H264_MIME}`] ? 'O' : 'X'}`,
    w ? `ws ${w.ok}/20 ${w.avg}ms` : 'ws -',
    v ? `video ${v.frames}f ${v.fps}fps lag ${v.latencyMs}ms${v.error ? ` err=${v.error}` : ''}` : 'video -',
    `private-ip blocked=${blocked} reachable=${reachable}`,
  ].join(', ');
}

let submitted = false;
async function submit(): Promise<void> {
  if (submitted) return;
  submitted = true;
  const out = $('report-result');
  report.summary = summarize();
  report.log = logEl.textContent ?? '';
  $('summary').textContent = report.summary;
  try {
    const res = await fetch('/api/report', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(report) });
    const j = await res.json();
    if (j.ok) {
      out.textContent = `저장됨 #${j.id} (${j.receivedAt}, 총 ${j.stored}건) — 노트북에서 /api/reports 로 확인`;
      out.className = 'ok';
    } else {
      out.textContent = `저장 실패: ${j.error ?? res.status}`; out.className = 'bad';
    }
    (window as any).__diag.report = j;
  } catch (e) {
    out.textContent = `저장 실패: ${String(e)} — 이 화면을 사진으로 남기세요`; out.className = 'bad';
    (window as any).__diag.report = { ok: false, error: String(e) };
  }
}

(window as any).__diag = { done: false };
// Worst case of the probes above: WS 20×3s + video 5+3+5s + addresses 4×4s ≈ 90s. Past that,
// something is wedged; save what we have rather than sit on "측정 중…" forever.
const WATCHDOG_MS = 120_000;
async function step(name: string, fn: () => Promise<void>): Promise<void> {
  try { await fn(); } catch (e) { log(`${name} failed: ${String(e)}`); }
}
(async () => {
  const watchdog = setTimeout(() => {
    log(`watchdog: ${WATCHDOG_MS / 1000}초 안에 안 끝남, 지금까지 결과 저장`);
    submit().then(() => { (window as any).__diag.done = true; });
  }, WATCHDOG_MS);
  await step('ws probe', wsProbe);
  await step('video probe', videoProbe);
  await step('address probe', addressProbe);
  clearTimeout(watchdog);
  await submit();
  (window as any).__diag.done = true;
  log('done');
})();

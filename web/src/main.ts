// Car-side client entry. Thin by design: decode, draw, forward input. All UI logic lives on the phone.
import { KEYCODE, KeyAction, MediaType, encodeKey, encodeText, parseMediaPacket } from './protocol';
import { ReconnectingWs, wsUrl } from './transport/ws';
import { MseRenderer, mseSupported } from './renderer/mse';
import { MjpegRenderer } from './renderer/mjpeg';
import type { Renderer } from './renderer/types';
import { TouchInput } from './input';

const $ = <T extends HTMLElement>(id: string) => document.getElementById(id) as T;
const stage = $('stage');
const video = $<HTMLVideoElement>('video');
const canvas = $<HTMLCanvasElement>('mjpeg');
const overlay = $('overlay');
const overlayMsg = $('overlay-msg');
const statsEl = $('stats');
const kbd = $<HTMLInputElement>('kbd');

const params = new URLSearchParams(location.search);
const forced = params.get('renderer');

function pickRenderer(): Renderer {
  if (forced === 'mjpeg' || (forced !== 'mse' && !mseSupported())) return new MjpegRenderer(canvas);
  return new MseRenderer(video);
}

let renderer = pickRenderer();
renderer.attach(stage);
overlayMsg.textContent = `화면을 터치하면 시작합니다 (${renderer.name})`;

const control = new ReconnectingWs(wsUrl('/ws/control'), {
  onMessage: (data) => {
    if (typeof data !== 'string') return;
    try {
      const msg = JSON.parse(data);
      if (msg.type === 'status' && msg.width && msg.height) touch.setVideoSize(msg.width, msg.height);
    } catch { /* ignore */ }
  },
});
control.start();

const touch = new TouchInput(stage, control);

// Session log: what happened and when, kept for the 💾 button (no devtools in the car).
const t0 = Date.now();
const events: string[] = [];
const note = (s: string) => {
  events.push(`${((Date.now() - t0) / 1000).toFixed(1)}s ${s}`);
  if (events.length > 60) events.shift();
};

let packets = 0;
let lastPacketAt = 0;
let recoveries = 0;
const videoWs = new ReconnectingWs(wsUrl(`/ws/video${renderer.name === 'mjpeg' ? '?codec=mjpeg' : ''}`), {
  onOpen: () => { renderer.reset(); note(`video ws open #${videoWs.stats.connects}`); },
  onClose: () => note('video ws closed'),
  onMessage: (data) => {
    if (typeof data === 'string') return;
    const p = parseMediaPacket(data);
    if (!p) return;
    packets++;
    lastPacketAt = Date.now();
    if (p.type === MediaType.Init) note('init segment (encoder restart?)');
    renderer.push(p);
  },
});
videoWs.start();

// Decode-stall watchdog. Packets keep arriving but nothing gets presented for 2 s: the MSE
// pipeline is wedged (seen on the laptop: frames stop, lag grows). A fresh socket makes the phone
// resend the init segment and a keyframe, which rebuilds the pipeline — the same path as a reconnect.
// No packets at all is not a stall: the phone's encoder goes quiet on a static screen.
let wdPackets = 0;
let wdFrames = 0;
let wdStalledTicks = 0;
setInterval(() => {
  const s = renderer.stats();
  // A hidden page (other window in front, browser minimised, the car showing the camera) presents no frames at
  // all — requestVideoFrameCallback does not run — while packets keep coming. That is not a stall; it looked
  // like one on the laptop ("5 packets, 0 frames, lag 0ms" the moment the window was switched away).
  if (!started || !videoWs.open || document.hidden) { wdStalledTicks = 0; wdPackets = packets; wdFrames = s.framesDecoded; return; }
  const dp = packets - wdPackets;
  const df = s.framesDecoded - wdFrames;
  wdPackets = packets; wdFrames = s.framesDecoded;
  wdStalledTicks = dp >= 5 && df === 0 ? wdStalledTicks + 1 : 0;
  if (wdStalledTicks >= 4) {
    wdStalledTicks = 0;
    recoveries++;
    note(`decode stall (${dp} packets, 0 frames in 2s, lag ${Math.round(s.latencyMs)}ms${s.lastError ? `, ${s.lastError}` : ''}; ${renderer.debug?.() ?? ''}) → video ws 재접속`);
    videoWs.restart();
  }
}, 500);
document.addEventListener('visibilitychange', () => note(document.hidden ? 'page hidden' : 'page visible'));

// The car browser blocks autoplay: the first gesture unlocks video (and later audio).
let started = false;
const start = async () => {
  if (started) return;
  started = true;
  overlay.hidden = true;
  await renderer.resume();
};
overlay.addEventListener('pointerdown', start, { once: true });
stage.addEventListener('pointerdown', start, { once: true });

// Nav bar keys -> Android key events.
for (const btn of document.querySelectorAll<HTMLButtonElement>('#bar button[data-key]')) {
  const code = { back: KEYCODE.BACK, home: KEYCODE.HOME, recents: KEYCODE.APP_SWITCH }[btn.dataset.key!]!;
  btn.addEventListener('pointerdown', () => control.send(encodeKey(KeyAction.Down, code)));
  btn.addEventListener('pointerup', () => control.send(encodeKey(KeyAction.Up, code)));
}

// Keyboard: focus a hidden input so the car keyboard opens; ship committed text to the phone.
$('btn-keyboard').addEventListener('click', () => {
  kbd.value = '';
  kbd.focus();
});
kbd.addEventListener('input', () => {
  if (kbd.value) {
    control.send(encodeText(kbd.value));
    kbd.value = '';
  }
});
kbd.addEventListener('keydown', (e) => {
  if (e.key === 'Enter') {
    control.send(encodeKey(KeyAction.Down, KEYCODE.ENTER));
    control.send(encodeKey(KeyAction.Up, KEYCODE.ENTER));
  } else if (e.key === 'Backspace' && kbd.value === '') {
    control.send(encodeKey(KeyAction.Down, KEYCODE.DEL));
    control.send(encodeKey(KeyAction.Up, KEYCODE.DEL));
  }
});

// A short notice on the stats line (no dialogs in the car), restored after a moment.
let noticeTimer = 0;
const notice = (text: string, ms = 4000) => {
  statsEl.textContent = text;
  window.clearTimeout(noticeTimer);
  noticeTimer = window.setTimeout(() => { if (statsEl.textContent === text) statsEl.textContent = ''; }, ms);
};

// Launch an app on the phone's virtual display (M4). A prompt is enough until the launcher page (M9).
// Android keeps one task per app: if it is open on the phone, a plain start would *move* it to the car and the
// phone's launcher would pull it back — so the phone (restart=auto) force-stops it first and says so.
const ACTION_TEXT: Record<string, string> = {
  started: '앱 실행',
  restarted: '폰에서 쓰던 앱을 종료하고 차 화면에 새로 띄움',
  moved: '폰에서 쓰던 앱을 차 화면으로 옮김',
  front: '이미 차 화면에 있던 앱을 앞으로',
};
$('btn-app').addEventListener('click', async () => {
  const name = window.prompt('실행할 앱 패키지명', localStorage.getItem('carcast.app') || 'com.google.android.youtube');
  if (!name) return;
  try {
    const r = await (await fetch(`/api/app?name=${encodeURIComponent(name.trim())}`, { method: 'POST' })).json();
    if (r.ok) {
      localStorage.setItem('carcast.app', name.trim());
      appOnPhone = false;
      appEpoch++; // a status poll that was already in flight describes the world before this launch
      note(`app ${r.package ?? name.trim()}: ${r.action ?? '?'} (from display ${r.fromDisplay ?? '-'})`);
      notice(ACTION_TEXT[r.action] ?? '앱 실행');
    } else window.alert(`앱 실행 실패: ${r.error}`);
  } catch (e) { window.alert(`앱 실행 요청 실패: ${String(e)}`); }
});

// The other direction cannot be prevented from here: tapping the app's icon on the phone moves its task back to
// the phone's display and the car is left streaming an empty display (black, encoder idle). The phone watches
// for that (/api/status.appOnPhone); poll it so the stats line says "폰이 가져감" instead of looking broken.
let appOnPhone = false;
let phoneAsleep = false;
let appEpoch = 0;
setInterval(async () => {
  if (document.hidden) return;
  try {
    const epoch = appEpoch;
    const st = await (await fetch('/api/status')).json();
    if (epoch !== appEpoch) return;
    const now = st.appOnPhone === true;
    if (now !== appOnPhone) {
      appOnPhone = now;
      note(now ? `phone took ${st.app ?? 'the app'} (display ${st.appDisplay})` : 'app back on the car display');
      if (now) notice('폰이 앱을 가져갔습니다 — ▶로 다시 띄우기', 8000);
    }
    // The phone went to sleep (power button or timeout): the virtual display sleeps with it and the picture
    // freezes. 📵 wakes it and turns only the panel off — the state the driver wanted in the first place.
    const asleep = st.asleep === true;
    if (asleep !== phoneAsleep) {
      phoneAsleep = asleep;
      note(asleep ? 'phone asleep (power button / timeout)' : 'phone awake');
      if (asleep) notice('폰이 잠들어 화면이 멈췄습니다 — 📵를 누르면 폰을 깨우고 화면만 끕니다', 8000);
    }
  } catch { /* the phone is away; the video socket's own reconnect covers it */ }
}, 5000);

// Phone screen off/on (M7): only the phone's own display; the virtual display and audio keep running.
// If the phone is asleep (power button), "off" wakes it first and darkens the panel — never the power button.
$('btn-screen').addEventListener('click', async () => {
  try {
    const cur = await (await fetch('/api/screen')).json();
    const off = cur.asleep ? 0 : cur.screenOn ? 0 : 1;
    const r = await (await fetch(`/api/screen?on=${off}`, { method: 'POST' })).json();
    if (!r.ok) window.alert('폰 화면 전원 변경 실패');
    else { phoneAsleep = false; notice(cur.asleep ? '폰을 깨우고 화면만 껐습니다' : off === 0 ? '폰 화면 끔 (스트림은 계속)' : '폰 화면 켬'); }
  } catch (e) { window.alert(`요청 실패: ${String(e)}`); }
});

$('btn-fullscreen').addEventListener('click', () => {
  if (document.fullscreenElement) document.exitFullscreen().catch(() => {});
  else document.documentElement.requestFullscreen().catch(() => {});
});

// Stats line + a hook for the Playwright tests.
const stats = () => ({
  renderer: renderer.name,
  ...renderer.stats(),
  packets,
  idleMs: lastPacketAt ? Date.now() - lastPacketAt : -1,
  recoveries,
  appOnPhone,
  phoneAsleep,
  videoWs: { ...videoWs.stats, open: videoWs.open },
  controlWs: { ...control.stats, open: control.open },
  started,
});
(window as any).__carcast = { stats, start, events, restartVideo: () => videoWs.restart() };
// fps · lag · socket · then only what is abnormal: reconnects, stall recoveries, dropped frames, idle encoder.
setInterval(() => {
  const s = stats();
  const extra = [
    s.videoWs.connects > 1 ? `↻${s.videoWs.connects - 1}` : '',
    s.recoveries ? `복구${s.recoveries}` : '',
    s.droppedFrames ? `드롭${s.droppedFrames}` : '',
    s.appOnPhone ? '📱폰이 앱을 가져감' : '',
    s.phoneAsleep ? '😴폰 잠듦 — 📵' : '',
    s.idleMs > 1500 ? `폰 무응답 ${Math.round(s.idleMs / 1000)}s` : '',
    s.lastError ? `err ${s.lastError}` : '',
  ].filter(Boolean).join(' ');
  // A notice (▶ result, 💾 saved, 폰이 가져감) owns the line until its own timeout clears or restores it.
  if (statsEl.textContent && !statsEl.textContent.startsWith(s.renderer)) return;
  statsEl.textContent = `${s.renderer} ${s.fps}fps lag ${Math.round(s.latencyMs)}ms ${s.videoWs.open ? '●' : '○'}${extra ? ` ${extra}` : ''}`;
}, 500);

// 💾: push this session's numbers and event log to the phone (/api/reports, like the diag page).
$('btn-save').addEventListener('click', async () => {
  const s = stats();
  const summary = `session ${s.renderer} ${s.fps}fps lag ${Math.round(s.latencyMs)}ms frames ${s.framesDecoded} packets ${s.packets} ws↻${s.videoWs.connects - 1}/${s.videoWs.failures} 복구${s.recoveries} 드롭${s.droppedFrames}${s.lastError ? ` err=${s.lastError}` : ''}`;
  const body = { version: 1, page: location.href, kind: 'session', clientTime: new Date().toISOString(), env: { UA: navigator.userAgent, viewport: `${innerWidth}x${innerHeight}`, dpr: devicePixelRatio }, stats: s, events, summary };
  const prev = statsEl.textContent;
  try {
    const r = await (await fetch('/api/report', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body) })).json();
    statsEl.textContent = r.ok ? `저장됨 #${r.id}` : `저장 실패 ${r.error ?? ''}`;
  } catch (e) { statsEl.textContent = `저장 실패 ${String(e)}`; }
  setTimeout(() => { if (statsEl.textContent?.startsWith('저장')) statsEl.textContent = prev; }, 2500);
});

export type { MediaType };

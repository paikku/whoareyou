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

const videoWs = new ReconnectingWs(wsUrl(`/ws/video${renderer.name === 'mjpeg' ? '?codec=mjpeg' : ''}`), {
  onOpen: () => renderer.reset(),
  onMessage: (data) => {
    if (typeof data === 'string') return;
    const p = parseMediaPacket(data);
    if (p) renderer.push(p);
  },
});
videoWs.start();

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

// Launch an app on the phone's virtual display (M4). A prompt is enough until the launcher page (M9).
$('btn-app').addEventListener('click', async () => {
  const name = window.prompt('실행할 앱 패키지명', localStorage.getItem('carcast.app') || 'com.google.android.youtube');
  if (!name) return;
  try {
    const r = await (await fetch(`/api/app?name=${encodeURIComponent(name.trim())}`, { method: 'POST' })).json();
    if (r.ok) localStorage.setItem('carcast.app', name.trim());
    else window.alert(`앱 실행 실패: ${r.error}`);
  } catch (e) { window.alert(`앱 실행 요청 실패: ${String(e)}`); }
});

$('btn-fullscreen').addEventListener('click', () => {
  if (document.fullscreenElement) document.exitFullscreen().catch(() => {});
  else document.documentElement.requestFullscreen().catch(() => {});
});

// Stats line + a hook for the Playwright tests.
const stats = () => ({
  renderer: renderer.name,
  ...renderer.stats(),
  videoWs: { ...videoWs.stats, open: videoWs.open },
  controlWs: { ...control.stats, open: control.open },
  started,
});
(window as any).__carcast = { stats, start };
setInterval(() => {
  const s = stats();
  statsEl.textContent = `${s.renderer} ${s.fps}fps lag ${Math.round(s.latencyMs)}ms ${s.videoWs.open ? '●' : '○'}`;
}, 500);

export type { MediaType };

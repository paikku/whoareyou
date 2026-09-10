// Car-side client entry. Thin by design: decode, draw, forward input. All UI logic lives on the phone.
import { KEYCODE, KeyAction, MediaType, encodeKey, encodeText, parseMediaPacket } from './protocol';
import { ReconnectingWs, wsUrl } from './transport/ws';
import { MseRenderer, mseSupported } from './renderer/mse';
import { AudioPlayer, audioSupported } from './renderer/audio';
import { MjpegRenderer } from './renderer/mjpeg';
import type { Renderer } from './renderer/types';
import { TouchInput } from './input';

const $ = <T extends HTMLElement>(id: string) => document.getElementById(id) as T;
const stage = $('stage');
const video = $<HTMLVideoElement>('video');
const audioEl = $<HTMLAudioElement>('audio');
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

// Audio (M6): the phone's sound on /ws/audio, played by its own element and kept on the video's
// playhead. Off with ?audio=off, or with the 🔊 button (remembered). Needs the MSE renderer: the
// playhead we sync to is the <video>'s.
const audioWanted = params.get('audio') !== 'off' && renderer.name === 'mse' && audioSupported();
const audio = audioWanted ? new AudioPlayer(audioEl) : null;
audio?.attach();
audio?.setMuted(localStorage.getItem('carcast.audio') === 'off');
// The audio element cannot play closer than ~150 ms to its live edge; hold the video back that much
// so the two can meet (costs about 130 ms of touch latency; ?audio=off restores the 50 ms edge).
if (audio) (renderer as MseRenderer).setTargetLag(0.18);

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

let audioRecoveries = 0;
const audioWs = audio ? new ReconnectingWs(wsUrl('/ws/audio'), {
  onOpen: () => { audio.reset(); note(`audio ws open #${audioWs!.stats.connects}`); },
  onClose: () => note('audio ws closed'),
  onMessage: (data) => {
    if (typeof data === 'string') return;
    const p = parseMediaPacket(data);
    if (!p) return;
    audio.push(p);
  },
}) : null;
audioWs?.start();

// Keep the audio on the video's playhead; if audio has data under the playhead but stops
// advancing for 2 s, rebuild it through a fresh socket (init + frames), like the video watchdog.
setInterval(() => {
  if (!audio || !started || video.paused) return;
  audio.syncTo(video.currentTime);
  if (audio.stats().stalledTicks >= 8) {
    audioRecoveries++;
    note(`audio stall (buffered ${audio.stats().bufferedMs}ms, sync ${audio.stats().syncMs}ms) → audio ws 재접속`);
    audioWs!.restart();
  }
}, 250);

// Decode-stall watchdog. Packets keep arriving but nothing gets presented for 2 s: the MSE
// pipeline is wedged (seen on the laptop: frames stop, lag grows). A fresh socket makes the phone
// resend the init segment and a keyframe, which rebuilds the pipeline — the same path as a reconnect.
// No packets at all is not a stall: the phone's encoder goes quiet on a static screen.
let wdPackets = 0;
let wdFrames = 0;
let wdStalledTicks = 0;
setInterval(() => {
  if (!started || !videoWs.open) { wdStalledTicks = 0; return; }
  const s = renderer.stats();
  const dp = packets - wdPackets;
  const df = s.framesDecoded - wdFrames;
  wdPackets = packets; wdFrames = s.framesDecoded;
  wdStalledTicks = dp >= 5 && df === 0 ? wdStalledTicks + 1 : 0;
  if (wdStalledTicks >= 4) {
    wdStalledTicks = 0;
    recoveries++;
    note(`decode stall (${dp} packets, 0 frames in 2s, lag ${Math.round(s.latencyMs)}ms${s.lastError ? `, ${s.lastError}` : ''}) → video ws 재접속`);
    videoWs.restart();
  }
}, 500);

// The car browser blocks autoplay: the first gesture unlocks video (and later audio).
let started = false;
const start = async () => {
  if (started) return;
  started = true;
  overlay.hidden = true;
  // Both play() calls ride on this gesture; the video's promise only settles on the first frame,
  // so unlock the audio first rather than after that wait.
  const audioStarted = audio?.resume();
  await renderer.resume();
  await audioStarted;
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

// Phone screen off/on (M7): only the phone's own display; the virtual display and audio keep running.
$('btn-screen').addEventListener('click', async () => {
  try {
    const cur = await (await fetch('/api/screen')).json();
    const r = await (await fetch(`/api/screen?on=${cur.screenOn ? 0 : 1}`, { method: 'POST' })).json();
    if (!r.ok) window.alert('폰 화면 전원 변경 실패');
  } catch (e) { window.alert(`요청 실패: ${String(e)}`); }
});

// 🔊/🔇: mute is per car (localStorage), the phone keeps streaming either way.
const audioBtn = $<HTMLButtonElement>('btn-audio');
const showAudioBtn = () => {
  audioBtn.hidden = !audio;
  audioBtn.textContent = audio?.stats().muted ? '🔇' : '🔊';
};
showAudioBtn();
audioBtn.addEventListener('click', () => {
  if (!audio) return;
  const muted = !audio.stats().muted;
  audio.setMuted(muted);
  localStorage.setItem('carcast.audio', muted ? 'off' : 'on');
  showAudioBtn();
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
  videoWs: { ...videoWs.stats, open: videoWs.open },
  controlWs: { ...control.stats, open: control.open },
  audio: audio ? { ...audio.stats(), recoveries: audioRecoveries } : null,
  audioWs: audioWs ? { ...audioWs.stats, open: audioWs.open } : null,
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
    s.idleMs > 1500 ? `폰 무응답 ${Math.round(s.idleMs / 1000)}s` : '',
    s.lastError ? `err ${s.lastError}` : '',
    s.audio?.recoveries ? `♪복구${s.audio.recoveries}` : '',
    s.audio?.lastError ? `♪err ${s.audio.lastError}` : '',
  ].filter(Boolean).join(' ');
  statsEl.textContent = `${s.renderer} ${s.fps}fps lag ${Math.round(s.latencyMs)}ms ${s.videoWs.open ? '●' : '○'}${extra ? ` ${extra}` : ''}`;
}, 500);

// 💾: push this session's numbers and event log to the phone (/api/reports, like the diag page).
$('btn-save').addEventListener('click', async () => {
  const s = stats();
  const a = s.audio;
  const audioSummary = a ? ` audio ${a.frames}f sync ${a.syncMs}ms${a.playing ? '' : ' paused'}${a.muted ? ' muted' : ''}${a.recoveries ? ` 복구${a.recoveries}` : ''}${a.lastError ? ` err=${a.lastError}` : ''}` : ' audio -';
  const summary = `session ${s.renderer} ${s.fps}fps lag ${Math.round(s.latencyMs)}ms frames ${s.framesDecoded} packets ${s.packets} ws↻${s.videoWs.connects - 1}/${s.videoWs.failures} 복구${s.recoveries} 드롭${s.droppedFrames}${s.lastError ? ` err=${s.lastError}` : ''}${audioSummary}`;
  const body = { version: 1, page: location.href, kind: 'session', clientTime: new Date().toISOString(), env: { UA: navigator.userAgent, viewport: `${innerWidth}x${innerHeight}`, dpr: devicePixelRatio }, stats: s, events, summary };
  const prev = statsEl.textContent;
  try {
    const r = await (await fetch('/api/report', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body) })).json();
    statsEl.textContent = r.ok ? `저장됨 #${r.id}` : `저장 실패 ${r.error ?? ''}`;
  } catch (e) { statsEl.textContent = `저장 실패 ${String(e)}`; }
  setTimeout(() => { if (statsEl.textContent?.startsWith('저장')) statsEl.textContent = prev; }, 2500);
});

export type { MediaType };

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

let touchRef: TouchInput | null = null;
const control = new ReconnectingWs(wsUrl('/ws/control'), {
  // A socket that dies mid-gesture leaves a finger down on the phone; the phone cancels its side, we
  // drop ours so the next touch does not reuse a slot the phone has already let go of.
  onClose: () => touchRef?.cancelAll(false),
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
touchRef = touch;

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
/** 폰이 마지막으로 들고 있던 앱(= /api/status.app 의 패키지). ▶ 의 기본값이자 "차로 가져오기"의 대상. */
let lastPackage = '';

async function launch(name: string): Promise<boolean> {
  try {
    const r = await (await fetch(`/api/app?name=${encodeURIComponent(name)}`, { method: 'POST' })).json();
    if (!r.ok) { window.alert(`앱 실행 실패: ${r.error}`); return false; }
    localStorage.setItem('carcast.app', name);
    lastPackage = r.package ?? name;
    appOnPhone = false;
    appEpoch++; // a status poll that was already in flight describes the world before this launch
    note(`app ${r.package ?? name}: ${r.action ?? '?'} (from display ${r.fromDisplay ?? '-'})`);
    notice(ACTION_TEXT[r.action] ?? '앱 실행');
    return true;
  } catch (e) {
    window.alert(`앱 실행 요청 실패: ${String(e)}`);
    return false;
  }
}

$('btn-app').addEventListener('click', async () => {
  const suggested = lastPackage || localStorage.getItem('carcast.app') || 'com.google.android.youtube';
  const name = window.prompt('실행할 앱 패키지명', suggested);
  if (name) await launch(name.trim());
});

// The other direction cannot be prevented from here: tapping the app's icon on the phone moves its task back to
// the phone's display and the car is left streaming an empty display (black, encoder idle). The phone watches
// for that (/api/status.appOnPhone); poll it so the stats line says "폰이 가져감" instead of looking broken.
let appOnPhone = false;
let appEpoch = 0;
let lastStatus: any = null;
let statusFailedAt = 0;
setInterval(async () => {
  if (document.hidden) return;
  try {
    const epoch = appEpoch;
    const st = await (await fetch('/api/status')).json();
    if (epoch !== appEpoch) return;
    lastStatus = st;
    statusFailedAt = 0;
    if (typeof st.app === 'string' && st.app) lastPackage = st.app.split('/')[0]!;
    const now = st.appOnPhone === true;
    if (now !== appOnPhone) {
      appOnPhone = now;
      note(now ? `phone took ${st.app ?? 'the app'} (display ${st.appDisplay})` : 'app back on the car display');
      if (now) notice('폰이 앱을 가져갔습니다 — 화면의 버튼으로 되찾기', 8000);
    }
    // 폰 화면 전원은 차의 📵 로도, 폰의 전원 버튼으로도 바뀐다. 버튼은 언제나 서버가 말하는 쪽을 따른다.
    $('btn-screen').classList.toggle('off', st.screenOn === false);
  } catch {
    // 폰이 잠깐 없는 것: 영상 소켓의 재접속이 알아서 덮는다. 다만 오래가면 상태 패널이 말한다.
    if (!statusFailedAt) statusFailedAt = Date.now();
  }
  updateStatePanel();
}, 2000);

// 그림이 멈췄을 때 얼어붙은 프레임만 남기지 않는다: 왜 멈췄는지와 한 번에 누를 조치를 그 자리에 띄운다.
// 가상 디스플레이는 앱이 하나도 없으면 합성할 내용이 없어 인코더가 한 장도 내지 않는다 — 그래서 "앱이 없다"와
// "폰이 가져갔다"는 둘 다 화면상으로는 똑같이 멈춘 그림으로 보인다. 그 둘을 갈라 주는 것이 이 패널이다.
const statePanel = $('state');
const stateTitle = $('state-title');
const stateMsg = $('state-msg');
const stateAction = $<HTMLButtonElement>('state-action');
let stateAct: (() => void) | null = null;
stateAction.addEventListener('click', () => stateAct?.());

// 정지 화면에서 프레임이 드문 것은 **정상**이다(폰 화면이 안 움직이면 인코더도 쉰다 — sparse.spec).
// 그래서 "프레임이 없다"만으로는 패널을 띄우지 않는다. 띄우는 것은 서버가 확실히 말해 주는 상태뿐이고,
// 나머지는 지금처럼 상태줄에만 적는다. 차에서 멀쩡한 그림을 덮는 것이 제일 나쁘다.
const NO_PHONE_MS = 6000;
// 폰이 잠든 것은 서버가 확실히 말해 주지만, 잠깐 조는 것까지 패널을 띄우면 시끄럽다.
const ASLEEP_MS = 3000;

function showState(title: string, msg: string, action?: { label: string; run: () => void }): void {
  stateTitle.textContent = title;
  stateMsg.textContent = msg;
  if (action) {
    stateAction.textContent = action.label;
    stateAction.hidden = false;
    stateAct = action.run;
  } else {
    stateAction.hidden = true;
    stateAct = null;
  }
  statePanel.hidden = false;
}

/** 지금 패널이 말하고 있는 것 (테스트와 세션 리포트가 읽는다). */
let stateName = '';

function updateStatePanel(): void {
  if (!started) { statePanel.hidden = true; stateName = ''; return; } // 첫 터치 전에는 시작 오버레이가 나와 있다

  if (statusFailedAt && Date.now() - statusFailedAt > NO_PHONE_MS) {
    stateName = 'no-phone';
    showState('폰에 연결되지 않습니다', '폰이 핫스팟을 켜고 있는지, CarCast 서버가 떠 있는지 확인하세요. 연결되면 저절로 돌아옵니다.');
    return;
  }
  if (appOnPhone) {
    stateName = 'app-on-phone';
    const pkg = lastPackage || '그 앱';
    showState('📱 폰에서 그 앱을 쓰는 중', `안드로이드는 앱마다 화면을 하나만 둡니다. ${pkg} 이(가) 폰으로 넘어가 차 화면은 비어 있습니다.`, {
      label: '차로 가져오기',
      run: () => { if (lastPackage) void launch(lastPackage); },
    });
    return;
  }
  // 가상 화면에 앱의 task 가 하나도 없는 상태. 그릴 것이 없으면 인코더도 아무것도 내지 않으므로
  // (2026-09-11 가상 폰 실측) 차에는 마지막 프레임이 얼어붙은 채로 남는다 — 고장처럼 보이지만 고장이 아니다.
  // 처음부터 안 띄운 경우와, 쓰던 앱이 닫힌 경우가 모두 여기다. 무작위 탐색에서 앱이 사라진 뒤 12단계 동안
  // 아무 설명 없이 죽은 화면이 이어졌다(seed 501398062): 그때 이 패널이 떴어야 했다.
  if (lastStatus && lastStatus.source === 'display' && lastStatus.appDisplay === null) {
    stateName = 'no-app';
    showState('차 화면에 띄운 앱이 없습니다', '앱을 고르면 바로 나옵니다. 그릴 것이 없는 동안에는 영상도 멈춰 있습니다.', {
      label: '앱 띄우기',
      run: () => $('btn-app').click(),
    });
    return;
  }
  // 폰이 잠들면 가상 디스플레이까지 합성이 멈춘다 — 앱은 멀쩡한데 그림만 얼어붙는다. 실차 리포트 #26 이
  // 정확히 이 상태였고, 그때 차는 아무 말도 하지 않았다(state=""). 서버가 폰을 다시 재우지 못한 경우
  // (세 번 연속 전원 누르기로 복구를 멈춰 둔 경우 등)에만 여기까지 온다.
  const idle = lastPacketAt ? Date.now() - lastPacketAt : 0;
  if (lastStatus && lastStatus.interactive === false && idle > ASLEEP_MS) {
    stateName = 'phone-asleep';
    showState('📱 폰이 잠들었습니다', '잠든 폰은 차 화면까지 멈춥니다. 폰 화면만 끄고 싶다면 전원 버튼 대신 📵 를 쓰세요.', {
      label: '폰 깨우기',
      run: () => { void fetch('/api/screen?on=1', { method: 'POST' }); },
    });
    return;
  }
  stateName = '';
  statePanel.hidden = true;
}

// Phone screen off/on (M7): only the phone's own display; the virtual display and audio keep running.
$('btn-screen').addEventListener('click', async () => {
  try {
    const cur = await (await fetch('/api/screen')).json();
    const r = await (await fetch(`/api/screen?on=${cur.screenOn ? 0 : 1}`, { method: 'POST' })).json();
    if (!r.ok) window.alert('폰 화면 전원 변경 실패');
  } catch (e) { window.alert(`요청 실패: ${String(e)}`); }
});

$('btn-fullscreen').addEventListener('click', () => {
  if (document.fullscreenElement) document.exitFullscreen().catch(() => {});
  else document.documentElement.requestFullscreen().catch(() => {});
});

// Stats line + a hook for the Playwright tests.
const stats = () => ({
  renderer: renderer.name,
  state: stateName,
  activePointers: touch.activePointers,
  ...renderer.stats(),
  packets,
  idleMs: lastPacketAt ? Date.now() - lastPacketAt : -1,
  recoveries,
  appOnPhone,
  videoWs: { ...videoWs.stats, open: videoWs.open },
  controlWs: { ...control.stats, open: control.open },
  started,
});
(window as any).__carcast = {
  stats, start, events,
  restartVideo: () => videoWs.restart(),
  // 손가락이 눌린 채로 소켓이 끊기는 상황을 테스트에서 만들기 위한 고리 (차에서 쓰는 길은 아니다).
  restartControl: () => control.restart(),
  activePointers: () => touch.activePointers,
};
// fps · lag · socket · then only what is abnormal: reconnects, stall recoveries, dropped frames, idle encoder.
setInterval(() => {
  const s = stats();
  const extra = [
    s.videoWs.connects > 1 ? `↻${s.videoWs.connects - 1}` : '',
    s.recoveries ? `복구${s.recoveries}` : '',
    s.droppedFrames ? `드롭${s.droppedFrames}` : '',
    s.appOnPhone ? '📱폰이 앱을 가져감' : '',
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
  const st = lastStatus;
  const phone = st ? ` | 폰 build=${st.build ?? '?'} ${st.interactive === false ? '잠듦' : '깨어있음'} 화면${st.screenOn === false ? 'OFF' : 'ON'}${st.sleepRecoveries ? ` 되살림${st.sleepRecoveries}` : ''}${st.keptActive ? ` 활성유지${st.keptActive}` : ''} idle${Math.round((s.idleMs ?? 0) / 100) / 10}s` : '';
  const summary = `session ${s.renderer} ${s.fps}fps lag ${Math.round(s.latencyMs)}ms frames ${s.framesDecoded} packets ${s.packets} ws↻${s.videoWs.connects - 1}/${s.videoWs.failures} 복구${s.recoveries} 드롭${s.droppedFrames}${s.lastError ? ` err=${s.lastError}` : ''}${phone}`;
  // 폰 쪽 상태를 같이 싣는다. 실차 리포트 #26·#27 은 차 쪽 수치만 담고 있어서 "전원 버튼을 눌렀을 때
  // 폰이 실제로 잠들었는지, 패널만 꺼졌는지"를 끝내 가릴 수 없었다 — 원인을 가르는 바로 그 정보였다.
  let server: any = null;
  try { server = await (await fetch('/api/status')).json(); } catch { /* 폰이 없으면 그대로 비워 둔다 */ }
  const body = { version: 1, page: location.href, kind: 'session', clientTime: new Date().toISOString(), env: { UA: navigator.userAgent, viewport: `${innerWidth}x${innerHeight}`, dpr: devicePixelRatio }, stats: s, server, events, summary };
  const prev = statsEl.textContent;
  try {
    const r = await (await fetch('/api/report', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body) })).json();
    statsEl.textContent = r.ok ? `저장됨 #${r.id}` : `저장 실패 ${r.error ?? ''}`;
  } catch (e) { statsEl.textContent = `저장 실패 ${String(e)}`; }
  setTimeout(() => { if (statsEl.textContent?.startsWith('저장')) statsEl.textContent = prev; }, 2500);
});

export type { MediaType };

// Car-side client entry. Thin by design: decode, draw, forward input. All UI logic lives on the phone.
import { KEYCODE, KeyAction, MediaType, TouchAction, encodeKey, encodeKeyframeRequest, encodePing, encodeText, encodeTouch, parseMediaPacket, parsePingEcho, touchClock } from './protocol';
import { ReconnectingWs, wsUrl } from './transport/ws';
import { MseRenderer, mseSupported } from './renderer/mse';
import { MjpegRenderer } from './renderer/mjpeg';
import { H264Renderer, h264Supported } from './renderer/h264';
import type { Renderer } from './renderer/types';
import { TouchInput } from './input';

const $ = <T extends HTMLElement>(id: string) => document.getElementById(id) as T;
const stage = $('stage');
const video = $<HTMLVideoElement>('video');
const canvas = $<HTMLCanvasElement>('mjpeg');
const glCanvas = $<HTMLCanvasElement>('gl');
const overlay = $('overlay');
const overlayMsg = $('overlay-msg');
const statsEl = $('stats');
const kbd = $<HTMLInputElement>('kbd');

const params = new URLSearchParams(location.search);
const forced = params.get('renderer');

/**
 * 기본은 h264(캔버스)다.
 *
 * <video> 는 기어가 P 를 벗어나는 순간 프레임 공급이 끊긴다(실측 2026-09-14, docs/drive-check).
 * 차는 대부분의 시간을 D 로 보내므로, 그때만 갈아타는 것은 두 경로를 유지하면서 정작 대부분의
 * 시간에는 쓰지도 않는 쪽을 기본으로 두는 셈이다. 게다가 갈아타려면 MSE 로도 읽히게 Baseline 을
 * 유지해야 해서 화질 이득도 없고, 전환을 감지하는 데 스톨 두 번(4~6 초)이 든다.
 *
 * 그래서 처음부터 D 에서 쓰는 그 경로로 간다. 실측으로 30fps·6ms 이고, MSE(80~140ms)보다 오히려
 * 지연이 낮다 — 버퍼를 쌓지 않기 때문이다. MSE 는 `?renderer=mse` 로 남겨 둔다.
 */
function pickRenderer(): Renderer {
  if (forced === 'mse') return new MseRenderer(video);
  if (forced === 'mjpeg') return new MjpegRenderer(canvas);
  if (forced === 'h264' || h264Supported()) return new H264Renderer(glCanvas);
  // 워커나 WebGL2 가 없는 브라우저: 주차 중에라도 보이도록 <video> 로 물러난다.
  return mseSupported() ? new MseRenderer(video) : new MjpegRenderer(canvas);
}

let renderer = pickRenderer();
renderer.attach(stage);
overlayMsg.textContent = renderer.needsGesture
  ? `화면을 터치하면 시작합니다 (${renderer.name})`
  : `연결하는 중… (${renderer.name})`;

let touchRef: TouchInput | null = null;
/**
 * 컨트롤 소켓 왕복 시간. 2 초마다 ping 을 보내고 폰이 그대로 되돌려 주면 그 안의 시각으로 잰다.
 * "터치가 굼뜨다"가 링크(이 값) 때문인지 폰의 렌더·인코드 때문인지를 리포트만으로 가르는 자리다.
 */
let rttMs = -1;
let pingSeq = 0;
const control = new ReconnectingWs(wsUrl('/ws/control'), {
  // A socket that dies mid-gesture leaves a finger down on the phone; the phone cancels its side, we
  // drop ours so the next touch does not reuse a slot the phone has already let go of.
  onClose: () => touchRef?.cancelAll(false),
  onMessage: (data) => {
    if (typeof data !== 'string') {
      const echo = parsePingEcho(data);
      if (echo) rttMs = (touchClock() - echo.tMs) >>> 0;
      return;
    }
    try {
      const msg = JSON.parse(data);
      if (msg.type === 'status' && msg.width && msg.height) touch.setVideoSize(msg.width, msg.height);
    } catch { /* ignore */ }
  },
});
control.start();
setInterval(() => { if (control.open) control.send(encodePing(++pingSeq)); }, 2000);

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
  // 왜 끊겼는지까지 남긴다. 1006 은 인사도 없이 끊긴 것(링크가 사라짐), 1000/1001 은 폰이
  // 제대로 닫은 것 — 리포트에서 "폰이 멎었나, 선이 끊겼나"를 가르는 데 이 한 글자가 쓰인다.
  onClose: (ev) => note(`video ws closed${'code' in ev ? ` (${ev.code})` : ''}`),
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

/**
 * 폰에 키프레임을 부탁한다(컨트롤 kind 4). 프레임을 버렸을 때(렌더러)와 디코더가 막혔을 때(아래 감시자)
 * 소켓을 다시 여는 것보다 싸고, GOP(10 초)를 기다리는 것보다 빠르다. 폰도 0.5 초에 하나로 줄이지만
 * 여기서도 그만큼 참는다 — 적체 중에는 프레임마다 부탁하게 되기 때문이다.
 */
let keyframeRequests = 0;
let lastKeyframeRequestAt = 0;
function requestKeyframe(why: string): boolean {
  const now = Date.now();
  if (now - lastKeyframeRequestAt < 500 || !control.open) return false;
  lastKeyframeRequestAt = now;
  keyframeRequests++;
  note(`keyframe request (${why})`);
  return control.send(encodeKeyframeRequest());
}
if (renderer instanceof H264Renderer) renderer.onNeedKeyframe = () => { requestKeyframe('dropped frames'); };

// Decode-stall watchdog. Packets keep arriving but nothing gets presented for 2 s: the pipeline is
// wedged. First ask the phone for a keyframe — a decoder that lost its reference recovers on the next
// IDR, and that costs nothing. If two more seconds pass with still nothing, a fresh socket makes the
// phone resend the init segment and a keyframe, which rebuilds the pipeline.
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
  if (wdStalledTicks === 4) {
    note(`decode stall (${dp} packets, 0 frames in 2s, lag ${Math.round(s.latencyMs)}ms${s.lastError ? `, ${s.lastError}` : ''}) → 키프레임 요청`);
    requestKeyframe('decode stall');
  } else if (wdStalledTicks >= 8) {
    wdStalledTicks = 0;
    recoveries++;
    note(`decode stall persists after the keyframe request → video ws 재접속`);
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

// 캔버스 렌더러는 제스처를 기다릴 이유가 없다 — 차에 타면 화면이 이미 나와 있어야 한다.
// (오디오가 붙는 날에는 그때 소리를 위한 제스처를 따로 받는다. M6)
if (!renderer.needsGesture) void start();

// 뒤로가기만 폰으로 보낸다. BACK 은 이벤트가 실린 디스플레이에서 처리되므로 차 화면의 앱에 제대로 간다.
for (const btn of document.querySelectorAll<HTMLButtonElement>('#bar button[data-key]')) {
  const code = { back: KEYCODE.BACK }[btn.dataset.key!]!;
  btn.addEventListener('pointerdown', () => control.send(encodeKey(KeyAction.Down, code)));
  btn.addEventListener('pointerup', () => {
    control.send(encodeKey(KeyAction.Up, code));
    void watchForExit();
  });
}

/**
 * 뒤로가기로 앱을 빠져나온 순간을 **바로** 잡는다.
 *
 * 상태 폴링(2초)만 믿으면 마지막 뒤로가기를 누르고도 몇 초 동안 검은 화면을 보게 된다. 게다가
 * 서버의 appDisplay 는 서버 나름의 감시 주기(최대 5초)로 갱신되므로 합치면 더 길어진다. 그래서
 * 누른 직후에는 `/api/tasks` 를 직접 물어본다 — 그건 그 자리에서 `am stack list` 를 돌려 **지금**
 * 무엇이 도는지를 답한다. 추측이 아니라 사실이고, 비었으면 그 즉시 홈을 띄운다.
 */
let exitWatch = 0;
async function watchForExit() {
  const mine = ++exitWatch;
  for (let i = 0; i < 10; i++) {
    await new Promise((r) => setTimeout(r, 250));
    if (mine !== exitWatch || !sheet.hidden) return;
    try {
      const r = await (await fetch('/api/tasks')).json();
      if (Array.isArray(r?.tasks) && r.tasks.length === 0) {
        hadAppOnCar = false; // 느린 폴링이 뒤늦게 같은 일을 또 하지 않도록
        void openHome();
        return;
      }
    } catch { return; }
  }
}

// ── 차의 홈과 최근앱 ────────────────────────────────────────────────────────────────────────────
//
// HOME 과 APP_SWITCH 는 **보내지 않는다.** 안드로이드는 그 두 키를 이벤트에 실린 디스플레이가 아니라
// **기본 디스플레이(폰)** 의 것으로 처리한다. 그래서 차에서 누르면 폰이 자기 런처로 가면서 차에서 보던
// 앱을 display 0 으로 끌고 간다 — 실차 리포트 #30 에 그 순간이 그대로 찍혀 있다:
// "464.0s phone took com.google.android.youtube (display 0)". 차 화면은 비고, 앱 감시자가 그것을
// 뒤늦게 알아채 다시 띄우는 핑퐁이 난다.
//
// 그래서 차는 자기 홈(설치된 앱 목록)과 자기 최근앱(이 가상 화면 위의 태스크)을 직접 그린다.
const sheet = $('launcher');
const sheetGrid = $('launcher-grid');
const sheetTitle = $('launcher-title');
const sheetEmpty = $('launcher-empty');
const sheetFind = $('launcher-find') as HTMLInputElement;

interface AppRow { package: string; label: string }

/** 보이는 칸에만 아이콘을 요청한다. 스크롤 밖의 수백 개를 미리 끌어오지 않는다. */
const iconWatcher = new IntersectionObserver((entries) => {
  for (const e of entries) {
    if (!e.isIntersecting) continue;
    const el = e.target as HTMLElement;
    iconWatcher.unobserve(el);
    if (el.dataset.pkg) fillIcon(el, el.dataset.pkg);
  }
}, { root: null, rootMargin: '200px' });
interface TaskRow { taskId: number; package: string; label: string; display: number; lastUsed: number }

let apps: AppRow[] = [];
let homeError = '';
let sheetMode: 'home' | 'recents' = 'home';

const closeSheet = () => {
  sheet.hidden = true;
  sheetFind.value = '';
};
$('launcher-close').addEventListener('click', closeSheet);
sheet.addEventListener('click', (e) => { if (e.target === sheet) closeSheet(); });

/**
 * 아이콘은 **화면에 보이는 칸만, 하나씩** 가져온다.
 *
 * 처음에는 목록 한 번에 다 실어 왔는데, 진짜 폰에서는 그것이 수백 개의 앱 리소스를 한 요청 안에서
 * 여는 일이 된다. 폰에서 그 뒤로 **새 연결이 전부 실패했다**(실차 리포트 #31~33) — 차 화면이 검은
 * 채로 재접속만 반복했다. 그래서 목록은 이름만 받고, 아이콘은 보이는 것부터 한 줄씩 채운다.
 */
const iconCache = new Map<string, string | null>();
let iconQueue: Promise<void> = Promise.resolve();

function fillIcon(el: HTMLElement, pkg: string) {
  const cached = iconCache.get(pkg);
  if (cached !== undefined) {
    if (cached) swapIcon(el, cached);
    return;
  }
  // 직렬로 세워 둔다. 동시에 여러 개를 부르면 폰에서 같은 일이 되풀이된다.
  iconQueue = iconQueue.then(async () => {
    if (iconCache.has(pkg) || !el.isConnected) return;
    try {
      const r = await (await fetch(`/api/icon?pkg=${encodeURIComponent(pkg)}`)).json();
      iconCache.set(pkg, r.icon ?? null);
      if (r.icon) swapIcon(el, r.icon);
    } catch { iconCache.set(pkg, null); }
  });
}

function swapIcon(tileEl: HTMLElement, src: string) {
  const fallback = tileEl.querySelector('.fallback');
  if (!fallback) return;
  const img = document.createElement('img');
  img.src = src;
  fallback.replaceWith(img);
}

/** 한 칸. 아이콘은 나중에 채워지고, 그때까지(또는 못 그리면) 이름 첫 글자로 둔다 — 빈 네모보다 낫다. */
function tile(label: string, pkg: string, sub: string | null, onPick: (restart: Restart) => void): HTMLElement {
  const el = document.createElement('button');
  el.className = sub ? 'tile away' : 'tile';
  const art = document.createElement('div');
  art.className = 'fallback';
  art.textContent = (label[0] ?? '?').toUpperCase();
  el.append(art);
  iconWatcher.observe(el);
  el.dataset.pkg = pkg;
  const name = document.createElement('span');
  name.className = 'name';
  name.textContent = label;
  el.append(name);
  if (sub) {
    const where = document.createElement('span');
    where.className = 'where';
    where.textContent = sub;
    el.append(where);
  }
  // 짧게 누르면 가져오기(폰에서 보던 그대로), 길게 누르면 새로 열기. 길게 누르기는 차 브라우저가
  // 컨텍스트 메뉴나 글자 선택으로 가로챌 수 있어 두 이벤트를 막는다 — 그래도 안 되는 차를 위해
  // 상태 패널에는 같은 일을 하는 버튼이 따로 있다.
  el.title = '길게 누르면 새로 열기';
  onPress(el, () => onPick('never'), () => onPick('always'));
  return el;
}

/** 길게 누른 것으로 치는 시간. 폰의 길게 누르기(400~500ms)와 비슷하게, 그러나 운전 중 떨림에 오인되지 않게. */
const LONG_PRESS_MS = 600;
/**
 * 짧게/길게 누르기를 가른다. 손가락이 눌린 채로 시간이 차면 칸이 `held` 로 바뀌어 "이제 떼면 새로 열기"를
 * 보여 주고, **떼는 순간** `long` 을 부른다(시간이 찼다고 바로 부르면 시트가 손가락 밑에서 사라지고, 떼는
 * 손짓이 그 아래 영상에 터치로 들어간다). 그 뒤의 click 은 버린다. 시간 전에 떼면 click 이 그대로 `short`
 * 가 된다. 눌린 채 움직여 나가면(스크롤) 둘 다 아니다.
 */
function onPress(el: HTMLElement, short: () => void, long: () => void): void {
  let timer = 0;
  let armed = false;
  let firedLong = false;
  const disarm = () => { window.clearTimeout(timer); timer = 0; armed = false; el.classList.remove('held'); };
  el.addEventListener('pointerdown', (e) => {
    if (e.button !== 0) return;
    firedLong = false;
    disarm();
    timer = window.setTimeout(() => { timer = 0; armed = true; el.classList.add('held'); }, LONG_PRESS_MS);
  });
  el.addEventListener('pointerup', () => {
    const fire = armed;
    disarm();
    if (fire) { firedLong = true; long(); }
  });
  el.addEventListener('pointercancel', disarm);
  el.addEventListener('pointerleave', disarm);
  el.addEventListener('click', (e) => {
    if (firedLong) { firedLong = false; e.preventDefault(); return; }
    short();
  });
  el.addEventListener('contextmenu', (e) => e.preventDefault());
}

/**
 * 시트에서 앱 하나를 고른다. 띄우는 일은 ▶ 와 같은 길(`launch`)로 보낸다 — 실패했을 때 알리고,
 * 마지막에 고른 앱을 기억하고, "폰이 가져갔다" 상태를 푸는 것까지 거기 다 들어 있다.
 */
function pick(pkg: string, restart: Restart = 'never') {
  closeSheet();
  void launch(pkg, restart);
}

function renderHome(filter: string) {
  const q = filter.trim().toLowerCase();
  const rows = q ? apps.filter((a) => a.label.toLowerCase().includes(q) || a.package.includes(q)) : apps;
  sheetGrid.replaceChildren(...rows.map((a) => tile(a.label, a.package, null, (r) => pick(a.package, r))));
  sheetEmpty.hidden = rows.length > 0;
  sheetEmpty.textContent = q ? `"${filter}" 에 맞는 앱이 없습니다` : (homeError || '앱 목록을 읽지 못했습니다');
}

async function openHome() {
  sheetMode = 'home';
  sheetTitle.textContent = '홈';
  sheetFind.hidden = false;
  sheet.hidden = false;
  sheetEmpty.hidden = true;
  // 가지고 있는 것부터 곧바로 그린다 — 기다리는 빈 화면을 보여 주지 않는다.
  if (apps.length) renderHome(sheetFind.value);
  else {
    sheetGrid.replaceChildren();
    sheetEmpty.hidden = false;
    sheetEmpty.textContent = '앱 목록을 읽는 중…';
  }
  // 그리고 **열 때마다 다시 읽는다.** 목록 자체는 잘 안 바뀌지만 순서는 바뀐다: 방금 쓴 앱이
  // 맨 위로 와야 하는데, 한 번 받아 두고 말면 차는 영영 옛날 순서를 보여 준다.
  try {
    const r = await (await fetch('/api/apps')).json();
    // 서버가 목록을 못 만들면 **왜인지**를 준다. "앱이 없습니다"로 뭉개지 않는다.
    if (Array.isArray(r)) { apps = r; homeError = ''; }
    else { apps = []; homeError = r?.error ?? '앱 목록을 읽지 못했습니다'; }
  } catch (e) {
    if (!apps.length) homeError = `폰에 물어보지 못했습니다: ${e}`;
  }
  if (sheetMode === 'home' && !sheet.hidden) renderHome(sheetFind.value);
}

async function openRecents() {
  sheetMode = 'recents';
  sheetTitle.textContent = '최근 앱';
  sheetFind.hidden = true;
  sheet.hidden = false;
  sheetGrid.replaceChildren();
  sheetEmpty.hidden = false;
  sheetEmpty.textContent = '읽는 중…';
  let tasks: TaskRow[] = [];
  let phone: TaskRow[] = [];
  let ourDisplay: number | null = null;
  let error = '';
  try {
    const r = await (await fetch('/api/tasks')).json();
    tasks = Array.isArray(r?.tasks) ? r.tasks : [];
    phone = Array.isArray(r?.phone) ? r.phone : [];
    ourDisplay = r?.display ?? null;
    error = r?.error ?? '';
  } catch (e) { error = `폰에 물어보지 못했습니다: ${e}`; }

  // 차 화면에서 도는 것이 먼저(최신순, 서버가 그 순서로 준다). 그 뒤에 **폰에서 쓰는 것**: 차에서 고르면
  // 보던 그대로 넘어오므로(restart=never) 이것도 한 번 누르면 되는 목록이다. 어디 있는지를 칸에 적어
  // 둔다 — 누르면 폰에서 사라진다는 것을 알고 눌러야 한다.
  sheetGrid.replaceChildren(
    ...tasks.map((t) => tile(t.label, t.package, null, (r) => pick(t.package, r))),
    ...phone.map((t) => tile(t.label, t.package, '📱 폰에서 쓰는 중 · 눌러서 가져오기', (r) => pick(t.package, r))),
  );
  sheetEmpty.hidden = tasks.length + phone.length > 0;
  sheetEmpty.textContent = error
    ? error
    : `차 화면(${ourDisplay ?? '?'})에서도 폰에서도 도는 앱이 없습니다. ● 홈에서 하나 고르세요.`;
}

/**
 * 차 화면이 비면 홈을 띄운다 — 처음 들어왔을 때와, 뒤로가기로 앱에서 빠져나왔을 때.
 *
 * 빈 가상 화면은 그릴 것이 없어 인코더가 아무것도 내지 않는다. 그래서 그대로 두면 차에는 검은
 * 화면(또는 마지막 프레임)이 남고, 운전자는 고장인지 아닌지 알 수 없다. 그 자리에 홈을 띄우면
 * 다음에 할 일이 화면에 있다.
 *
 * **들어가는 순간에만** 띄운다. 매번 띄우면 닫아 둔 홈이 계속 되살아나 성가시다 — 닫은 것은
 * 닫아 둔 채로 두고, 다음에 앱이 사라질 때 다시 띄운다.
 */
/** true = 앱이 있었다, false = 비어 있었다, null = 아직 본 적 없다. */
let hadAppOnCar: boolean | null = null;
/** `arrived`: 이 페이지 로드의 첫 상태 답이다 — 차에 막 탔을 때만 홈 대신 지난번 앱을 이어서 띄운다. */
function maybeOpenHome(st: any, arrived = false) {
  if (st?.source !== 'display') return;
  const empty = st.appDisplay === null && st.appOnPhone !== true;
  // 비어 **있게 된** 순간만 잡는다. 계속 비어 있는 동안 매번 띄우면 닫아 둔 홈이 2초마다 되살아난다.
  const becameEmpty = empty && hadAppOnCar !== false;
  hadAppOnCar = !empty;
  // 재생이 시작되기 전에는 띄우지 않는다: 시작은 화면을 한 번 눌러야 하는데, 그 손짓을 홈이 가로챈다.
  if (becameEmpty && started && sheet.hidden) void (arrived ? resumeOrHome() : openHome());
}

/**
 * 차에 막 탔는데 차 화면이 비어 있으면, 이 브라우저에서 **마지막으로 띄운 앱**을 그대로 다시 띄운다 —
 * 어제 보던 유튜브를 오늘 또 홈에서 찾아 누르게 하지 않는다. 뒤로가기로 앱을 닫아서 빈 경우는 여기로
 * 오지 않는다(홈이 뜬다): 방금 닫은 것을 도로 띄우면 안 되기 때문이다.
 *
 * 단 **폰이 그 앱을 쓰고 있으면 띄우지 않는다.** 차에서 띄우는 것은 옮기는 것이라(restart=never), 폰에서
 * 보던 화면을 말없이 끌어오게 된다. 그때는 홈을 띄우고 최근앱의 "눌러서 가져오기"에 맡긴다. 아무것도
 * 띄운 적이 없거나 `?resume=0` 이면 예전처럼 홈이다. 못 띄우면 역시 홈.
 */
async function resumeOrHome(): Promise<void> {
  let last = '';
  try { last = params.get('resume') === '0' ? '' : (localStorage.getItem('carcast.app') ?? ''); } catch { /* 저장소 없음 */ }
  const pkg = last.split('/')[0] ?? '';
  if (!pkg) { void openHome(); return; }
  try {
    const r = await (await fetch('/api/tasks')).json();
    if (Array.isArray(r?.phone) && r.phone.some((t: TaskRow) => t.package === pkg)) {
      note(`resume ${pkg} skipped: in use on the phone`);
      void openHome();
      return;
    }
    if (await launch(pkg, 'never', true)) return;
  } catch (e) {
    note(`resume ${pkg} failed: ${String(e)}`);
  }
  void openHome();
}

$('btn-home').addEventListener('click', openHome);
$('btn-recents').addEventListener('click', openRecents);
sheetFind.addEventListener('input', () => { if (sheetMode === 'home') renderHome(sheetFind.value); });

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

// Launch an app on the phone's virtual display (M4).
// Android keeps one task per app: if it is open on the phone, a start on the car *moves* it — the phone loses it,
// the car gets it exactly as it was (the video mid-play, the page half-read). That move is what the driver wants
// ("bring what I was watching"), so it is the default (`never` = never force-stop). `always` is the other way,
// a fresh copy from its front page, for the app that did not survive the move; it is one long press away.
// The phone's launcher can pull the task back at any time; the status poll below reports that instead of black.
type Restart = 'never' | 'always' | 'auto';
const ACTION_TEXT: Record<string, string> = {
  started: '앱 실행',
  restarted: '앱을 종료하고 차 화면에 새로 띄움',
  moved: '폰에서 보던 그대로 차 화면으로 가져옴',
  front: '이미 차 화면에 있던 앱을 앞으로',
};
/** 폰이 마지막으로 들고 있던 앱(= /api/status.app 의 패키지). ▶ 의 기본값이자 "차로 가져오기"의 대상. */
let lastPackage = '';

/**
 * `auto`: 사람이 누른 것이 아니라 차가 스스로 띄우는 것(지난번 앱 이어서). 실패해도 대화상자를 띄우지 않고
 * 상태줄에만 적는다 — 운전 중에 누르라고 뜨는 alert 는 최악이다.
 */
const launchApp = (name: string, restart: Restart, auto: boolean) => launch(name, restart, auto);
async function launch(name: string, restart: Restart = 'never', auto = false): Promise<boolean> {
  const fail = (msg: string) => { if (auto) notice(msg, 6000); else window.alert(msg); };
  try {
    const r = await (await fetch(`/api/app?name=${encodeURIComponent(name)}&restart=${restart}`, { method: 'POST' })).json();
    if (!r.ok) { fail(`앱 실행 실패: ${r.error}`); return false; }
    // 앱이 차 화면에 왔으니 그것을 보여 준다. 홈이 저절로 떠 있던 채로 ▶ 나 패널 버튼을 눌렀을 때
    // 시트가 그대로 남아 새 앱을 덮던 것 — 칸에서 고를 때는 pick 이 먼저 닫지만 다른 길은 아니었다.
    closeSheet();
    localStorage.setItem('carcast.app', name);
    lastPackage = r.package ?? name;
    appOnPhone = false;
    appEpoch++; // a status poll that was already in flight describes the world before this launch
    note(`app ${r.package ?? name}: ${r.action ?? '?'} (from display ${r.fromDisplay ?? '-'}, restart=${restart})${auto ? ' [resume]' : ''}`);
    const what = ACTION_TEXT[r.action] ?? '앱 실행';
    notice(auto ? `지난번 앱 이어서 — ${what}` : what);
    return true;
  } catch (e) {
    fail(`앱 실행 요청 실패: ${String(e)}`);
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
/**
 * 폰의 상태를 한 번 읽는다. 처음 한 번은 **바로** 부른다: setInterval 만으로는 첫 답이 2 초 뒤에 오고, 빈
 * 가상 화면은 프레임을 한 장도 안 보내므로 차에 타서 페이지를 열면 그 2 초 동안 검은 화면만 보였다.
 * 홈(또는 지난번 앱)이 그만큼 빨리 뜬다.
 */
async function pollStatus(): Promise<void> {
  if (document.hidden) return;
  try {
    const epoch = appEpoch;
    const first = lastStatus === null; // 이 페이지 로드의 첫 답 = "차에 막 탔다"
    const st = await (await fetch('/api/status')).json();
    if (epoch !== appEpoch) return;
    lastStatus = st;
    statusFailedAt = 0;
    if (typeof st.app === 'string' && st.app) lastPackage = st.app.split('/')[0]!;
    // 화질을 바꾸면 그림의 크기가 바뀐다(/api/encoder). 터치 좌표는 그 크기 기준이므로 여기서도 맞춘다.
    if (st.width && st.height) touch.setVideoSize(st.width, st.height);
    renderQuality();
    const now = st.appOnPhone === true;
    if (now !== appOnPhone) {
      appOnPhone = now;
      note(now ? `phone took ${st.app ?? 'the app'} (display ${st.appDisplay})` : 'app back on the car display');
      if (now) notice('폰이 앱을 가져갔습니다 — 화면의 버튼으로 되찾기', 8000);
    }
    // 폰 화면 전원은 차의 📵 로도, 폰의 전원 버튼으로도 바뀐다. 버튼은 언제나 서버가 말하는 쪽을 따른다.
    $('btn-screen').classList.toggle('off', st.screenOn === false);
    maybeOpenHome(st, first);
  } catch {
    // 폰이 잠깐 없는 것: 영상 소켓의 재접속이 알아서 덮는다. 다만 오래가면 상태 패널이 말한다.
    if (!statusFailedAt) statusFailedAt = Date.now();
  }
  updateStatePanel();
}
void pollStatus();
setInterval(pollStatus, 2000);

// 그림이 멈췄을 때 얼어붙은 프레임만 남기지 않는다: 왜 멈췄는지와 한 번에 누를 조치를 그 자리에 띄운다.
// 가상 디스플레이는 앱이 하나도 없으면 합성할 내용이 없어 인코더가 한 장도 내지 않는다 — 그래서 "앱이 없다"와
// "폰이 가져갔다"는 둘 다 화면상으로는 똑같이 멈춘 그림으로 보인다. 그 둘을 갈라 주는 것이 이 패널이다.
const statePanel = $('state');
const stateTitle = $('state-title');
const stateMsg = $('state-msg');
const stateAction = $<HTMLButtonElement>('state-action');
const stateAlt = $<HTMLButtonElement>('state-alt');
let stateAct: (() => void) | null = null;
let stateAltAct: (() => void) | null = null;
stateAction.addEventListener('click', () => stateAct?.());
stateAlt.addEventListener('click', () => stateAltAct?.());

// 정지 화면에서 프레임이 드문 것은 **정상**이다(폰 화면이 안 움직이면 인코더도 쉰다 — sparse.spec).
// 그래서 "프레임이 없다"만으로는 패널을 띄우지 않는다. 띄우는 것은 서버가 확실히 말해 주는 상태뿐이고,
// 나머지는 지금처럼 상태줄에만 적는다. 차에서 멀쩡한 그림을 덮는 것이 제일 나쁘다.
const NO_PHONE_MS = 6000;
// 폰이 잠든 것은 서버가 확실히 말해 주지만, 잠깐 조는 것까지 패널을 띄우면 시끄럽다.
const ASLEEP_MS = 3000;

type StateAction = { label: string; run: () => void };
/** `alt` 는 같은 자리의 두 번째 선택지(덜 흔한 쪽). 없으면 버튼 하나만 나온다. */
function showState(title: string, msg: string, action?: StateAction, alt?: StateAction): void {
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
  if (alt) {
    stateAlt.textContent = alt.label;
    stateAlt.hidden = false;
    stateAltAct = alt.run;
  } else {
    stateAlt.hidden = true;
    stateAltAct = null;
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
    // 가져오기가 먼저다 — 폰에서 보던 그대로 온다. 새로 열기는 옮기다 깨진 앱을 위한 두 번째 길이다.
    showState('📱 폰에서 그 앱을 쓰는 중', `안드로이드는 앱마다 화면을 하나만 둡니다. ${pkg} 이(가) 폰으로 넘어가 차 화면은 비어 있습니다.`, {
      label: '차로 가져오기',
      run: () => { if (lastPackage) void launch(lastPackage, 'never'); },
    }, {
      label: '새로 열기',
      run: () => { if (lastPackage) void launch(lastPackage, 'always'); },
    });
    return;
  }
  // 가상 화면에 앱의 task 가 하나도 없는 상태. 예전에는 여기서 "앱을 띄우세요" 패널을 띄웠는데,
  // 그것은 한 번 더 누르라는 말일 뿐이었다 — 눌러야 할 것이 뻔하면 그냥 그것을 띄우는 게 맞다.
  // 이제 홈이 그 자리를 대신한다(maybeOpenHome). 패널은 띄우지 않고 상태 이름만 남긴다.
  if (lastStatus && lastStatus.source === 'display' && lastStatus.appDisplay === null) {
    stateName = 'no-app';
    statePanel.hidden = true;
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

/**
 * 성능 추이. 순간 fps 하나로는 "이 기기가 이 해상도를 계속 감당하는가"에 답할 수 없다 — 더워져서
 * 느려지는 것은 몇십 분에 걸쳐 일어나고, 💾 를 누른 그 순간의 숫자에는 나타나지 않는다.
 *
 * 그래서 10 초마다 한 칸씩, 최근 한 시간을 들고 있다가 리포트에 함께 싣는다. 칸마다: 그 구간의
 * 평균 fps, 그때의 지연, 그 사이 늘어난 드롭, 그리고 디코더 적체(backlog). 적체가 자라면서 fps 가
 * 떨어지면 소프트 디코딩이 못 따라오는 것이고, 그때가 해상도·fps 를 낮출(또는 WebCodecs 를 볼)
 * 자리다. fps 만 떨어지고 적체가 0 이면 폰이 안 보내는 것이지 차가 못 푸는 것이 아니다 — 정지
 * 화면에서는 그게 정상이다.
 */
const PERF_SAMPLE_MS = 10_000;
const PERF_KEEP = 360; // 10초 × 360 = 한 시간
interface PerfSample { t: number; fps: number; lagMs: number; dropped: number; backlog: number; rttMs: number; skipped: number }
const perf: PerfSample[] = [];
let perfFrames = 0;
let perfDropped = 0;
let perfAt = Date.now();

setInterval(() => {
  if (!started) return;
  const s = renderer.stats();
  const now = Date.now();
  const secs = (now - perfAt) / 1000;
  perfAt = now;
  perf.push({
    t: Math.round((now - t0) / 1000),
    fps: secs > 0 ? Math.round((s.framesDecoded - perfFrames) / secs) : 0,
    lagMs: Math.round(s.latencyMs),
    dropped: s.droppedFrames - perfDropped,
    backlog: s.backlog ?? 0,
    rttMs,
    skipped: s.skipped ?? 0,
  });
  perfFrames = s.framesDecoded;
  perfDropped = s.droppedFrames;
  if (perf.length > PERF_KEEP) perf.shift();
  maybeStepDown(perf[perf.length - 1]!);
}, PERF_SAMPLE_MS);

// ── 화질 (인코더 설정) ─────────────────────────────────────────────────────────────────────────
//
// 폰의 인코더를 차에서 바꾼다(POST /api/encoder): 크기·fps·비트레이트. 폰은 앱을 그대로 둔 채 인코더만
// 갈아끼우고 선택을 기억한다. 무엇이 맞는지는 이 차의 디코더가 정한다 — 실측(2026-09-17)에서 720p 한 장에
// 10ms 였으니 60fps(예산 16ms)와 900p 는 해 볼 만하고, 1080p 60 은 아닐 것이다. 그래서 자동 모드는
// **내리기만** 한다: 적체나 드롭이 두 샘플 연속 보이면 한 단계 아래로. 올리는 것은 사람이 고른다.
interface Preset { id: string; label: string; width: number; height: number; fps: number; bitrate: number }
const PRESETS: Preset[] = [
  { id: '720p30', label: '기본 · 720p 30fps', width: 1280, height: 720, fps: 30, bitrate: 4_000_000 },
  { id: '720p60', label: '부드럽게 · 720p 60fps', width: 1280, height: 720, fps: 60, bitrate: 6_000_000 },
  { id: '900p30', label: '선명하게 · 900p 30fps', width: 1600, height: 900, fps: 30, bitrate: 6_000_000 },
  { id: '900p60', label: '선명하고 부드럽게 · 900p 60fps', width: 1600, height: 900, fps: 60, bitrate: 8_000_000 },
  { id: '1080p30', label: '최대 · 1080p 30fps', width: 1920, height: 1080, fps: 30, bitrate: 8_000_000 },
];
/** 부담 순서(가로×세로×fps). 자동 모드가 한 단계 내릴 때 이 순서를 따른다. */
const byCost = [...PRESETS].sort((a, b) => a.width * a.height * a.fps - b.width * b.height * b.fps);
const qualityPanel = $('quality');
const qualityGrid = $('quality-grid');
const qualityNow = $('quality-now');
const qualityAuto = $<HTMLInputElement>('quality-auto');
const qualityProbe = $<HTMLButtonElement>('quality-probe');
const qualityResult = $('quality-result');
let autoQuality = true;
try { autoQuality = localStorage.getItem('carcast.autoQuality') !== '0'; } catch { /* 저장소 없음 */ }
qualityAuto.checked = autoQuality;
qualityAuto.addEventListener('change', () => {
  autoQuality = qualityAuto.checked;
  try { localStorage.setItem('carcast.autoQuality', autoQuality ? '1' : '0'); } catch { /* 저장소 없음 */ }
});

/** 지금 폰이 도는 설정에 맞는 프리셋, 없으면 null (PC 명령으로 띄운 별난 설정). */
function currentPreset(): Preset | null {
  const st = lastStatus;
  if (!st?.width || !st?.height || !st?.maxFps) return null;
  return PRESETS.find((p) => p.width === st.width && p.height === st.height && p.fps === st.maxFps) ?? null;
}

let applyingPreset = false;
async function applyPreset(p: Preset, why: string): Promise<boolean> {
  if (applyingPreset) return false;
  applyingPreset = true;
  try {
    const q = `width=${p.width}&height=${p.height}&fps=${p.fps}&bitrate=${p.bitrate}`;
    const r = await (await fetch(`/api/encoder?${q}`, { method: 'POST' })).json();
    if (!r.ok) { notice(`화질 변경 실패: ${r.error ?? '?'}`, 6000); note(`encoder ${p.id} (${why}) failed: ${r.error}`); return false; }
    note(`encoder ${p.id} (${why}): ${r.width}x${r.height} ${r.fps}fps ${Math.round(r.bitrate / 1000)}k`);
    notice(`화질: ${p.label}`);
    if (lastStatus) { lastStatus.width = r.width; lastStatus.height = r.height; lastStatus.maxFps = r.fps; lastStatus.bitRate = r.bitrate; }
    touch.setVideoSize(r.width, r.height);
    renderQuality();
    return true;
  } catch (e) {
    notice(`화질 변경 요청 실패: ${String(e)}`, 6000);
    return false;
  } finally {
    applyingPreset = false;
  }
}

function renderQuality(): void {
  if (qualityPanel.hidden) return;
  const cur = currentPreset();
  const st = lastStatus;
  qualityNow.textContent = st?.width
    ? `지금: ${st.width}x${st.height} ${st.maxFps ?? '?'}fps ${st.bitRate ? Math.round(st.bitRate / 1000) + 'k' : ''}${cur ? '' : ' (프리셋 아님)'}`
    : '지금: 폰 응답 대기';
  for (const el of qualityGrid.querySelectorAll<HTMLElement>('.tile')) el.classList.toggle('held', el.dataset.preset === cur?.id);
}

function openQuality(): void {
  qualityGrid.replaceChildren(...PRESETS.map((p) => {
    const el = document.createElement('button');
    el.className = 'tile';
    el.dataset.preset = p.id;
    el.textContent = p.label;
    el.addEventListener('click', () => { void applyPreset(p, 'picked'); });
    return el;
  }));
  qualityPanel.hidden = false;
  renderQuality();
}
$('btn-quality').addEventListener('click', openQuality);
$('quality-close').addEventListener('click', () => { qualityPanel.hidden = true; });
qualityPanel.addEventListener('click', (e) => { if (e.target === qualityPanel) qualityPanel.hidden = true; });

/**
 * 자동 내리기. perf 샘플(10초)에서 적체가 4 를 넘거나 드롭이 있으면 "나쁜 샘플"이고, 두 번 연속이면
 * 한 단계 아래 프리셋으로 간다. 60초에 한 번만. 가장 낮은 단계이거나 프리셋이 아닌 설정이면 손대지 않는다.
 */
let badSamples = 0;
let lastStepDownAt = 0;
let autoStepDowns = 0;
function maybeStepDown(sample: PerfSample): void {
  const bad = sample.backlog > 4 || sample.dropped > 0;
  badSamples = bad ? badSamples + 1 : 0;
  if (!autoQuality || badSamples < 2) return;
  void stepDown(`backlog ${sample.backlog}, dropped ${sample.dropped}`);
}
async function stepDown(why: string): Promise<boolean> {
  if (Date.now() - lastStepDownAt < 60_000) return false;
  const cur = currentPreset();
  if (!cur) return false;
  const i = byCost.indexOf(cur);
  if (i <= 0) return false;
  lastStepDownAt = Date.now();
  badSamples = 0;
  autoStepDowns++;
  const ok = await applyPreset(byCost[i - 1]!, `auto: ${why}`);
  if (ok) notice(`차가 못 따라와 화질을 내렸습니다: ${byCost[i - 1]!.label}`, 8000);
  return ok;
}

// ── 끝에서 끝까지 지연 측정 ────────────────────────────────────────────────────────────────────
//
// lag(디코드)와 rtt(링크)는 재지만 터치 → 폰 렌더 → 인코드 → 전송 → 디코드까지의 전체 숫자는 아무도 몰랐다.
// 폰의 LatencyProbeActivity 는 터치마다 화면을 검정↔흰색으로 뒤집는다. 여기서는 그 액티비티를 차 화면에
// 띄우고, 가운데를 누른 뒤 디코드된 그림의 가운데 밝기가 뒤집힐 때까지를 잰다. 카메라도 노트북도 없이
// 차 안에서 글래스 투 글래스 값이 나온다. 결과는 상태줄과 💾 리포트에 실린다.
interface ProbeResult { n: number; fails: number; medianMs: number; p90Ms: number; minMs: number; samples: number[]; at: string; preset: string | null }
let latencyProbe: ProbeResult | null = null;
let probeRunning = false;
let lastLuma = -1;
let lastLumaAt = 0;
if (renderer instanceof H264Renderer) renderer.onLuma = (l, at) => { lastLuma = l; lastLumaAt = at; };
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

const PROBE_ACTIVITY = 'com.carcast/.ui.LatencyProbeActivity';
const PROBE_SLOT = 9; // 운전자의 손가락(0..)과 겹치지 않는 슬롯

async function runProbe(opts: { trials?: number; launch?: boolean } = {}): Promise<ProbeResult | null> {
  if (probeRunning) return null;
  if (!(renderer instanceof H264Renderer)) { notice('지연 측정은 h264 렌더러에서만 됩니다', 5000); return null; }
  probeRunning = true;
  qualityProbe.disabled = true;
  qualityResult.textContent = '측정 중…';
  const trials = opts.trials ?? 10;
  const launch = opts.launch ?? true;
  const samples: number[] = [];
  let fails = 0;
  try {
    if (launch) {
      const ok = await launchApp(PROBE_ACTIVITY, 'never', true);
      if (!ok) { qualityResult.textContent = '측정 액티비티를 못 띄웠습니다'; return null; }
      note('latency probe: activity launched');
      await sleep(1500);
    }
    for (let i = 0; i < trials; i++) {
      // 기준: 지금 그림의 밝기. 최근에 디코드된 그림이 있어야 한다(액티비티가 뜨면 프레임이 온다).
      const since = performance.now();
      if (lastLuma < 0) { await waitFor(() => lastLuma >= 0, 4000); }
      const base = lastLuma;
      const t0 = performance.now();
      const stamp = touchClock();
      control.send(encodeTouch(TouchAction.Down, PROBE_SLOT, 0.5, 0.5, 1, stamp));
      control.send(encodeTouch(TouchAction.Up, PROBE_SLOT, 0.5, 0.5, 0, stamp + 30));
      const flipped = await waitFor(() => lastLumaAt > since && Math.abs(lastLuma - base) > 64, 2500);
      if (flipped) samples.push(Math.round(lastLumaAt - t0));
      else fails++;
      qualityResult.textContent = `측정 중… ${i + 1}/${trials}${samples.length ? ` (마지막 ${samples[samples.length - 1]}ms)` : ''}`;
      await sleep(700);
    }
    const sorted = [...samples].sort((a, b) => a - b);
    const q = (f: number) => sorted.length ? sorted[Math.min(sorted.length - 1, Math.floor(f * (sorted.length - 1)))]! : -1;
    latencyProbe = { n: samples.length, fails, medianMs: q(0.5), p90Ms: q(0.9), minMs: sorted[0] ?? -1, samples, at: new Date().toISOString(), preset: currentPreset()?.id ?? null };
    const text = samples.length
      ? `끝에서 끝까지 ${latencyProbe.medianMs}ms (최소 ${latencyProbe.minMs}, p90 ${latencyProbe.p90Ms}, ${samples.length}회${fails ? `, 실패 ${fails}` : ''})`
      : `측정 실패 (${fails}회 모두 화면이 안 바뀜)`;
    qualityResult.textContent = text;
    notice(text, 10000);
    note(`latency probe: ${text}`);
    return latencyProbe;
  } finally {
    probeRunning = false;
    qualityProbe.disabled = false;
    if (launch) {
      // 측정 액티비티에서 나간다: 뒤로가기는 차 화면의 앱에 간다. 그 밑에 있던 앱이 돌아온다.
      control.send(encodeKey(KeyAction.Down, KEYCODE.BACK));
      control.send(encodeKey(KeyAction.Up, KEYCODE.BACK));
    }
  }
}

function waitFor(cond: () => boolean, timeoutMs: number): Promise<boolean> {
  return new Promise((resolve) => {
    const t0 = performance.now();
    const tick = () => {
      if (cond()) { resolve(true); return; }
      if (performance.now() - t0 > timeoutMs) { resolve(false); return; }
      setTimeout(tick, 4);
    };
    tick();
  });
}
qualityProbe.addEventListener('click', () => { void runProbe(); });

/** 초반과 최근을 견준다. 처음부터 느린 것과 **점점** 느려지는 것은 다른 문제다. */
function perfTrend(): { early: number; recent: number; backlog: number } | null {
  if (perf.length < 6) return null; // 1 분은 모여야 견줄 값이 된다
  const avg = (xs: PerfSample[]) => Math.round(xs.reduce((a, b) => a + b.fps, 0) / xs.length);
  const head = perf.slice(0, 3);
  const tail = perf.slice(-3);
  return { early: avg(head), recent: avg(tail), backlog: Math.max(...tail.map((x) => x.backlog)) };
}

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
  perf: perfTrend(),
  rttMs,
  keyframeRequests,
  touch: { ...touch.stats },
  latencyProbe,
  autoStepDowns,
  encoder: lastStatus ? { width: lastStatus.width, height: lastStatus.height, fps: lastStatus.maxFps, bitrate: lastStatus.bitRate } : null,
});
(window as any).__carcast = {
  stats, start, events, perf,
  restartVideo: () => videoWs.restart(),
  requestKeyframe: () => requestKeyframe('test'),
  // 화질·지연 측정을 테스트에서 몰기 위한 고리. feedLuma 는 디코더 대신 밝기를 넣어 준다(가짜 폰은 그림을 못 뒤집는다).
  applyPreset: (id: string) => { const p = PRESETS.find((x) => x.id === id); return p ? applyPreset(p, 'test') : Promise.resolve(false); },
  stepDown: (why = 'test') => stepDown(why),
  runProbe: (opts: { trials?: number; launch?: boolean }) => runProbe(opts),
  feedLuma: (l: number) => { lastLuma = l; lastLumaAt = performance.now(); },
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
    // 초반보다 눈에 띄게 느려졌으면 그 사실만 한 칸 붙인다 — 더워져서 느려지는 중인지 보는 자리다.
    s.perf && s.perf.recent < s.perf.early * 0.7 ? `⤵ ${s.perf.early}→${s.perf.recent}fps` : '',
    s.perf && s.perf.backlog > 10 ? `적체${s.perf.backlog}` : '',
  ].filter(Boolean).join(' ');
  // A notice (▶ result, 💾 saved, 폰이 가져감) owns the line until its own timeout clears or restores it.
  if (statsEl.textContent && !statsEl.textContent.startsWith(s.renderer)) return;
  const rtt = s.rttMs >= 0 ? ` rtt ${s.rttMs}ms` : '';
  statsEl.textContent = `${s.renderer} ${s.fps}fps lag ${Math.round(s.latencyMs)}ms${rtt} ${s.videoWs.open ? '●' : '○'}${extra ? ` ${extra}` : ''}`;
}, 500);

// 💾: push this session's numbers and event log to the phone (/api/reports, like the diag page).
$('btn-save').addEventListener('click', async () => {
  const s = stats();
  const st = lastStatus;
  const phone = st ? ` | 폰 build=${st.build ?? '?'} ${st.interactive === false ? '잠듦' : '깨어있음'} 화면${st.screenOn === false ? 'OFF' : 'ON'}${st.sleepRecoveries ? ` 되살림${st.sleepRecoveries}` : ''}${st.keptActive ? ` 활성유지${st.keptActive}` : ''} idle${Math.round((s.idleMs ?? 0) / 100) / 10}s` : '';
  const trend = s.perf ? ` 추이 ${s.perf.early}→${s.perf.recent}fps 적체${s.perf.backlog} (${Math.round(perf.length * PERF_SAMPLE_MS / 6000) / 10}분)` : '';
  const rtt = s.rttMs >= 0 ? ` rtt ${s.rttMs}ms` : '';
  const e2e = s.latencyProbe && s.latencyProbe.n ? ` 끝까지${s.latencyProbe.medianMs}ms` : '';
  const summary = `session ${s.renderer} ${s.fps}fps lag ${Math.round(s.latencyMs)}ms${rtt} frames ${s.framesDecoded} packets ${s.packets} ws↻${s.videoWs.connects - 1}/${s.videoWs.failures} 복구${s.recoveries} 드롭${s.droppedFrames}${s.keyframeRequests ? ` 키프레임요청${s.keyframeRequests}` : ''}${e2e}${s.lastError ? ` err=${s.lastError}` : ''}${trend}${phone}`;
  // 폰 쪽 상태를 같이 싣는다. 실차 리포트 #26·#27 은 차 쪽 수치만 담고 있어서 "전원 버튼을 눌렀을 때
  // 폰이 실제로 잠들었는지, 패널만 꺼졌는지"를 끝내 가릴 수 없었다 — 원인을 가르는 바로 그 정보였다.
  // 대응책이 있는지도 같이 남긴다. 소프트 디코딩이 버거운 것으로 드러났을 때 다음 수가 무엇이냐는
  // 질문에는 "이 차에 WebCodecs(하드웨어 디코더)가 있느냐"가 답이고, 그건 나중에 물어볼 수 없다 —
  // 차를 다시 몰고 나가야 하기 때문이다. 참고: VideoDecoder 는 SecureContext 라 평문 http 에서는
  // 있어도 안 보인다(실측). secure 가 false 면 X 는 "없다"가 아니라 "모른다"로 읽어야 한다.
  const caps = {
    webcodecs: typeof (window as any).VideoDecoder === 'function',
    secure: window.isSecureContext,
    cores: navigator.hardwareConcurrency ?? 0,
    memGb: (navigator as any).deviceMemory ?? 0,
  };
  let server: any = null;
  try { server = await (await fetch('/api/status')).json(); } catch { /* 폰이 없으면 그대로 비워 둔다 */ }
  const body = { version: 1, page: location.href, kind: 'session', clientTime: new Date().toISOString(), env: { UA: navigator.userAgent, viewport: `${innerWidth}x${innerHeight}`, dpr: devicePixelRatio }, stats: s, server, events, perf, caps, summary };
  const prev = statsEl.textContent;
  try {
    const r = await (await fetch('/api/report', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body) })).json();
    statsEl.textContent = r.ok ? `저장됨 #${r.id}` : `저장 실패 ${r.error ?? ''}`;
  } catch (e) { statsEl.textContent = `저장 실패 ${String(e)}`; }
  setTimeout(() => { if (statsEl.textContent?.startsWith('저장')) statsEl.textContent = prev; }, 2500);
});

export type { MediaType };

// 실제로 일어나는 일들. 폰 쪽(사용자가 폰을 만진다)과 차 쪽(브라우저에서 일어난다)을 한 목록에 두고
// 아무 순서로나 섞을 수 있게 한다 — 생애주기 버그는 "그 둘이 겹칠 때"만 나오기 때문이다.
//
// 폰 쪽 조작은 adb 로 한다. 실기기에 USB/무선으로 붙어 있어도 그대로 쓸 수 있지만, 기본 대상은
// 가상 폰(tools/virtual-phone)이다.
import { execFileSync } from 'node:child_process';
import type { Page } from '@playwright/test';
import { sleep } from './probe';

const ADB = process.env.ADB ?? (process.env.ANDROID_HOME ? `${process.env.ANDROID_HOME}/platform-tools/adb` : 'adb');

export const adbShell = (cmd: string) =>
  execFileSync(ADB, ['shell', cmd], { encoding: 'utf8', timeout: 30_000 }).trim();

export function adbAvailable(): boolean {
  try { adbShell('true'); return true; } catch { return false; }
}

export interface Ctx {
  page: Page;
  base: string;
  /** 시나리오가 쓰는 앱의 패키지와 컴포넌트 (기기에서 실제로 띄울 수 있는 것으로 고른다) */
  app: { pkg: string; component: string };
  post: (path: string) => Promise<any>;
}

export interface Action {
  id: string;
  side: 'phone' | 'car';
  title: string;
  /** 이 동작 뒤에 영상이 끊기는 것이 **정상**인가 (끊김 자체를 실패로 세지 않는다) */
  mayStopVideo?: boolean;
  run(ctx: Ctx): Promise<void>;
  /**
   * 이 동작의 결과가 서버 상태에 **반영되기까지** 기다린다. 앱 전환에서는 이 시간이 곧 체감이다:
   * 폰이 앱을 가져간 순간과 차가 그 사실을 알고 안내를 띄우는 순간 사이의 간격. 표에 "알아챔" 으로 남는다.
   */
  settle?(ctx: Ctx): Promise<number | null>;
}

/**
 * 측정 중에 가상 화면을 계속 움직여 둔다(자체 control 소켓으로). 가상 디스플레이는 픽셀이 바뀔 때만
 * 버퍼를 올리므로, 정지 화면에서는 "영상이 돌아왔는지"를 물을 수 없다. 차 페이지의 소켓과 별개의
 * 연결이라 페이지 쪽 터치 상태를 건드리지 않는다.
 */
export async function wiggle(base: string): Promise<() => void> {
  // Node 22 의 전역 WebSocket (ws 패키지 타입을 끌어오지 않으려고).
  const ws = new WebSocket(`${base.replace(/^http/, 'ws')}/ws/control`);
  await new Promise<void>((resolve, reject) => {
    ws.addEventListener('open', () => resolve(), { once: true });
    ws.addEventListener('error', () => reject(new Error('control ws 실패')), { once: true });
  });
  const u16 = (v: number) => Math.max(0, Math.min(65535, Math.round(v * 65535)));
  const send = (action: number, y: number, pressure: number) => {
    const b = Buffer.alloc(9);
    b.writeUInt8(1, 0); b.writeUInt8(action, 1); b.writeUInt8(0, 2);
    b.writeUInt16BE(u16(0.5), 3); b.writeUInt16BE(u16(y), 5); b.writeUInt16BE(u16(pressure), 7);
    try { ws.send(b); } catch { /* 닫혔다 */ }
  };
  let y = 0.3;
  const timer = setInterval(() => {
    y = y > 0.7 ? 0.3 : y + 0.1;
    send(0, y, 1); send(2, y + 0.05, 1); send(1, y + 0.05, 0);
  }, 250);
  return () => { clearInterval(timer); try { ws.close(); } catch { /* 이미 닫힘 */ } };
}

/** cond() 가 참이 될 때까지의 ms. 시간 안에 안 되면 null. */
export async function until(cond: () => Promise<boolean>, timeoutMs = 15_000): Promise<number | null> {
  const t0 = Date.now();
  for (;;) {
    if (await cond().catch(() => false)) return Date.now() - t0;
    if (Date.now() - t0 > timeoutMs) return null;
    await sleep(250);
  }
}

const appOnPhoneIs = (base: string, want: boolean) => async () => {
  const res = await fetch(`${base}/api/status`, { signal: AbortSignal.timeout(3000) });
  return (await res.json()).appOnPhone === want;
};

/** `cmd package resolve-activity --brief` 로 이 기기에서 띄울 수 있는 앱 하나를 고른다. */
export function pickApp(): { pkg: string; component: string } {
  const candidates = [
    'com.android.settings', 'com.google.android.deskclock', 'com.android.deskclock',
    'com.google.android.calculator', 'com.android.calculator2', 'com.android.contacts',
  ];
  for (const pkg of candidates) {
    try {
      const component = adbShell(`cmd package resolve-activity --brief ${pkg}`).split('\n').pop()!.trim();
      if (component.includes('/')) return { pkg, component };
    } catch { /* 다음 후보 */ }
  }
  throw new Error('띄울 수 있는 앱을 찾지 못했다');
}

// ---- 폰 쪽 --------------------------------------------------------------------------------

export const PHONE_ACTIONS: Action[] = [
  {
    id: 'phone.power-button',
    side: 'phone',
    title: '폰 전원 버튼을 누른다',
    mayStopVideo: true,
    // 사용자가 실제로 누르는 그 버튼. 토글이라 상태에 따라 재우거나 깨운다 — 그 모호함까지가 현실이다.
    async run() { adbShell('input keyevent 26'); await sleep(1500); },
  },
  {
    id: 'phone.sleep',
    side: 'phone',
    title: '폰을 재운다 (KEYCODE_SLEEP)',
    mayStopVideo: true,
    async run() { adbShell('input keyevent 223'); await sleep(1500); },
  },
  {
    id: 'phone.wake',
    side: 'phone',
    title: '폰을 깨운다 (KEYCODE_WAKEUP)',
    async run() { adbShell('input keyevent 224'); adbShell('wm dismiss-keyguard'); await sleep(1000); },
  },
  {
    id: 'phone.open-app',
    side: 'phone',
    title: '폰 런처에서 그 앱을 연다 (차에서 뺏어간다)',
    mayStopVideo: true,
    async run({ app }) {
      adbShell(`am start --display 0 -n ${app.component} -a android.intent.action.MAIN -c android.intent.category.LAUNCHER`);
      await sleep(500);
    },
    // 차가 "폰이 가져갔다"를 알아채기까지. 이 값이 크면 운전자는 그동안 멈춘 그림을 이유도 모른 채 본다.
    settle: ({ base }) => until(appOnPhoneIs(base, true), 15_000),
  },
  {
    id: 'phone.home',
    side: 'phone',
    title: '폰에서 홈 버튼',
    async run() { adbShell('input keyevent 3'); await sleep(800); },
  },
  {
    id: 'phone.force-stop-app',
    side: 'phone',
    // 앱이 사라지면 가상 디스플레이에 그릴 것이 없다 — 흔들어도 프레임은 안 나온다. 정상이다.
    title: '그 앱을 강제 종료한다 (차 화면은 빈다)',
    mayStopVideo: true,
    async run({ app }) { adbShell(`am force-stop ${app.pkg}`); await sleep(1000); },
  },
  {
    id: 'phone.force-stop-carcast-ui',
    side: 'phone',
    title: 'CarCast 앱(UI)을 강제 종료한다 — 분리 실행된 서버는 살아 있어야 한다',
    async run() { adbShell('am force-stop com.carcast'); await sleep(1500); },
  },
  {
    id: 'phone.doze',
    side: 'phone',
    title: '도즈에 들어간다 (충전 해제 + force-idle)',
    async run() {
      adbShell('dumpsys battery unplug');
      adbShell('dumpsys deviceidle force-idle');
      await sleep(2000);
    },
  },
  {
    id: 'phone.undoze',
    side: 'phone',
    title: '도즈에서 나온다',
    async run() {
      adbShell('dumpsys deviceidle unforce');
      adbShell('dumpsys battery reset');
      await sleep(1500);
    },
  },
];

// ---- 차 쪽 --------------------------------------------------------------------------------

export const CAR_ACTIONS: Action[] = [
  {
    id: 'car.open-app',
    side: 'car',
    title: '차에서 ▶ 로 앱을 띄운다',
    async run({ post, app }) { await post(`/api/app?name=${encodeURIComponent(app.pkg)}`); await sleep(500); },
    settle: ({ base }) => until(appOnPhoneIs(base, false), 15_000),
  },
  {
    id: 'car.screen-off',
    side: 'car',
    title: '차에서 📵 (폰 화면만 끈다)',
    async run({ post }) { await post('/api/screen?on=0'); await sleep(1200); },
  },
  {
    id: 'car.screen-on',
    side: 'car',
    title: '차에서 📵 해제 (폰 화면을 켠다)',
    async run({ post }) { await post('/api/screen?on=1'); await sleep(1200); },
  },
  {
    id: 'car.tap',
    side: 'car',
    title: '차 화면을 누른다',
    async run({ page }) {
      const box = await page.locator('#stage').boundingBox();
      if (box) {
        await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
        await page.mouse.down(); await page.mouse.up();
      }
      await sleep(500);
    },
  },
  {
    id: 'car.drag',
    side: 'car',
    title: '차 화면을 끌어 스크롤한다',
    async run({ page }) {
      const box = await page.locator('#stage').boundingBox();
      if (!box) return;
      const x = box.x + box.width / 2;
      await page.mouse.move(x, box.y + box.height * 0.7);
      await page.mouse.down();
      for (let i = 1; i <= 8; i++) await page.mouse.move(x, box.y + box.height * (0.7 - 0.05 * i));
      await page.mouse.up();
      await sleep(600);
    },
  },
  {
    id: 'car.long-press',
    side: 'car',
    title: '차 화면을 길게 누른다',
    async run({ page }) {
      const box = await page.locator('#stage').boundingBox();
      if (!box) return;
      await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
      await page.mouse.down();
      await sleep(900);
      await page.mouse.up();
      await sleep(500);
    },
  },
  {
    id: 'car.two-finger',
    side: 'car',
    title: '두 손가락으로 벌린다 (멀티터치)',
    // 차 화면은 멀티터치다. 슬롯 배분이 틀리면 여기서 유령 손가락이 남는다.
    async run({ page }) {
      const box = await page.locator('#stage').boundingBox();
      if (!box) return;
      const cx = box.x + box.width / 2;
      const cy = box.y + box.height / 2;
      const a = await page.context().newCDPSession(page);
      const touch = (type: 'touchStart' | 'touchMove' | 'touchEnd', points: { x: number; y: number }[]) =>
        a.send('Input.dispatchTouchEvent', { type, touchPoints: points.map((p) => ({ ...p, id: points.indexOf(p) })) });
      await touch('touchStart', [{ x: cx - 20, y: cy }, { x: cx + 20, y: cy }]);
      for (let i = 1; i <= 6; i++) {
        await touch('touchMove', [{ x: cx - 20 - i * 12, y: cy }, { x: cx + 20 + i * 12, y: cy }]);
      }
      await touch('touchEnd', []);
      await a.detach();
      await sleep(600);
    },
  },
  {
    id: 'car.nav-back',
    side: 'car',
    title: '차의 ◀ (뒤로)',
    async run({ page }) { await page.locator('#bar button[data-key=back]').click(); await sleep(600); },
  },
  {
    // 폰의 HOME 키를 보내던 동작이었다. 그 키는 폰의 기본 디스플레이 것이라 누르면 차에서 보던 앱이
    // display 0 으로 끌려갔다(실차 리포트 #30). 지금은 차가 자기 목록을 띄우고, 폰은 건드리지 않는다.
    id: 'car.nav-home',
    side: 'car',
    title: '차의 ● (홈) — 차 자기 앱 목록',
    async run({ page }) {
      await page.locator('#btn-home').click();
      await sleep(600);
      await page.locator('#launcher-close').click();
      await sleep(400);
    },
  },
  {
    id: 'car.nav-recents',
    side: 'car',
    title: '차의 ■ (최근 앱) — 이 화면에서 도는 것',
    async run({ page }) {
      await page.locator('#btn-recents').click();
      await sleep(800);
      await page.locator('#launcher-close').click();
      await sleep(400);
    },
  },
  {
    id: 'car.type-text',
    side: 'car',
    title: '차의 키보드로 글자를 보낸다',
    async run({ page }) {
      await page.locator('#btn-keyboard').click();
      await page.locator('#kbd').fill('carcast');
      await sleep(600);
    },
  },
  {
    id: 'car.touch-while-socket-dies',
    side: 'car',
    title: '손가락을 댄 채 제어 소켓이 죽는다',
    // UP 이 영영 안 오는 상황. 서버가 제스처를 취소하지 않으면 그 손가락은 영원히 눌린 채로 남는다.
    async run({ page }) {
      const box = await page.locator('#stage').boundingBox();
      if (!box) return;
      await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
      await page.mouse.down();
      await sleep(300);
      await page.evaluate(() => (window as any).__carcast.restartControl());
      await sleep(1200);
      await page.mouse.up();
      await sleep(800);
    },
  },
  {
    id: 'car.drop-video-ws',
    side: 'car',
    title: '영상 소켓이 끊긴다 (차 Wi-Fi 가 튄다)',
    mayStopVideo: true,
    async run({ page }) { await page.evaluate(() => (window as any).__carcast.restartVideo()); await sleep(500); },
  },
  {
    id: 'car.reload',
    side: 'car',
    title: '차에서 페이지를 새로 연다 (탭이 죽었다 살아난다)',
    mayStopVideo: true,
    // 새로고침 뒤 첫 터치까지가 차의 실제 동작이다 — 자동재생이 막혀 있으므로.
    async run({ page }) {
      await page.reload();
      await page.locator('#overlay').click({ position: { x: 100, y: 100 } });
    },
  },
];

export const ALL_ACTIONS = [...PHONE_ACTIONS, ...CAR_ACTIONS];
export const byId = (id: string): Action => {
  const a = ALL_ACTIONS.find((x) => x.id === id);
  if (!a) throw new Error(`모르는 동작: ${id}`);
  return a;
};

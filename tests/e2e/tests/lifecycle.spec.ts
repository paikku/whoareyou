// 폰 생애주기 × 웹 생애주기: 둘이 겹칠 때 차 화면이 어떻게 되는가.
//
// 여기서 보려는 것은 "통과/실패"보다 **상태와 복구 시간**이다. 실차에서 겪은 문제들(전원 버튼을 누르니
// 차가 얼어붙음, 폰이 앱을 도로 가져가 검은 화면, 새로고침 뒤 안 돌아옴)은 전부 두 생애주기가 어긋난 자리에서
// 났다. 그래서 각 시나리오는 동작을 순서대로 걸고 매 단계의 양쪽 상태와 복구 시간을 표로 남긴다.
// 기기마다 갈리는 것(전원/패널)은 **기록만** 하고, 어디서나 성립해야 하는 것만 단언한다.
//
// 대상: 가상 폰 또는 실기기. 브라우저 없이 도는 검사는 tests/device.
//   tools/virtual-phone/vphone.sh up
//   BASE_URL=http://127.0.0.1:3333 CHROME_PATH=... npx playwright test tests/lifecycle.spec.ts --project=model-y-2026.26
import { existsSync, mkdirSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { expect, test } from '@playwright/test';
import { Ctx, adbAvailable, byId, pickApp } from './lifecycle/actions';
import { clientStats, probe, sleep } from './lifecycle/probe';
import { Step, applyAndMeasure, reset, table } from './lifecycle/runner';

const BASE = process.env.BASE_URL ?? '';
test.skip(!BASE, '진짜 서버가 있어야 한다 (tools/virtual-phone/vphone.sh up)');
test.skip(!adbAvailable(), '폰 쪽 조작에 adb 가 필요하다');
test.describe.configure({ mode: 'serial', timeout: 300_000 });

const OUT = resolve(process.cwd(), '../../out/lifecycle');
const report: string[] = [];

async function context(page: any): Promise<Ctx> {
  const post = async (path: string) => {
    const res = await fetch(BASE + path, { method: 'POST', signal: AbortSignal.timeout(15_000) });
    return res.json();
  };
  return { page, base: BASE, app: pickApp(), post };
}

async function open(page: any) {
  await page.goto('/');
  await page.locator('#overlay').click({ position: { x: 100, y: 100 } });
  await page.waitForFunction(() => (window as any).__carcast.stats().framesDecoded > 5, null, { timeout: 45_000 });
}

/** 시나리오 하나: 동작을 순서대로 걸고 표를 남긴다. */
async function scenario(page: any, name: string, actionIds: string[]): Promise<Step[]> {
  const ctx = await context(page);
  await open(page);
  await reset(ctx);
  const steps: Step[] = [];
  for (const id of actionIds) {
    steps.push(await applyAndMeasure(ctx, byId(id)));
  }
  report.push(`### ${name}\n\n${table(steps)}\n`);
  test.info().attach(`${name}.md`, { body: table(steps), contentType: 'text/markdown' });
  return steps;
}

test.afterAll(async () => {
  if (!report.length) return;
  const path = `${OUT}/report.md`;
  if (!existsSync(dirname(path))) mkdirSync(dirname(path), { recursive: true });
  writeFileSync(path, `# 생애주기 시나리오 (${new Date().toISOString()})\n\n${report.join('\n')}\n`);
  console.log(`\n생애주기 보고서: ${path}\n\n${report.join('\n')}`);
});

// ---------------------------------------------------------------------------------------------

test('앱 전환: 폰과 차가 같은 앱을 두고 주고받는다', async ({ page }) => {
  const steps = await scenario(page, '앱 전환', [
    'car.open-app',          // 차가 띄운다
    'phone.open-app',        // 폰이 가져간다
    'car.open-app',          // 차가 되찾는다 (강제 종료 후 새로)
    'phone.home',            // 폰에서 홈 — 차 화면은 그대로여야 한다
    'phone.force-stop-app',  // 앱이 죽는다
    'car.open-app',          // 다시 띄운다
  ]);

  // 어디서나 성립해야 하는 것: 서버는 살아 있고, 차가 띄운 직후에는 앱이 차에 있다.
  for (const s of steps) expect(s.after.serverAlive, `${s.title} 뒤 서버가 죽었다`).toBe(true);
  const [carOpen, phoneTook, carRetook] = steps;
  expect(carOpen.after.appOnPhone, '차가 띄웠는데 앱이 차에 없다').toBe(false);
  expect(phoneTook.after.appOnPhone, '폰이 가져갔는데 서버가 모른다').toBe(true);
  expect(carRetook.after.appOnPhone, '차가 되찾았는데 여전히 폰에 있다고 한다').toBe(false);
  // 폰이 가져간 뒤에도 차는 계속 프레임을 받는다(빈 디스플레이). 그래서 "검은 화면"이 되는 것이고,
  // 사용자에게는 상태줄로 알린다 — 이 사실 자체를 표에 남겨 둔다.
  expect(carRetook.recoveryMs, '차가 되찾은 뒤 영상이 돌아오지 않았다').not.toBeNull();
});

test('웹 생애주기: 소켓이 끊기고 탭이 새로 열려도 돌아온다', async ({ page }) => {
  const steps = await scenario(page, '웹 생애주기', [
    'car.drop-video-ws',
    'car.tap',
    'car.reload',
    'car.tap',
  ]);
  for (const s of steps) {
    expect(s.after.serverAlive, `${s.title} 뒤 서버가 죽었다`).toBe(true);
    expect(s.recoveryMs, `${s.title} 뒤 영상이 돌아오지 않았다`).not.toBeNull();
  }
  const drop = steps[0]!;
  expect(drop.recoveryMs!, `소켓 재접속에 ${drop.recoveryMs}ms 걸렸다`).toBeLessThan(15_000);
});

test('폰 생애주기: CarCast 앱을 죽여도, 도즈에 들어가도 서버는 산다', async ({ page }) => {
  const steps = await scenario(page, '폰 생애주기', [
    'phone.force-stop-carcast-ui',
    'phone.doze',
    'phone.undoze',
  ]);
  for (const s of steps) {
    expect(s.after.serverAlive, `${s.title} 뒤 서버가 죽었다 — 차에서는 되살릴 방법이 없다`).toBe(true);
  }
  expect(steps[0]!.recoveryMs, 'UI 를 죽였더니 영상이 멈췄다 (분리 실행이 아니다)').not.toBeNull();
});

test('전원 버튼 × 📵: 폰 화면과 차 화면이 서로를 끌고 가는가', async ({ page }) => {
  const ctx = await context(page);
  await open(page);
  await reset(ctx);
  const can = await ctx.post('/api/screen?on=0');
  await ctx.post('/api/screen?on=1');
  if (can?.ok !== true) {
    // 에뮬레이터/기기에 따라 SurfaceControl 디스플레이 전원이 안 먹는다. 그 사실만 남기고 넘어간다.
    report.push(`### 전원 버튼 × 📵\n\n이 기기에서는 디스플레이 전원 전환이 되지 않는다(\`/api/screen\` → ok:false). 실기기에서 확인할 것.\n`);
    test.info().annotations.push({ type: 'skip-reason', description: '이 기기에서 디스플레이 전원 전환 불가' });
    return;
  }

  const steps = await scenario(page, '전원 버튼 × 📵', [
    'car.screen-off',      // 차에서 폰 화면만 끈다 — 차 영상은 계속돼야 한다
    'phone.power-button',  // 그 상태에서 사용자가 폰 전원 버튼을 누른다
    'car.screen-on',       // 차에서 다시 켜 본다
    'phone.sleep',         // 폰을 재운다 — 가상 디스플레이는?
    'phone.wake',
  ]);
  for (const s of steps) expect(s.after.serverAlive, `${s.title} 뒤 서버가 죽었다`).toBe(true);

  // 📵 는 폰 화면만 끄는 기능이다. 차 영상까지 멈추면 기능 자체가 성립하지 않는다.
  const screenOff = steps[0]!;
  expect(screenOff.recoveryMs, '📵 를 눌렀더니 차 영상까지 멈췄다').not.toBeNull();
  expect(screenOff.after.screenOn).toBe(false);

  // 그 뒤 전원 버튼을 누른 결과는 기기마다 다르다 — 단언하지 않고, 서버가 말하는 상태와
  // 실제 디스플레이 상태가 어긋나면 표에 남긴다.
  const afterPower = steps[1]!;
  const real = (await import('./lifecycle/actions')).adbShell('dumpsys display | grep -m1 -o "mScreenState=[A-Z]*"');
  report.push(`\n전원 버튼 직후 — 서버가 보는 폰 화면: \`${afterPower.after.screenOn}\`, 기기가 말하는 상태: \`${real || '읽지 못함'}\`\n`);
  void sleep; void probe; void clientStats;
});

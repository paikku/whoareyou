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
import { Step, applyAndMeasure, openCarPage, reset, table } from './lifecycle/runner';

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

const THROUGHPUT = !process.env.NO_THROUGHPUT;

async function open(page: any) {
  const flowing = await openCarPage(page, BASE);
  if (!flowing) {
    test.info().annotations.push({ type: 'note', description: '프레임이 오지 않은 채로 시작한다 (가상 폰: 인코더가 쉬는 중)' });
  }
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
    'phone.force-stop-app',  // 앱이 죽는다 — 차 화면은 빈다(그릴 것이 없다)
    'car.open-app',          // 다시 띄운다
  ]);

  // 어디서나 성립해야 하는 것: 서버는 살아 있고, 차가 띄운 직후에는 앱이 차에 있다.
  for (const s of steps) expect(s.after.serverAlive, `${s.title} 뒤 서버가 죽었다`).toBe(true);
  const [carOpen, phoneTook, carRetook] = steps;
  expect(carOpen.after.appOnPhone, '차가 띄웠는데 앱이 차에 없다').toBe(false);
  expect(phoneTook.noticeMs, '폰이 가져갔는데 서버가 끝내 알아채지 못했다').not.toBeNull();
  expect(phoneTook.after.appOnPhone, '폰이 가져갔는데 서버가 모른다').toBe(true);
  // 운전자가 이유도 모른 채 멈춘 그림을 보는 시간. 감시자의 빠른 주기(1초) 안쪽이어야 한다.
  expect(phoneTook.noticeMs!, `차가 알아채는 데 ${phoneTook.noticeMs}ms 걸렸다`).toBeLessThan(8_000);
  expect(carRetook.after.appOnPhone, '차가 되찾았는데 여전히 폰에 있다고 한다').toBe(false);
  // 폰이 가져간 뒤에도 차는 계속 프레임을 받는다(빈 디스플레이). 그래서 "검은 화면"이 되는 것이고,
  // 사용자에게는 상태줄로 알린다 — 이 사실 자체를 표에 남겨 둔다.
  if (THROUGHPUT) expect(carRetook.recoveryMs, '차가 되찾은 뒤 영상이 돌아오지 않았다').not.toBeNull();
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
    expect(s.after.pageAlive, `${s.title} 뒤 차 페이지가 죽었다`).toBe(true);
    if (THROUGHPUT) expect(s.recoveryMs, `${s.title} 뒤 영상이 돌아오지 않았다`).not.toBeNull();
  }
  // 소켓은 프레임과 무관하게 다시 붙어야 한다 — 그것만은 어디서나 확인할 수 있다.
  for (const s of steps) expect(s.after.wsOpen, `${s.title} 뒤 영상 소켓이 닫힌 채로 남았다`).toBe(true);
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
  if (THROUGHPUT) expect(steps[0]!.recoveryMs, 'UI 를 죽였더니 영상이 멈췄다 (분리 실행이 아니다)').not.toBeNull();
});

test('터치: 끌기·길게 누르기·멀티터치·키보드가 폰까지 가고, 손가락이 남지 않는다', async ({ page }) => {
  const ctx = await context(page);
  await open(page);
  await reset(ctx);
  const before = await probe(page, BASE);

  const steps = await scenario(page, '터치 상호작용', [
    'car.tap',
    'car.drag',
    'car.long-press',
    'car.two-finger',
    'car.nav-back',
    'car.type-text',
    'car.touch-while-socket-dies',   // 제스처 도중 소켓이 죽는다
    'car.tap',                        // 그 다음 탭이 멀쩡한 한 손가락 탭이어야 한다
  ]);

  for (const s of steps) {
    expect(s.after.serverAlive, `${s.title} 뒤 서버가 죽었다`).toBe(true);
    // 어떤 제스처도 손가락을 남기면 안 된다. 남으면 다음 탭이 유령 손가락과의 멀티터치가 된다.
    expect(s.after.pointersDown, `${s.title} 뒤 손가락이 ${s.after.pointersDown}개 눌린 채 남았다`).toBe(0);
  }
  const last = steps[steps.length - 1]!;
  expect(last.after.injected!, '제스처가 폰까지 가지 않았다').toBeGreaterThan(before.injected!);
  expect(last.after.injectFailed, 'INJECT_EVENTS 가 막혀 있다 (삼성: USB 디버깅(보안 설정))')
    .toBe(before.injectFailed);
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
  if (THROUGHPUT) expect(screenOff.recoveryMs, '📵 를 눌렀더니 차 영상까지 멈췄다').not.toBeNull();
  expect(screenOff.after.screenOn, '📵 를 눌렀는데 서버가 화면이 켜져 있다고 한다').toBe(false);

  // 실차 리포트 #26: 운전 중 전원 버튼을 누르면 기기가 통째로 자고 가상 디스플레이도 멈춘다 — 앱은
  // 멀쩡한데 차 화면만 얼어붙는다. 운전자가 원한 것은 📵 였으므로 서버가 그렇게 되돌려 놓아야 한다.
  const slept = steps[3]!;
  expect(slept.after.serverAlive).toBe(true);
  report.push(`\n폰을 재운 뒤 — interactive=\`${slept.after.interactive}\` screenOn=\`${slept.after.screenOn}\` ` +
    `되살린 횟수=\`${slept.after.sleepRecoveries ?? '-'}\`\n`);
  expect(slept.after.interactive, '폰이 잠든 채로 남았다 — 차 화면이 멈춘다 (sleep recovery)').toBe(true);
  expect(slept.after.screenOn, '되살리면서 폰 화면까지 켜 버렸다 — 운전자가 원한 것은 어두운 폰이다').toBe(false);

  // 📵 로 꺼 둔 사이에 전원 버튼을 누르면 패널은 켜진다. 그때 서버가 계속 "꺼짐"이라고 우기면
  // 차의 📵 버튼은 그 뒤로 계속 뒤집힌 채로 남는다 — 실 사용에서 제일 짜증나는 종류의 버그다.
  // ScreenPower 의 감시자가 기기 쪽을 믿고 장부를 버리는지 본다.
  // 전원 버튼으로 폰이 잠든 뒤 차에서 📵 를 다시 누르면 화면이 돌아와야 한다. 안 돌아오면 운전자는
  // 차에서 빠져나갈 방법이 없다 (2026-09-11 시나리오가 잡은 상태).
  const screenOn = steps[2]!;
  expect(screenOn.after.screenOn, '📵 해제를 눌렀는데 폰 화면이 켜지지 않았다 — 차에서 되돌릴 방법이 없다')
    .toBe(true);

  const afterPower = steps[1]!;
  report.push(
    `\n전원 버튼 직후 — 서버: screenOn=\`${afterPower.after.screenOn}\` forcedOff=\`${afterPower.after.forcedOff}\`, ` +
    `기기: panelState=\`${afterPower.after.panelState}\` interactive=\`${afterPower.after.interactive}\`, ` +
    `되돌린 횟수=\`${afterPower.after.powerReconciled}\`\n`,
  );
  if (afterPower.after.panelState === null) {
    test.info().annotations.push({ type: 'note', description: 'dumpsys display 에서 패널 상태를 읽지 못해 대조를 건너뛴다' });
  } else {
    expect(
      afterPower.after.screenOn === false && afterPower.after.panelState === 'ON',
      '패널은 켜졌는데 서버는 아직 꺼졌다고 한다 — 📵 버튼이 뒤집힌 채로 남는다',
    ).toBe(false);
  }
  void sleep; void probe; void clientStats;
});

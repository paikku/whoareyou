// One app, two displays: Android moves an app's only task between the phone and the virtual display instead
// of running two copies. The phone side handles the car → phone direction by restarting the app (restart=auto,
// covered on the device, see docs/testing-guide.md "M4-b"); this checks the car-side UI around it — the ▶
// result notice, and the "phone took the app" state the car shows instead of a black picture.
import { expect, test } from '@playwright/test';
import { startPlayback, stats } from './helpers';

test.skip(!!process.env.BASE_URL, 'needs the fake phone (test hook /api/fake/app-on-phone)');

test('▶ reports what the phone did with the app, and the car notices when the phone takes it back', async ({ page }) => {
  await page.goto('/');
  await startPlayback(page);
  await page.waitForFunction(() => (window as any).__carcast.stats().framesDecoded > 5, null, { timeout: 30_000 });

  // ▶ → prompt → POST /api/app. No task anywhere: plain start.
  page.once('dialog', (d) => d.accept('com.example.app'));
  await page.locator('#btn-app').click();
  await expect(page.locator('#stats')).toContainText('앱 실행');
  const events = () => page.evaluate(() => (window as any).__carcast.events as string[]);
  expect((await events()).join('\n')).toContain('app com.example.app: started (from display -)');

  // The phone's launcher pulls the task back to display 0: within one status poll the stats line says so.
  await page.evaluate(async () => (await fetch('/api/fake/app-on-phone?on=1')).json());
  await expect(page.locator('#stats')).toContainText('폰이 앱을 가져갔습니다', { timeout: 10_000 });
  expect((await stats(page)).appOnPhone).toBe(true);
  // 얼어붙은 그림만 남기지 않는다: 왜 멈췄는지와 한 번에 되찾는 버튼이 그 자리에 떠야 한다.
  await expect(page.locator('#state')).toBeVisible();
  await expect(page.locator('#state-title')).toContainText('폰에서 그 앱을 쓰는 중');
  expect((await stats(page)).state).toBe('app-on-phone');
  expect((await events()).join('\n')).toContain('phone took com.example.app (display 0)');
  // …and after the notice, the regular stats line carries the marker as long as it lasts.
  await expect(page.locator('#stats')).toContainText('📱폰이 앱을 가져감', { timeout: 15_000 });

  // 그 버튼이 실제로 되찾는다 — 패키지명을 다시 타이핑하지 않아도 된다(차에서 키보드를 여는 일 자체가 부담이다).
  await page.locator('#state-action').click();
  await expect(page.locator('#stats')).toContainText('폰에서 쓰던 앱을 종료하고 차 화면에 새로 띄움');
  expect((await stats(page)).appOnPhone).toBe(false);
  await expect(page.locator('#state')).toBeHidden();

  // ▶ 로도 같은 일이 된다 (예전 경로).
  await page.evaluate(async () => (await fetch('/api/fake/app-on-phone?on=1')).json());
  await expect(page.locator('#state')).toBeVisible({ timeout: 10_000 });
  page.once('dialog', (d) => d.accept('com.example.app'));
  await page.locator('#btn-app').click();
  await expect(page.locator('#stats')).toContainText('폰에서 쓰던 앱을 종료하고 차 화면에 새로 띄움');
  expect((await stats(page)).appOnPhone).toBe(false);
  expect((await events()).join('\n')).toContain('app com.example.app: restarted (from display 0)');
  await expect(page.locator('#stats')).not.toContainText('폰이 앱을 가져감', { timeout: 15_000 });
});

// 쓰던 앱이 닫히면(스와이프, 강제 종료, 앱 자신의 종료) 가상 화면에 그릴 것이 없어 마지막 프레임이
// 얼어붙는다. 고장과 구분되지 않으므로 차는 그 자리에서 이유와 다음 행동을 말해 줘야 한다.
// 무작위 탐색(seed 501398062)에서 앱이 사라진 뒤 12단계 동안 아무 설명 없이 죽은 화면이 이어졌다 —
// 누적 프레임 수를 "아직 아무것도 안 나왔다"로 읽고 있어서 패널이 뜨지 못했다.
test('쓰던 앱이 닫히면 차가 홈을 띄운다', async ({ page }) => {
  await page.goto('/');
  await startPlayback(page);
  await page.waitForFunction(() => (window as any).__carcast.stats().framesDecoded > 5, null, { timeout: 30_000 });
  await expect(page.locator('#state')).toBeHidden();

  // 예전에는 여기서 "차 화면에 띄운 앱이 없습니다 / 앱 띄우기" 패널을 띄웠다. 그것은 한 번 더
  // 누르라는 말일 뿐이었다 — 누를 것이 뻔하면 그냥 그것을 띄우는 게 맞다. 이제 홈이 그 자리에 온다.
  await page.evaluate(async () => (await fetch('/api/fake/no-app')).json());
  await expect(page.locator('#launcher')).toBeVisible({ timeout: 10_000 });
  await expect(page.locator('#launcher-title')).toHaveText('홈');
  expect((await stats(page)).state).toBe('no-app');
  await expect(page.locator('#state')).toBeHidden();

  // 그리고 거기서 고른 앱은 ▶ 와 같은 길로 간다.
  await page.locator('#launcher-grid .tile').first().click();
  await expect(page.locator('#launcher')).toBeHidden();
  await expect(page.locator('#stats')).toContainText('앱');
});

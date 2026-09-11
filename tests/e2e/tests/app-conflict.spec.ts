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

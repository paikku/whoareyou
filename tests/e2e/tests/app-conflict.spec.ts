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
  expect((await events()).join('\n')).toContain('phone took com.example.app (display 0)');
  // …and after the notice, the regular stats line carries the marker as long as it lasts.
  await expect(page.locator('#stats')).toContainText('📱폰이 앱을 가져감', { timeout: 15_000 });

  // ▶ again: the phone force-stops its copy and starts a fresh one on the car (action "restarted").
  page.once('dialog', (d) => d.accept('com.example.app'));
  await page.locator('#btn-app').click();
  await expect(page.locator('#stats')).toContainText('폰에서 쓰던 앱을 종료하고 차 화면에 새로 띄움');
  expect((await stats(page)).appOnPhone).toBe(false);
  expect((await events()).join('\n')).toContain('app com.example.app: restarted (from display 0)');
  await expect(page.locator('#stats')).not.toContainText('폰이 앱을 가져감', { timeout: 15_000 });
});

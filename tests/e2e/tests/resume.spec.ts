// 차에 타서 페이지를 열었는데 차 화면이 비어 있으면, 이 브라우저에서 마지막으로 띄운 앱을 그대로 다시
// 띄운다(main.ts resumeOrHome). 홈에서 어제의 앱을 또 찾아 누르는 것이 매일의 첫 동작이었다.
//
// 세 갈래를 본다: 이어서 띄우는 경우, 폰이 그 앱을 쓰고 있어 띄우지 않는 경우(말없이 끌어오면 안 된다),
// 그리고 ?resume=0 으로 끈 경우. 뒤로가기로 닫아서 빈 경우는 input.spec 이 보듯 홈이 뜬다 — 여기로 오지 않는다.
import { expect, test } from '@playwright/test';
import { startPlayback, statusOf } from './helpers';

test.skip(!!process.env.BASE_URL, 'needs the fake phone (test hooks /api/fake/no-app, /api/reset)');

const events = (page: import('@playwright/test').Page) =>
  page.evaluate(() => ((window as any).__carcast.events as string[]).join('\n'));

/** 이 브라우저가 지난번에 `pkg` 를 띄웠고, 지금 차 화면은 비어 있는 채로 페이지를 새로 연다. */
async function arriveWithLastApp(page: import('@playwright/test').Page, pkg: string, url = '/') {
  await page.goto('/');
  await page.evaluate(() => fetch('/api/reset'));
  await page.evaluate((p) => localStorage.setItem('carcast.app', p), pkg);
  await page.evaluate(() => fetch('/api/fake/no-app', { method: 'POST' }));
  await page.goto(url);
  await startPlayback(page);
}

test('차에 타면 지난번 앱을 이어서 띄운다', async ({ page }) => {
  await arriveWithLastApp(page, 'com.google.android.youtube');
  await expect.poll(() => events(page)).toContain('app com.google.android.youtube: started (from display -, restart=never) [resume]');
  await expect(page.locator('#stats')).toContainText('지난번 앱 이어서');
  await expect(page.locator('#launcher')).toBeHidden();
  expect((await statusOf(page)).app).toBe('com.google.android.youtube/.Main');
});

test('폰이 그 앱을 쓰고 있으면 끌어오지 않고 홈을 띄운다', async ({ page }) => {
  // 가짜 폰은 Spotify 를 언제나 폰 화면(0)에서 쓰는 중으로 준다.
  await arriveWithLastApp(page, 'com.spotify.music');
  await expect(page.locator('#launcher')).toBeVisible({ timeout: 10_000 });
  await expect.poll(() => events(page)).toContain('resume com.spotify.music skipped: in use on the phone');
  expect(await events(page)).not.toContain('app com.spotify.music:');
  expect((await statusOf(page)).app).toBeUndefined();
});

test('?resume=0 이면 예전처럼 홈이다', async ({ page }) => {
  await arriveWithLastApp(page, 'com.google.android.youtube', '/?resume=0');
  await expect(page.locator('#launcher')).toBeVisible({ timeout: 10_000 });
  expect(await events(page)).not.toContain('[resume]');
});

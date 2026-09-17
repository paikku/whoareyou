// One app, two displays: Android moves an app's only task between the phone and the virtual display instead
// of running two copies. That move is the point — the driver gets what they were watching, as it was — so the
// car asks for it by default (restart=never) and keeps "start it fresh" (restart=always) one long press or one
// second button away. The phone side of both is covered on the device (docs/testing-guide.md "M4-b"); this
// checks the car-side UI around it — the ▶ result notice, which mode each gesture sends, and the "phone took
// the app" state the car shows instead of a black picture.
import { expect, test } from '@playwright/test';
import { startPlayback, stats } from './helpers';

test.skip(!!process.env.BASE_URL, 'needs the fake phone (test hook /api/fake/app-on-phone)');
// 가짜 폰은 하나가 모든 스펙·프로필을 차례로 받는다. 앞 스펙이 남긴 앱 상태로 시작하지 않는다.
// (페이지 안에서 부른다 — `request` 픽스처는 브라우저의 100.99.9.9 → 127.0.0.1 매핑을 타지 않는다.)
const reset = (page: import('@playwright/test').Page) => page.evaluate(() => fetch('/api/reset'));

test('▶ reports what the phone did with the app, and the car notices when the phone takes it back', async ({ page }) => {
  await page.goto('/');
  await reset(page);
  await startPlayback(page);
  await page.waitForFunction(() => (window as any).__carcast.stats().framesDecoded > 5, null, { timeout: 30_000 });

  // ▶ → prompt → POST /api/app. No task anywhere: plain start.
  page.once('dialog', (d) => d.accept('com.example.app'));
  await page.locator('#btn-app').click();
  await expect(page.locator('#stats')).toContainText('앱 실행');
  const events = () => page.evaluate(() => (window as any).__carcast.events as string[]);
  expect((await events()).join('\n')).toContain('app com.example.app: started (from display -, restart=never)');

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
  // 그리고 **그대로** 가져온다: 폰에서 보던 상태째로(restart=never → moved). 새로 띄우는 것이 아니다.
  await expect(page.locator('#state-action')).toHaveText('차로 가져오기');
  await expect(page.locator('#state-alt')).toHaveText('새로 열기');
  await page.locator('#state-action').click();
  await expect(page.locator('#stats')).toContainText('폰에서 보던 그대로 차 화면으로 가져옴');
  expect((await events()).join('\n')).toContain('app com.example.app: moved (from display 0, restart=never)');
  expect((await stats(page)).appOnPhone).toBe(false);
  await expect(page.locator('#state')).toBeHidden();

  // 두 번째 버튼은 새로 연다 — 옮기다 깨진 앱을 위한 길(restart=always → restarted).
  await page.evaluate(async () => (await fetch('/api/fake/app-on-phone?on=1')).json());
  await expect(page.locator('#state')).toBeVisible({ timeout: 10_000 });
  await page.locator('#state-alt').click();
  await expect(page.locator('#stats')).toContainText('앱을 종료하고 차 화면에 새로 띄움');
  expect((await events()).join('\n')).toContain('app com.example.app: restarted (from display 0, restart=always)');
  expect((await stats(page)).appOnPhone).toBe(false);
  await expect(page.locator('#state')).toBeHidden();

  // ▶ 도 가져오기다 (예전 경로).
  await page.evaluate(async () => (await fetch('/api/fake/app-on-phone?on=1')).json());
  await expect(page.locator('#state')).toBeVisible({ timeout: 10_000 });
  page.once('dialog', (d) => d.accept('com.example.app'));
  await page.locator('#btn-app').click();
  await expect(page.locator('#stats')).toContainText('폰에서 보던 그대로 차 화면으로 가져옴');
  expect((await stats(page)).appOnPhone).toBe(false);
  expect((await events()).join('\n')).toContain('app com.example.app: moved (from display 0, restart=never)');
  await expect(page.locator('#stats')).not.toContainText('폰이 앱을 가져감', { timeout: 15_000 });
});

// 홈의 칸: 짧게 누르면 가져오기, 길게 누르면 새로 열기. 운전 중 손가락이 떨려 조금 오래 눌린 것이
// "새로 열기"로 읽히면 보던 것을 잃으므로, 경계(600ms)의 양쪽을 다 본다.
test('홈에서 짧게 누르면 가져오고, 길게 누르면 새로 연다', async ({ page }) => {
  await page.goto('/');
  await reset(page);
  await startPlayback(page);
  await page.waitForFunction(() => (window as any).__carcast.stats().framesDecoded > 5, null, { timeout: 30_000 });
  const events = () => page.evaluate(() => (window as any).__carcast.events as string[]);

  // 폰이 앱을 들고 있는 상태에서 홈을 연다 — 가져오기/새로 열기가 갈리는 상황이다.
  await page.evaluate(async () => (await fetch('/api/fake/app-on-phone?on=1')).json());
  await expect(page.locator('#state')).toBeVisible({ timeout: 10_000 });
  await page.locator('#btn-home').click();
  await expect(page.locator('#launcher')).toBeVisible();
  const first = page.locator('#launcher-grid .tile').first();
  const pkg = await first.getAttribute('data-pkg');
  await expect(first).toHaveAttribute('title', '길게 누르면 새로 열기');

  // 짧게: 400ms 눌러도 아직 짧다.
  await first.click({ delay: 400 });
  await expect(page.locator('#launcher')).toBeHidden();
  await expect(page.locator('#stats')).toContainText('폰에서 보던 그대로 차 화면으로 가져옴');
  expect((await events()).join('\n')).toContain(`app ${pkg}: moved (from display 0, restart=never)`);

  // 길게: 손가락이 눌린 채로 시간이 차면 그 자리에서 새로 연다. 떼는 순간의 click 은 두 번째 실행이 되면 안 된다.
  await page.evaluate(async () => (await fetch('/api/fake/app-on-phone?on=1')).json());
  await expect(page.locator('#state')).toBeVisible({ timeout: 10_000 });
  await page.locator('#btn-home').click();
  await expect(page.locator('#launcher')).toBeVisible();
  const before = (await events()).filter((l) => l.includes(`app ${pkg}:`)).length;
  await page.locator('#launcher-grid .tile').first().click({ delay: 900 });
  await expect(page.locator('#launcher')).toBeHidden();
  await expect(page.locator('#stats')).toContainText('앱을 종료하고 차 화면에 새로 띄움');
  const lines = (await events()).filter((l) => l.includes(`app ${pkg}:`));
  expect(lines.length).toBe(before + 1);
  expect(lines[lines.length - 1]).toContain(`app ${pkg}: restarted (from display 0, restart=always)`);
  expect((await stats(page)).appOnPhone).toBe(false);
});

// 쓰던 앱이 닫히면(스와이프, 강제 종료, 앱 자신의 종료) 가상 화면에 그릴 것이 없어 마지막 프레임이
// 얼어붙는다. 고장과 구분되지 않으므로 차는 그 자리에서 이유와 다음 행동을 말해 줘야 한다.
// 무작위 탐색(seed 501398062)에서 앱이 사라진 뒤 12단계 동안 아무 설명 없이 죽은 화면이 이어졌다 —
// 누적 프레임 수를 "아직 아무것도 안 나왔다"로 읽고 있어서 패널이 뜨지 못했다.
test('쓰던 앱이 닫히면 차가 홈을 띄운다', async ({ page }) => {
  await page.goto('/');
  await reset(page);
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

// 홈이 저절로 떠 있는 채로(차 화면이 비어서) ▶ 로 앱을 띄우면 홈은 닫혀야 한다. 칸에서 고를 때만 닫고
// ▶ 와 패널 버튼은 안 닫던 것이 CI 에서 걸렸다: 새 앱 위에 홈이 남아 그 아래의 패널 버튼을 덮었다.
test('홈이 떠 있는 채로 ▶ 로 앱을 띄우면 홈이 닫힌다', async ({ page }) => {
  await page.goto('/');
  await reset(page);
  await startPlayback(page);
  await page.evaluate(async () => (await fetch('/api/fake/no-app')).json());
  await expect(page.locator('#launcher')).toBeVisible({ timeout: 10_000 });
  page.once('dialog', (d) => d.accept('com.example.app'));
  await page.locator('#btn-app').click();
  await expect(page.locator('#stats')).toContainText('앱 실행');
  await expect(page.locator('#launcher')).toBeHidden();
});

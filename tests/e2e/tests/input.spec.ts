import { expect, test } from '@playwright/test';
import { startPlayback, statusOf } from './helpers';

test.skip(!!process.env.BASE_URL, 'touch assertions need the fake phone state endpoint');

test('touch on the picture arrives as normalised coordinates', async ({ page }) => {
  await page.goto('/');
  await startPlayback(page);
  await page.waitForFunction(() => (window as any).__carcast.stats().controlWs.open);
  await page.evaluate(() => fetch('/api/reset'));

  // The 16:9 picture is letterboxed inside #stage; compute the expected mapping from the layout.
  const box = await page.locator('#stage').boundingBox();
  if (!box) throw new Error('no stage');
  const aspect = 16 / 9;
  let vw = box.width, vh = box.height, ox = 0, oy = 0;
  if (box.width / box.height > aspect) { vw = box.height * aspect; ox = (box.width - vw) / 2; }
  else { vh = box.width / aspect; oy = (box.height - vh) / 2; }
  const target = { x: ox + vw * 0.25, y: oy + vh * 0.75 };
  await page.mouse.move(box.x + target.x, box.y + target.y);
  await page.mouse.down();
  await page.mouse.up();

  await expect.poll(async () => (await statusOf(page)).touches.length, { timeout: 5000 }).toBeGreaterThanOrEqual(2);
  const touches = (await statusOf(page)).touches;
  const down = touches.find((t: any) => t.action === 0);
  const up = touches.find((t: any) => t.action === 1);
  expect(down).toBeTruthy();
  expect(up).toBeTruthy();
  expect(down.x).toBeCloseTo(0.25, 2);
  expect(down.y).toBeCloseTo(0.75, 2);
});

test('◀ 는 폰으로 가고, ● 홈은 **키를 보내지 않는다**', async ({ page }) => {
  await page.goto('/');
  await startPlayback(page);
  await page.waitForFunction(() => (window as any).__carcast.stats().controlWs.open);
  await page.evaluate(() => fetch('/api/reset'));

  await page.locator('#bar button[data-key=back]').click();
  await expect.poll(async () => (await statusOf(page)).keys.length).toBeGreaterThanOrEqual(2);
  expect((await statusOf(page)).keys.map((k: any) => k.keycode)).toContain(4); // KEYCODE_BACK

  // HOME(3)/APP_SWITCH(187) 은 이벤트에 실린 디스플레이가 아니라 **폰의 기본 디스플레이** 것으로
  // 처리된다. 그래서 보내면 차에서 보던 앱이 폰으로 끌려간다(실차 리포트 #30). 차는 대신 자기
  // 목록을 띄운다 — 그 계약을 여기서 못박는다: 눌러도 폰으로 가는 키가 하나도 늘지 않아야 한다.
  const before = (await statusOf(page)).keys.length;
  await page.locator('#btn-home').click();
  await expect(page.locator('#launcher')).toBeVisible();
  await page.locator('#launcher-close').click();
  await page.locator('#btn-recents').click();
  await expect(page.locator('#launcher')).toBeVisible();
  await page.locator('#launcher-close').click();
  expect((await statusOf(page)).keys.length).toBe(before);
});

test('차 홈에서 앱을 고르면 그 앱이 차 화면에 뜬다', async ({ page }) => {
  await page.goto('/');
  await startPlayback(page);
  await page.evaluate(() => fetch('/api/reset'));

  await page.locator('#btn-home').click();
  const tiles = page.locator('#launcher-grid .tile');
  // 목록은 **아이콘을 기다리지 않고** 곧바로 뜬다. 아이콘까지 한 번에 실어 오던 것이 실기기에서
  // 새 연결을 전부 막았기 때문이다(실차 리포트 #31~33).
  await expect(tiles).toHaveCount(3);
  // 그 다음 보이는 칸부터 아이콘이 채워지고, 못 그리는 앱은 첫 글자 타일로 남는다.
  await expect(page.locator('#launcher-grid .tile img')).toHaveCount(2);
  await expect(page.locator('#launcher-grid .tile .fallback')).toHaveCount(1);

  // 찾기: 운전 중에 목록을 훑는 대신 한 번에 좁힐 수 있어야 한다.
  await page.locator('#launcher-find').fill('you');
  await expect(tiles).toHaveCount(1);
  await tiles.first().click();

  // 고른 것이 ▶ 와 같은 길로 간다: 폰이 그 앱을 받았고, 시트는 닫혔다.
  await expect(page.locator('#launcher')).toBeHidden();
  await expect.poll(async () => (await statusOf(page)).apps ?? []).toContain('com.google.android.youtube');
});

test('최근 앱은 이 화면에서 도는 것을 보여 준다', async ({ page }) => {
  await page.goto('/');
  await startPlayback(page);
  await page.evaluate(() => fetch('/api/reset'));

  await page.locator('#btn-recents').click();
  await expect(page.locator('#launcher-title')).toHaveText('최근 앱');
  await expect(page.locator('#launcher-grid .tile')).toHaveCount(1);

  // 폰이 가져간 앱은 **차의 최근앱에 뜨지 않는다** — 차 화면에서 도는 것만 보여 준다는 계약이다.
  // 대신 비었다는 말로 끝내지 않고 폰 쪽에 몇 개가 있는지를 적어 준다.
  await page.locator('#launcher-close').click();
  await page.evaluate(() => fetch('/api/fake/app-on-phone', { method: 'POST' }));
  await page.locator('#btn-recents').click();
  await expect(page.locator('#launcher-grid .tile')).toHaveCount(0);
  await expect(page.locator('#launcher-empty')).toContainText('폰 쪽에 1개');
});

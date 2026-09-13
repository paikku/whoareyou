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

// 세 버튼은 폰의 탐색 막대와 같은 순서·같은 모양이어야 한다. 운전 중에 읽고 고르는 것이 아니라
// 손이 아는 자리를 누르는 것이라, 순서가 바뀌면 엉뚱한 것이 눌린다. 순서와 글리프를 못박는다.
test('탐색 막대는 왼쪽부터 최근·홈·뒤로, 글자가 아니라 선으로 그려진다', async ({ page }) => {
  await page.goto('/');
  const ids = await page.locator('#nav button').evaluateAll((els) =>
    els.map((e) => e.id || e.getAttribute('data-key')));
  expect(ids).toEqual(['btn-recents', 'btn-home', 'back']);

  // 이모지나 글자가 아니라 선 그림이다(폰의 것과 같은 인상을 주는 유일한 방법이고, 차 화면의
  // 글꼴이 무엇이든 같게 보인다). 최근=세로줄 셋, 홈=원, 뒤로=꺾인 선.
  for (const id of ['#btn-recents', '#btn-home', '#bar button[data-key=back]']) {
    await expect(page.locator(`${id} svg`)).toHaveCount(1);
    expect((await page.locator(id).innerText()).trim()).toBe('');
  }
  expect(await page.locator('#btn-recents svg path').getAttribute('d')).toMatch(/^M5.5 5.5v13M12 5.5v13M18.5 5.5v13$/);
  await expect(page.locator('#btn-home svg circle')).toHaveCount(1);

  // 상자에 들어 있지 않다: 테두리를 두르면 우리 버튼처럼 보이고, 폰의 막대와 달라진다.
  const border = await page.locator('#btn-home').evaluate((e) => getComputedStyle(e).borderTopWidth);
  expect(border).toBe('0px');
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

test('홈은 열 때마다 다시 읽어 최근 사용순을 보여 준다', async ({ page }) => {
  await page.goto('/');
  await startPlayback(page);
  await page.evaluate(() => fetch('/api/reset'));

  const names = () => page.locator('#launcher-grid .tile .name').allTextContents();
  await page.locator('#btn-home').click();
  await expect(page.locator('#launcher-grid .tile')).toHaveCount(3);
  const before = await names();
  await page.locator('#launcher-find').fill('spot');
  await page.locator('#launcher-grid .tile').first().click();
  await expect(page.locator('#launcher')).toBeHidden();

  // 방금 쓴 앱이 맨 위여야 한다. 목록을 한 번만 받아 두면 여기서 옛 순서가 그대로 나온다 —
  // 서버가 아무리 정렬해 줘도 차는 영영 모른다.
  await page.locator('#btn-home').click();
  await expect.poll(async () => (await names())[0]).toBe('Spotify');
  expect(before[0]).not.toBe('Spotify');
});

test('차 화면이 비면 홈이 저절로 뜬다 — 처음 들어올 때, 그리고 앱에서 빠져나왔을 때', async ({ page }) => {
  await page.goto('/');
  await page.evaluate(() => fetch('/api/fake/no-app', { method: 'POST' }));
  await startPlayback(page);
  // 빈 가상 화면은 그릴 것이 없어 영상이 멈춘다. 그 자리에 다음 할 일을 띄운다.
  await expect(page.locator('#launcher')).toBeVisible();
  await expect(page.locator('#launcher-title')).toHaveText('홈');

  // 닫으면 닫힌 채로 있어야 한다 — 계속 비어 있다고 2초마다 되살아나면 못 쓴다.
  await page.locator('#launcher-close').click();
  await page.waitForTimeout(3000);
  await expect(page.locator('#launcher')).toBeHidden();

  // 앱을 띄웠다가 다시 사라지면(뒤로가기로 빠져나온 경우) 그때 다시 뜬다.
  await page.evaluate(() => fetch('/api/app?name=com.google.android.youtube', { method: 'POST' }));
  // 차가 그 사실을 **본 뒤에** 뒤집어야 한다. 차는 2초마다 폰을 보므로, 보기 전에 뒤집으면
  // "앱이 있었다가 사라졌다"가 아니라 "계속 비어 있었다"가 된다. 차가 알았다는 신호는 스스로
  // 적어 두는 상태 이름이다('no-app' 이 아니게 된다).
  await expect
    .poll(async () => page.evaluate(() => (window as any).__carcast.stats().state))
    .not.toBe('no-app');
  await page.evaluate(() => fetch('/api/fake/no-app', { method: 'POST' }));
  await expect(page.locator('#launcher')).toBeVisible();
});

test('뒤로가기로 앱을 빠져나오면 홈이 곧바로 뜬다', async ({ page }) => {
  await page.goto('/');
  await startPlayback(page);
  await page.evaluate(() => fetch('/api/app?name=com.google.android.youtube', { method: 'POST' }));
  await expect
    .poll(async () => page.evaluate(() => (window as any).__carcast.stats().state))
    .not.toBe('no-app');

  // 마지막 뒤로가기로 앱이 닫힌 상황.
  await page.evaluate(() => fetch('/api/fake/no-app', { method: 'POST' }));
  const t0 = Date.now();
  await page.locator('#bar button[data-key=back]').click();
  await expect(page.locator('#launcher')).toBeVisible({ timeout: 2000 });
  // 상태 폴링(2초)만 믿으면 여기서 몇 초를 검은 화면으로 보낸다. 누른 직후에 직접 물어보는 값어치가 그것이다.
  expect(Date.now() - t0).toBeLessThan(2000);

  // 그리고 "띄운 앱이 없습니다 / 앱 띄우기" 패널은 더 이상 없다 — 누를 것이 뻔하면 그것을 띄운다.
  await page.locator('#launcher-close').click();
  await expect(page.locator('#state')).toBeHidden();
});

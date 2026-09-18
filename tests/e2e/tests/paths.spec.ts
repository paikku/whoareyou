// 경로(path) 고르기 — "무엇으로 만들고 무엇으로 푸는가" 한 벌을 사람이 바꾸는 길.
//
// 왜 이것이 검사할 값어치가 있나: 차도 폰도 세대가 다르다. 자동 선택은 "아마 이게 제일 나을 것"이지
// 답이 아니고, 틀렸을 때 운전자에게 남는 것은 멈춘 화면뿐이다. 그래서 ① 무엇을 고를 수 있는지 보이고,
// ② 안 되는 것은 왜 안 되는지 적고, ③ 고른 것이 이 차에 남고, ④ 남은 선택이 나중에 못 쓰게 되면
// 조용히 자동으로 돌아가는 것까지가 계약이다. ④ 가 없으면 펌웨어가 바뀐 어느 날 검은 화면이 된다.
import { expect, test } from '@playwright/test';
import { stats } from './helpers';

test.skip(!!process.env.BASE_URL, 'needs the fake phone state endpoint');

const PATH_KEY = 'carcast.path';

test('시트가 경로를 전부 내고, 못 가는 것은 이유를 적는다', async ({ page }) => {
  await page.goto('/');
  await page.waitForFunction(() => !!(window as any).__carcast, null, { timeout: 30_000 });
  await page.locator('#btn-quality').click();

  // 자동 + 경로 넷.
  await expect(page.locator('#path-grid .tile')).toHaveCount(5);
  await expect(page.locator('#path-grid .tile[data-path="auto"]')).toHaveClass(/held/);
  await expect(page.locator('#path-note')).toContainText('소프트 디코더');

  // 평문 오리진이고 이 가짜 폰에는 공개 CA 인증서가 없으므로 하드웨어 경로는 갈 곳이 없다 —
  // 회색으로 두되 **왜** 인지를 적어야 한다. "그냥 없음"으로 보이면 운전자가 할 수 있는 일이 없다.
  const hw = page.locator('#path-grid .tile[data-path="webcodecs"]');
  await expect(hw).toHaveClass(/away/);
  await expect(hw).toContainText('인증서');

  // 갈 수 있는 것들은 설명 한 줄을 달고 있다(고르는 근거가 그것뿐이다).
  await expect(page.locator('#path-grid .tile[data-path="mjpeg"]')).toContainText('H.264 디코더가 아예 없어도');
});

test('고른 경로는 이 차에 남는다', async ({ page }) => {
  await page.goto('/');
  await page.waitForFunction(() => !!(window as any).__carcast, null, { timeout: 30_000 });
  expect((await stats(page) as any).pathAuto).toBe(true);

  // 영상 소켓도 그 경로가 말하는 대로 열려야 한다 — 렌더러만 바꾸고 폰에는 H.264 를 계속 달라고 하면
  // 화면이 빈다. "한 벌"이라는 것이 이 뜻이라, 고른 뒤 실제로 열린 소켓 주소로 확인한다.
  // (그림까지는 못 본다: 가짜 폰은 `?codec=` 을 모르고 언제나 같은 H.264 클립을 보낸다.)
  const sockets: string[] = [];
  page.on('websocket', (ws) => sockets.push(ws.url()));

  await page.locator('#btn-quality').click();
  await Promise.all([
    page.waitForLoadState('load'),
    page.locator('#path-grid .tile[data-path="mjpeg"]').click(),
  ]);

  await page.waitForFunction(() => !!(window as any).__carcast, null, { timeout: 30_000 });
  const s = await stats(page) as any;
  expect(s.path).toBe('mjpeg');
  expect(s.renderer).toBe('mjpeg');
  expect(s.pathAuto).toBe(false);
  expect(await page.evaluate((k) => localStorage.getItem(k), PATH_KEY)).toBe('mjpeg');

  await expect.poll(() => sockets.filter((u) => u.includes('/ws/video')), { timeout: 15_000 })
    .toEqual([expect.stringContaining('/ws/video?codec=mjpeg')]);
});

test('남은 선택을 이 브라우저가 못 쓰면 조용히 자동으로 돌아간다', async ({ page }) => {
  // 하드웨어 경로를 골라 둔 채 평문으로 들어온 차. 그대로 세우면 `VideoDecoder` 가 없어 검은 화면이다.
  await page.goto('/');
  await page.evaluate((k) => localStorage.setItem(k, 'webcodecs'), PATH_KEY);
  await page.goto('/');
  await page.waitForFunction(() => !!(window as any).__carcast, null, { timeout: 30_000 });

  const s = await stats(page) as any;
  expect(s.path).toBe('h264');
  expect(s.pathAuto).toBe(true);
  expect(s.pathWhy).toContain('되돌림');
  // 그림은 나온다 — 그게 이 규칙이 있는 이유다.
  await page.waitForFunction(() => (window as any).__carcast.stats().framesDecoded > 0, null, { timeout: 30_000 });
});

// 주소로 지정하는 쪽은 반대다: 되는지 묻지 않고 그대로 간다. "이 브라우저에 정말 없는가"를 보려면
// 없는 길로도 가 봐야 하기 때문이다(그 답이 곧 report #67 이 답한 질문이었다).
test('?path= 는 되는지 묻지 않고 그대로 간다', async ({ page }) => {
  await page.goto('/?path=webcodecs');
  await page.waitForFunction(() => !!(window as any).__carcast, null, { timeout: 30_000 });
  const s = await stats(page) as any;
  expect(s.path).toBe('webcodecs');
  expect(s.pathAuto).toBe(false);
  expect(s.pathWhy).toContain('?path=');
  // 평문이라 디코더는 안 선다. 그것이 답이고, 화면은 그 이유를 들고 있어야 한다.
  expect((await stats(page)).lastError).not.toBe('');
});

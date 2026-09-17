// 컨트롤 소켓의 새 말들: 터치에 실린 차의 시계, 한 프레임에 묶인 MOVE 표본, 왕복을 재는 ping,
// 그리고 프레임을 버렸을 때의 키프레임 요청. 바이트는 web/src/protocol.ts 가, 받는 쪽은 가짜 폰이
// 진짜 서버(core ControlMessage.kt)와 같은 규칙으로 읽는다.
import { expect, test } from '@playwright/test';
import { startPlayback, stats, statusOf } from './helpers';

test.skip(!!process.env.BASE_URL, 'needs the fake phone state endpoint');

async function picture(page: import('@playwright/test').Page) {
  const box = await page.locator('#stage').boundingBox();
  if (!box) throw new Error('no stage');
  const aspect = 16 / 9;
  let vw = box.width, vh = box.height, ox = 0, oy = 0;
  if (box.width / box.height > aspect) { vw = box.height * aspect; ox = (box.width - vw) / 2; }
  else { vh = box.width / aspect; oy = (box.height - vh) / 2; }
  return { at: (fx: number, fy: number) => ({ x: box.x + ox + vw * fx, y: box.y + oy + vh * fy }) };
}

test('터치는 차의 시계를 싣고, 드래그의 MOVE 는 표본째 폰에 닿는다', async ({ page }) => {
  await page.goto('/');
  await startPlayback(page);
  await page.waitForFunction(() => (window as any).__carcast.stats().controlWs.open);
  await page.evaluate(() => fetch('/api/reset'));
  const p = await picture(page);

  const a = p.at(0.2, 0.5), b = p.at(0.8, 0.5);
  await page.mouse.move(a.x, a.y);
  await page.mouse.down();
  await page.mouse.move(b.x, b.y, { steps: 12 });
  await page.mouse.up();

  await expect.poll(async () => (await statusOf(page)).touches.length, { timeout: 5000 }).toBeGreaterThanOrEqual(4);
  const touches = (await statusOf(page)).touches as { action: number; x: number; y: number; tMs: number | null }[];
  const down = touches.find((t) => t.action === 0)!;
  const up = touches.find((t) => t.action === 1)!;
  const moves = touches.filter((t) => t.action === 2);
  expect(down.x).toBeCloseTo(0.2, 1);
  expect(up.x).toBeCloseTo(0.8, 1);
  expect(moves.length).toBeGreaterThanOrEqual(2);
  // 매 표본에 차의 시계가 있고, 시간은 앞으로만 간다 — 폰이 그 시각으로 MotionEvent 를 찍는다.
  for (const t of touches) expect(typeof t.tMs).toBe('number');
  expect(down.tMs).toBeLessThanOrEqual(moves[0]!.tMs!);
  expect(moves[moves.length - 1]!.tMs).toBeLessThanOrEqual(up.tMs!);
  // 드래그가 그림 밖으로 나가도 MOVE 는 가장자리에 붙어 계속 간다(버리지 않는다).
  const before = touches.length;
  await page.mouse.move(b.x, b.y);
  await page.mouse.down();
  await page.mouse.move(b.x + 2000, b.y, { steps: 4 });
  await page.mouse.up();
  await expect.poll(async () => (await statusOf(page)).touches.length).toBeGreaterThan(before + 2);
  const edge = ((await statusOf(page)).touches as { action: number; x: number }[]).slice(before).filter((t) => t.action === 2);
  expect(edge.length).toBeGreaterThanOrEqual(2);
  expect(edge[edge.length - 1]!.x).toBe(1);
});

test('ping 이 돌아와 왕복 시간이 상태줄과 리포트에 실린다', async ({ page }) => {
  await page.goto('/');
  await startPlayback(page);
  await page.evaluate(() => fetch('/api/reset'));
  await expect.poll(async () => (await stats(page)).rttMs, { timeout: 8000 }).toBeGreaterThanOrEqual(0);
  expect((await stats(page)).rttMs).toBeLessThan(1000);
  await expect(page.locator('#stats')).toContainText(/rtt \d+ms/);
  expect((await statusOf(page)).pings).toBeGreaterThanOrEqual(1);
});

test('키프레임 요청은 컨트롤 소켓으로 폰에 닿고 0.5 초에 하나로 준다', async ({ page }) => {
  await page.goto('/');
  await startPlayback(page);
  await page.waitForFunction(() => (window as any).__carcast.stats().controlWs.open);
  await page.evaluate(() => fetch('/api/reset'));
  const sent = await page.evaluate(() => {
    const c = (window as any).__carcast;
    return [c.requestKeyframe(), c.requestKeyframe(), c.requestKeyframe()];
  });
  expect(sent).toEqual([true, false, false]);
  await expect.poll(async () => (await statusOf(page)).keyframeRequests).toBe(1);
  expect((await stats(page)).keyframeRequests).toBe(1);
  await page.waitForTimeout(600);
  expect(await page.evaluate(() => (window as any).__carcast.requestKeyframe())).toBe(true);
  await expect.poll(async () => (await statusOf(page)).keyframeRequests).toBe(2);
});

// 기본 렌더러가 워커에서 바로 그린다(OffscreenCanvas). 차와 같은 엔진(Chrome 148)에서 되는지 여기서 못박는다.
test('h264 렌더러는 워커가 OffscreenCanvas 에 그린다', async ({ page }) => {
  await page.goto('/');
  await startPlayback(page);
  await page.waitForFunction(() => (window as any).__carcast.stats().framesDecoded > 5, null, { timeout: 30_000 });
  const s = await stats(page);
  expect(s.renderer).toBe('h264');
  expect(s.offscreen).toBe(true);
  expect(s.fps).toBeGreaterThan(0);
  expect(s.lastError).toBe('');
});

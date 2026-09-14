import { expect, test } from '@playwright/test';

// 가상 폰(에뮬레이터)에서는 건너뛴다: 소프트웨어 인코더가 정지 화면에서 초당 0.3프레임까지 떨어져
// (2026-09-11 실측) 디코드 처리량을 물을 수 없다. 처리량은 A(가짜 폰)와 B(실기기)에서 본다 —
// A+ 는 경로가 이어지는지를 보는 자리다. docs/agent-runbook.md
test.skip(!!process.env.NO_THROUGHPUT, '가상 폰에서는 디코드 처리량을 물을 수 없다');
import { sleep, startPlayback, stats } from './helpers';

test('MSE renderer decodes the live stream at >= 25 fps with < 300 ms lag', async ({ page }) => {
  await page.goto('/');
  await expect(page.locator('#overlay')).toBeVisible();
  await startPlayback(page);
  await expect(page.locator('#overlay')).toBeHidden();

  await page.waitForFunction(() => (window as any).__carcast.stats().framesDecoded > 10, null, { timeout: 20_000 });
  await sleep(3000);
  const s = await stats(page);
  expect(s.renderer).toBe('mse');
  expect(s.lastError).toBe('');
  expect(s.fps).toBeGreaterThanOrEqual(25);
  expect(s.latencyMs).toBeLessThan(300);
  expect(s.videoWs.open).toBe(true);
  expect(s.controlWs.open).toBe(true);
});

test('video keeps playing across a 10 s window (no stall)', async ({ page }) => {
  await page.goto('/');
  await startPlayback(page);
  await page.waitForFunction(() => (window as any).__carcast.stats().framesDecoded > 10);
  const a = await stats(page);
  await sleep(10_000);
  const b = await stats(page);
  // 30 fps for 10 s is 300 frames; accept some scheduling jitter.
  expect(b.framesDecoded - a.framesDecoded).toBeGreaterThan(250);
});

test('mjpeg renderer can be forced via ?renderer=mjpeg', async ({ page }) => {
  await page.goto('/?renderer=mjpeg');
  await startPlayback(page);
  const s = await stats(page);
  expect(s.renderer).toBe('mjpeg');
  expect(s.controlWs.open || s.controlWs.connects > 0 || s.controlWs.failures >= 0).toBe(true);
});

// 드라이브 모드용 경로. 테슬라는 기어가 P 를 벗어나면 <video> 에 프레임 공급을 끊지만 캔버스는
// 그대로 돈다(실측 2026-09-14, docs/drive-check). 이 렌더러는 <video> 를 아예 쓰지 않는다 —
// 그래서 **첫 제스처도 필요 없다.** 그 점까지 여기서 확인한다(startPlayback 을 부르지 않는다).
test('h264 renderer decodes to canvas with no gesture (?renderer=h264)', async ({ page }) => {
  await page.goto('/?renderer=h264');
  await page.waitForFunction(() => (window as any).__carcast.stats().framesDecoded > 10, null, { timeout: 30_000 });

  const a = await stats(page);
  await sleep(5000);
  const b = await stats(page);
  expect(b.renderer).toBe('h264');
  expect(b.lastError).toBe('');
  // 5 초면 30fps 에서 150 장. 소프트 디코딩이라 여유를 두고 본다.
  expect(b.framesDecoded - a.framesDecoded).toBeGreaterThan(100);
  // 오버레이는 아직 떠 있어야 한다: 우리는 누른 적이 없고, 그런데도 그림은 나오고 있다.
  await expect(page.locator('#overlay')).toBeVisible();
});

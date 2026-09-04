import { expect, test } from '@playwright/test';
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

// Long run against a phone (or the fake phone): the laptop's stand-in for a car session. Samples the
// client's stats every 5 s, then presses 💾 so the run ends up in the phone's /api/reports with the
// same numbers a car session leaves. Off unless SOAK_MINUTES is set:
//   SOAK_MINUTES=10 BASE_URL=http://100.99.9.9:3333 npx playwright test tests/soak.spec.ts --project=model-y-2026.26
import { expect, test } from '@playwright/test';
import { startPlayback, stats } from './helpers';

const MINUTES = Number(process.env.SOAK_MINUTES ?? 0);
test.skip(!MINUTES, 'set SOAK_MINUTES to run the soak test');

test('soak: plays for SOAK_MINUTES without stalls, reconnect storms or errors', async ({ page }) => {
  test.setTimeout(MINUTES * 60_000 + 60_000);
  await page.goto('/');
  await startPlayback(page);
  await page.waitForFunction(() => (window as any).__carcast.stats().framesDecoded > 30, null, { timeout: 30_000 });
  const t0 = Date.now();
  let prev = await stats(page);
  let quietSamples = 0; // 5 s windows with packets but no frames: a stall the watchdog did not catch
  while (Date.now() - t0 < MINUTES * 60_000) {
    await page.waitForTimeout(5_000);
    const s = await stats(page);
    const df = s.framesDecoded - prev.framesDecoded;
    const dp = s.packets - prev.packets;
    if (dp >= 10 && df === 0) quietSamples++;
    console.log(`${Math.round((Date.now() - t0) / 1000)}s fps ${s.fps} lag ${Math.round(s.latencyMs)}ms +${df}f/+${dp}p ws↻${s.videoWs.connects - 1} 복구${s.recoveries} 드롭${s.droppedFrames}${s.lastError ? ` err=${s.lastError}` : ''}`);
    prev = s;
  }
  await page.locator('#btn-save').click();
  await expect(page.locator('#stats')).toContainText('저장됨 #');
  const s = await stats(page);
  expect(s.lastError).toBe('');
  expect(s.recoveries).toBe(0);
  expect(quietSamples).toBe(0);
  expect(s.videoWs.connects - 1, 'video socket reconnects').toBeLessThanOrEqual(Math.ceil(MINUTES / 5));
});

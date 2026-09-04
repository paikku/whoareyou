import { expect, test } from '@playwright/test';

test('diag page reports environment, API support, WS success and decode', async ({ page }) => {
  await page.goto('/diag');
  await expect(page.locator('#env')).toContainText('Tesla/');
  // The origin is plain http, so the page must not be a secure context (like in the car).
  const secure = await page.evaluate(() => isSecureContext);
  expect(secure).toBe(false);
  const rows = await page.locator('#api tr').allTextContents();
  expect(rows.find((r) => r.startsWith('MediaSource'))).toContain('O');
  expect(rows.find((r) => r.includes('avc1.42E01E'))).toContain('O');

  await page.waitForFunction(() => (window as any).__diag?.done === true, null, { timeout: 60_000 });
  const diag = await page.evaluate(() => (window as any).__diag);
  expect(diag.ws.ok).toBeGreaterThanOrEqual(18);
  expect(diag.video.frames).toBeGreaterThan(30);
  expect(diag.video.error).toBe('');
});

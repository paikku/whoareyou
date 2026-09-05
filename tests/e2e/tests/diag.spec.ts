import { expect, test } from '@playwright/test';

test('diag page reports environment, API support, WS success and decode', async ({ page }) => {
  await page.goto('/diag');
  await expect(page.locator('#env')).toContainText('Tesla/');
  // The origin is plain http, so the page must not be a secure context (like in the car).
  // Loopback origins are always "potentially trustworthy", so skip this when pointed at 127.0.0.1.
  const secure = await page.evaluate(() => isSecureContext);
  if (!/^https?:\/\/(127\.0\.0\.1|localhost)[:/]/.test(page.url())) expect(secure).toBe(false);
  const rows = await page.locator('#api tr').allTextContents();
  expect(rows.find((r) => r.startsWith('MediaSource'))).toContain('O');
  expect(rows.find((r) => r.includes('avc1.42E01E'))).toContain('O');

  await page.waitForFunction(() => (window as any).__diag?.done === true, null, { timeout: 60_000 });
  const diag = await page.evaluate(() => (window as any).__diag);
  expect(diag.ws.ok).toBeGreaterThanOrEqual(18);
  expect(diag.video.frames).toBeGreaterThan(30);
  expect(diag.video.error).toBe('');

  // The private-IP control group: every address the phone reports (other than the one we came in on)
  // must be unreachable, exactly as in the car. Locally the config maps RFC1918 to NXDOMAIN; against a
  // real phone (BASE_URL) the laptop CAN reach the hotspot address — only the car cannot — so just log.
  const addresses = diag.addresses as Record<string, string>;
  expect(Object.keys(addresses).length).toBeGreaterThan(0);
  if (process.env.BASE_URL) console.log('private-address probe from this host:', addresses);
  else for (const [addr, state] of Object.entries(addresses)) expect(state, addr).not.toBe('reachable');

  // The page pushed everything it measured to the phone, and the phone lists it back.
  expect(diag.report.ok, JSON.stringify(diag.report)).toBe(true);
  await expect(page.locator('#report-result')).toContainText('저장됨');
  await expect(page.locator('#summary')).toContainText('Tesla 2026.26');
  const reports = await page.evaluate(async () => (await fetch('/api/reports')).json());
  const mine = reports.find((r: any) => r.id === diag.report.id);
  expect(mine).toBeTruthy();
  expect(mine.report.env.UA).toContain('Tesla/');
  expect(mine.report.ws.ok).toBe(diag.ws.ok);
  expect(mine.summary).toContain('ws ');
  const status = await page.evaluate(async () => (await fetch('/api/status')).json());
  expect(status.reports).toBeGreaterThanOrEqual(1);
  expect(status.lastReport.id).toBe(mine.id);
});

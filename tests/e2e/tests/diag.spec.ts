import { expect, test } from '@playwright/test';

// 가상 폰(에뮬레이터)에서는 건너뛴다: 소프트웨어 인코더가 정지 화면에서 초당 0.3프레임까지 떨어져
// (2026-09-11 실측) 디코드 처리량을 물을 수 없다. 처리량은 A(가짜 폰)와 B(실기기)에서 본다 —
// A+ 는 경로가 이어지는지를 보는 자리다. docs/agent-runbook.md
test.skip(!!process.env.NO_THROUGHPUT, '가상 폰에서는 디코드 처리량을 물을 수 없다');

test('diag page reports environment, API support, WS success and decode', async ({ page }) => {
  await page.goto('/diag');
  const ua = await page.evaluate(() => navigator.userAgent);
  const teslaToken = ua.includes('Tesla/'); // the 2026.26 car sends none; the older-style profile does
  await expect(page.locator('#env')).toContainText(teslaToken ? 'Tesla/' : 'X11; Linux x86_64');
  // The origin is plain http, so the page must not be a secure context (like in the car).
  // Loopback origins are always "potentially trustworthy", so skip this when pointed at 127.0.0.1.
  const secure = await page.evaluate(() => isSecureContext);
  if (!/^https?:\/\/(127\.0\.0\.1|localhost)[:/]/.test(page.url())) expect(secure).toBe(false);
  const rows = await page.locator('#api tr').allTextContents();
  expect(rows.find((r) => r.startsWith('MediaSource'))).toContain('O');
  expect(rows.find((r) => r.includes('avc1.42E01E'))).toContain('O');

  await page.waitForFunction(() => (window as any).__diag?.done === true, null, { timeout: 120_000 });
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
  // WebRTC 가능성 조사: 같은 엔진(Chrome 148)이라 여기서는 루프백이 서야 한다. 차에서 X 가 나오면 그것이 답이다.
  // 코덱·디코더 이름은 이 PC 의 사정이라 단언하지 않고 기록만 한다.
  expect(diag.webrtc, 'webrtc 프로브 결과가 없다').toBeTruthy();
  expect(diag.webrtc.present).toBe(true);
  expect(diag.webrtc.loopback, JSON.stringify(diag.webrtc)).toBe('ok');
  expect(diag.webrtc.dataChannel).toBe(true);
  expect(diag.webrtc.unreliableChannel).toBe(true);
  console.log('webrtc here:', diag.webrtc);
  // 링크 프로브: 가짜 폰은 loopback 이라 숫자는 뜻이 없고, 다섯 크기가 전부 재졌는지만 본다.
  const link = diag.link as Record<string, { ms: number; mbps: number } | string>;
  for (const k of ['64KB', '256KB', '600KB', '600KB#2', '2MB']) expect(typeof link[k], `${k}: ${JSON.stringify(link[k])}`).toBe('object');
  console.log('link here:', link);
  await expect(page.locator('#report-result')).toContainText('저장됨');
  await expect(page.locator('#summary')).toContainText(teslaToken ? 'Tesla 2026.26' : 'X11 Linux x86_64 Chrome/148 (no Tesla/ token)');
  const reports = await page.evaluate(async () => (await fetch('/api/reports')).json());
  const mine = reports.find((r: any) => r.id === diag.report.id);
  expect(mine).toBeTruthy();
  expect(mine.report.env.UA).toBe(ua);
  expect(mine.report.ws.ok).toBe(diag.ws.ok);
  expect(mine.summary).toContain('ws ');
  const status = await page.evaluate(async () => (await fetch('/api/status')).json());
  expect(status.reports).toBeGreaterThanOrEqual(1);
  expect(status.lastReport.id).toBe(mine.id);
});

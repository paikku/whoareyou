// The 💾 button on the main page posts the session's numbers and event log to the phone, the same
// way the diag page does — the only way to get "it stuttered" out of the car with numbers attached.
import { expect, test } from '@playwright/test';

// 가상 폰(에뮬레이터)에서는 건너뛴다: 소프트웨어 인코더가 정지 화면에서 초당 0.3프레임까지 떨어져
// (2026-09-11 실측) 디코드 처리량을 물을 수 없다. 처리량은 A(가짜 폰)와 B(실기기)에서 본다 —
// A+ 는 경로가 이어지는지를 보는 자리다. docs/agent-runbook.md
test.skip(!!process.env.NO_THROUGHPUT, '가상 폰에서는 디코드 처리량을 물을 수 없다');
import { startPlayback } from './helpers';

test('main page saves a session report with stats and events', async ({ page }) => {
  await page.goto('/');
  await startPlayback(page);
  await page.waitForFunction(() => (window as any).__carcast.stats().framesDecoded > 30, null, { timeout: 30_000 });
  await page.locator('#btn-save').click();
  await expect(page.locator('#stats')).toContainText('저장됨 #');
  const reports = await page.evaluate(async () => (await fetch('/api/reports')).json());
  const mine = reports.find((r: any) => r.report.kind === 'session');
  expect(mine, JSON.stringify(reports.map((r: any) => r.summary))).toBeTruthy();
  expect(mine.summary).toMatch(/^session mse \d+fps lag \d+ms frames \d+ packets \d+ ws↻0\/0 복구0 드롭\d+$/);
  expect(mine.report.events.join('\n')).toContain('video ws open #1');
  expect(mine.report.stats.recoveries).toBe(0);
  // The stats line shows only what is abnormal: a clean run has no reconnects or recoveries on it.
  await expect(page.locator('#stats')).not.toContainText('복구');
});

// The diag page must reach "저장됨" even when the video path is dead: in the car (Model Y 2026.26)
// the phone accepted /ws/video but no frame was ever presented, video.play() never settled, and the
// page sat on "측정 중…" forever with nothing saved. Here the fake phone sends the init segment and
// then goes silent; the page has to time out, say so in the report, and still post it.
import { expect, test } from '@playwright/test';
import { spawn, type ChildProcess } from 'node:child_process';

test.skip(!!process.env.BASE_URL, 'fault injection needs the fake phone');

const PORT = 3335;
let proc: ChildProcess;

test.beforeAll(async () => {
  proc = spawn('node', ['../../tools/fake-phone/server.mjs', '--port', String(PORT), '--host', '127.0.0.1', '--video-silent'], { stdio: 'inherit' });
  await new Promise<void>((resolve, reject) => {
    const started = Date.now();
    const tryConnect = async () => {
      try { await fetch(`http://127.0.0.1:${PORT}/api/status`); resolve(); }
      catch { if (Date.now() - started > 15_000) reject(new Error('fake phone did not start')); else setTimeout(tryConnect, 250); }
    };
    tryConnect();
  });
});
test.afterAll(() => { proc?.kill(); });

test('diag still saves a report when no video frame ever arrives', async ({ page }) => {
  await page.goto(`http://100.99.9.9:${PORT}/diag`);
  // WS 20 handshakes (fast) + video 5s open cap / 3s play cap / 5s measure + address probes: well under 40s.
  await page.waitForFunction(() => (window as any).__diag?.done === true, null, { timeout: 40_000 });
  const diag = await page.evaluate(() => (window as any).__diag);
  expect(diag.ws.ok).toBeGreaterThanOrEqual(18);
  expect(diag.video.frames).toBe(0);
  expect(diag.video.error).toContain('play() never started');
  expect(diag.video.state).toContain('paused=');
  expect(diag.report.ok, JSON.stringify(diag.report)).toBe(true);
  await expect(page.locator('#report-result')).toContainText('저장됨');
  await expect(page.locator('#summary')).toContainText('err=');
  await expect(page.locator('#log')).toContainText('play() still pending');
});

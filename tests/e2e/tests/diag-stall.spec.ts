// The diag page must reach "저장됨" even when the video path is dead: in the car (Model Y 2026.26)
// the phone accepted /ws/video but no frame was ever presented, and the page sat on "측정 중…" forever
// with nothing saved (that was the MSE probe; since 2026-09-18 the probe runs the same canvas path the
// main page uses, and the contract is unchanged). Here the fake phone sends the init segment and then
// goes silent; the page has to time out, say so in the report, and still post it.
import { expect, test } from '@playwright/test';
import { spawn, type ChildProcess } from 'node:child_process';

test.skip(!!process.env.BASE_URL, 'fault injection needs the fake phone');

const PORTS = { silent: 3335, freeze: 3336 };
const procs: ChildProcess[] = [];

async function fakePhone(port: number, flag: string): Promise<void> {
  procs.push(spawn('node', ['../../tools/fake-phone/server.mjs', '--port', String(port), '--host', '127.0.0.1', flag], { stdio: 'inherit' }));
  await new Promise<void>((resolve, reject) => {
    const started = Date.now();
    const tryConnect = async () => {
      try { await fetch(`http://127.0.0.1:${port}/api/status`); resolve(); }
      catch { if (Date.now() - started > 15_000) reject(new Error('fake phone did not start')); else setTimeout(tryConnect, 250); }
    };
    tryConnect();
  });
}

test.beforeAll(async () => {
  await fakePhone(PORTS.silent, '--video-silent');
  await fakePhone(PORTS.freeze, '--video-freeze');
});
test.afterAll(() => { for (const p of procs) p.kill(); });

test('diag still saves a report when no video frame ever arrives', async ({ page }) => {
  await page.goto(`http://100.99.9.9:${PORTS.silent}/diag`);
  // WS 20 handshakes (fast) + video 5s open cap / 3s play cap / 5s measure + address probes: well under 40s.
  await page.waitForFunction(() => (window as any).__diag?.done === true, null, { timeout: 40_000 });
  const diag = await page.evaluate(() => (window as any).__diag);
  expect(diag.ws.ok).toBeGreaterThanOrEqual(18);
  expect(diag.video.frames).toBe(0);
  expect(diag.video.error).toContain('no frames decoded');
  // 무엇으로 쟀는지가 리포트에 남는다 — 평문이라 소프트 디코더다.
  expect(diag.video.path).toBe('h264');
  expect(diag.video.state).toContain('path=h264');
  expect(diag.report.ok, JSON.stringify(diag.report)).toBe(true);
  await expect(page.locator('#report-result')).toContainText('저장됨');
  await expect(page.locator('#summary')).toContainText('video h264 0f');
  await expect(page.locator('#summary')).toContainText('err=');
  await expect(page.locator('#log')).toContainText('video probe packets=1');
});

// Reports #7 and #8 from the car: the phone's encoder had gone idle, so a new client got the init
// segment plus the cached last keyframe (stamped ~50 h into the stream) and nothing else. The one
// frame we do have must be shown (a canvas renderer draws it as it arrives; the old MSE probe had to
// seek to it), and the report must say the stream then froze.
test('a lone cached keyframe from an idle phone is displayed and reported as a stall', async ({ page }) => {
  await page.goto(`http://100.99.9.9:${PORTS.freeze}/diag`);
  await page.waitForFunction(() => (window as any).__diag?.done === true, null, { timeout: 40_000 });
  const diag = await page.evaluate(() => (window as any).__diag);
  expect(diag.video.packets).toBe(2);
  expect(diag.video.frames).toBeGreaterThanOrEqual(1);
  expect(diag.video.error).toContain('stalled');
  expect(diag.video.state).toContain('path=h264');
  expect(diag.video.packetTimes).toMatch(/^\d+,\d+ms$/);
  expect(diag.report.ok, JSON.stringify(diag.report)).toBe(true);
});

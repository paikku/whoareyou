// A phone screen that goes still for a while (paused video, the driver's power-button toggle) sends nothing
// for many seconds and then resumes mid-GOP with P-frames; the encoder's keyframe interval counts frames, so
// the next keyframe can be a long way off. The renderer used to trim its buffer past the last keyframe during
// the silence, after which every resumed frame was undecodable and the watchdog reconnected ("decode stall …
// buf=[]", laptop report #24). The fake phone reproduces the silence with --pause-every/--pause-ms.
import { expect, test } from '@playwright/test';
import { spawn, type ChildProcess } from 'node:child_process';
import { startPlayback, stats } from './helpers';

test.skip(!!process.env.BASE_URL, 'fault injection needs the fake phone');

const PORT = 3338;
const PAUSE_EVERY_S = 22; // with --pts-stretch 4 keyframes fall on multiples of 4 s; resuming at 22 s lands mid-GOP (P-frames until 24 s)
const PAUSE_MS = 12_000; // longer than the renderer keeps (8 s) plus its trim period
let proc: ChildProcess;

test.beforeAll(async () => {
  proc = spawn('node', ['../../tools/fake-phone/server.mjs', '--port', String(PORT), '--host', '127.0.0.1',
    '--pts-stretch', '4', '--pause-every', String(PAUSE_EVERY_S), '--pause-ms', String(PAUSE_MS)], { stdio: 'inherit' });
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

test('frames that resume after a long still period are shown without a reconnect', async ({ page }) => {
  test.setTimeout(120_000);
  await page.goto(`http://100.99.9.9:${PORT}/`);
  await startPlayback(page);
  await page.waitForFunction(() => (window as any).__carcast.stats().framesDecoded > 5, null, { timeout: 30_000 });
  // Wait for a silence to start (no packets for 3 s), then for it to end (packets again).
  await page.waitForFunction(() => (window as any).__carcast.stats().idleMs > 3_000, null, { timeout: 40_000 });
  const during = await stats(page);
  await page.waitForFunction(() => (window as any).__carcast.stats().idleMs < 1_000, null, { timeout: 20_000 });
  // The resumed frames are P-frames (the clip's next keyframe is up to 2 s away); they must be shown at once,
  // which needs the last GOP still in the buffer. Without it nothing moves until that keyframe and the
  // watchdog would reconnect at 2 s.
  await page.waitForTimeout(1_500);
  const soon = await stats(page);
  const dbg = await page.evaluate(() => (window as any).__carcast.debug());
  expect(soon.framesDecoded - during.framesDecoded, `frames shown 1.5 s after the silence (${dbg})`).toBeGreaterThanOrEqual(5);
  await page.waitForTimeout(1_500);
  const after = await stats(page);
  expect(after.framesDecoded - during.framesDecoded, 'frames shown 3 s after the silence').toBeGreaterThanOrEqual(15);
  expect(after.recoveries).toBe(0);
  expect(after.videoWs.connects).toBe(1);
});

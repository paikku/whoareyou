// Two things the car taught us on 2026-09-10 (session reports #10-#12):
//  1. On a static phone screen frames arrive 100-150 ms apart while every fragment is stamped 33 ms,
//     so the MSE timeline is full of gaps; in 'segments' mode Chrome stopped at each one
//     ("0 fps, lag 8 ms, packets still arriving"). The fake phone reproduces that with --pts-stretch.
//  2. Restarting the video socket must not leak the old one: each recovery used to add a stream.
import { expect, test } from '@playwright/test';
import { spawn, type ChildProcess } from 'node:child_process';
import { startPlayback, stats, statusOf } from './helpers';

test.skip(!!process.env.BASE_URL, 'fault injection needs the fake phone');

const PORT = 3337;
let proc: ChildProcess;

test.beforeAll(async () => {
  proc = spawn('node', ['../../tools/fake-phone/server.mjs', '--port', String(PORT), '--host', '127.0.0.1', '--pts-stretch', '4'], { stdio: 'inherit' });
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

test('sparse frames (33 ms fragments 133 ms apart) play without stalling', async ({ page }) => {
  await page.goto(`http://100.99.9.9:${PORT}/`);
  await startPlayback(page);
  // Stretching pts also stretches the clip's keyframe spacing, so the first picture can take a few
  // seconds; measure from there. 7.5 fps for 6 s ≈ 45 frames, and every frame that arrived in that
  // window must have been presented rather than parked behind a gap.
  await page.waitForFunction(() => (window as any).__carcast.stats().framesDecoded > 0, null, { timeout: 15_000 });
  const before = await stats(page);
  await page.waitForTimeout(6_000);
  const s = await stats(page);
  const frames = s.framesDecoded - before.framesDecoded;
  const packets = s.packets - before.packets;
  expect(frames, `frames ${frames} of ${packets} packets`).toBeGreaterThanOrEqual(30);
  expect(frames).toBeGreaterThanOrEqual(packets * 0.8);
  expect(s.fps).toBeGreaterThanOrEqual(4);
  expect(s.latencyMs).toBeLessThan(300);
  expect(s.recoveries).toBe(0);
  expect(s.lastError).toBe('');
});

test('restarting the video socket leaves exactly one stream behind', async ({ page }) => {
  await page.goto(`http://100.99.9.9:${PORT}/`);
  await startPlayback(page);
  await page.waitForFunction(() => (window as any).__carcast.stats().framesDecoded > 5, null, { timeout: 15_000 });
  for (let i = 0; i < 3; i++) {
    await page.evaluate(() => (window as any).__carcast.restartVideo());
    await page.waitForTimeout(400);
  }
  await page.waitForTimeout(2_000);
  const s = await stats(page);
  expect(s.videoWs.connects).toBe(4);
  expect((await statusOf(page)).videoClients).toBe(1);
  // and it is still playing after the restarts
  const before = s.framesDecoded;
  await page.waitForTimeout(2_000);
  expect((await stats(page)).framesDecoded).toBeGreaterThan(before);
});

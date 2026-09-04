// Runs the client against a fake phone that rejects a third of all WebSocket handshakes, adds
// 150 ms of network delay and kills every socket every 5 s: the client must reach a playing
// state within 30 s and keep recovering after each cut (reconnect count and frames both grow).
import { expect, test } from '@playwright/test';
import { spawn, type ChildProcess } from 'node:child_process';
import { startPlayback, stats } from './helpers';

test.skip(!!process.env.BASE_URL, 'fault injection needs the fake phone');

const PORT = 3334;
let proc: ChildProcess;

test.beforeAll(async () => {
  proc = spawn('node', ['../../tools/fake-phone/server.mjs', '--port', String(PORT), '--host', '127.0.0.1', '--ws-reject', '0.34', '--delay-ms', '150', '--ws-drop-every', '5'], { stdio: 'inherit' });
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

test('client recovers from rejected handshakes and socket cuts', async ({ page }) => {
  await page.goto(`http://100.99.9.9:${PORT}/`);
  await startPlayback(page);
  await page.waitForFunction(() => (window as any).__carcast.stats().framesDecoded > 30, null, { timeout: 30_000 });
  // Live through at least two cuts and check that frames still arrive afterwards.
  const before = await stats(page);
  await page.waitForTimeout(15_000);
  const after = await stats(page);
  expect(after.lastError).toBe('');
  expect(after.videoWs.connects).toBeGreaterThan(before.videoWs.connects);
  expect(after.framesDecoded).toBeGreaterThan(before.framesDecoded + 60);
});

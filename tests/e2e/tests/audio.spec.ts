// M6: the phone's sound reaches the car as AAC on /ws/audio and plays in step with the video from
// its own <audio> element. What the car adds on top of this (speaker output, the autoplay policy
// for sound) is on the car-only list in testing-guide §C.
import { expect, test } from '@playwright/test';
import { spawn, type ChildProcess } from 'node:child_process';
import { sleep, startPlayback, stats, statusOf } from './helpers';

test('audio plays in step with the video', async ({ page }) => {
  await page.goto('/');
  await startPlayback(page);
  await page.waitForFunction(() => {
    const s = (window as any).__carcast.stats();
    return s.framesDecoded > 10 && s.audio && s.audio.frames > 20 && s.audio.playing;
  }, null, { timeout: 20_000 });
  // Settles within a few seconds (a seek lands ~180 ms late, then 1.05x closes the rest), then stays
  // there. The video's own live-edge jumps (CPU hiccups on this box) knock it off for a moment, so
  // judge the run by most samples and the last one, not the single worst.
  await page.waitForFunction(() => Math.abs((window as any).__carcast.stats().audio.syncMs) < 100, null, { timeout: 15_000 });
  const samples: number[] = [];
  for (let i = 0; i < 8; i++) {
    await sleep(500);
    samples.push(Math.abs((await stats(page)).audio!.syncMs));
  }
  const inStep = samples.filter((v) => v < 120).length;
  expect(inStep, `samples off the video: ${samples.join(' ')}`).toBeGreaterThanOrEqual(6);
  expect(samples[samples.length - 1], `ends ${samples.join(' ')}`).toBeLessThan(120);
  const s = await stats(page);
  expect(s.audio, 'audio player present').not.toBeNull();
  expect(s.audioWs!.open).toBe(true);
  expect(s.audio!.lastError).toBe('');
  expect(s.audio!.playing).toBe(true);
  expect(s.audio!.bufferedMs).toBeGreaterThan(0);
  expect(s.audio!.seeks, 'a seek to get onto the video, maybe one more after a video jump').toBeLessThanOrEqual(3);
  expect(s.audio!.recoveries).toBe(0);
  // and the audio element really advances (not just buffers)
  const t1 = await page.evaluate(() => (document.getElementById('audio') as HTMLAudioElement).currentTime);
  await sleep(1500);
  const t2 = await page.evaluate(() => (document.getElementById('audio') as HTMLAudioElement).currentTime);
  expect(t2 - t1).toBeGreaterThan(1);
  // the video keeps its budget (stream.spec.ts holds the fps bar): held ~180 ms back for the audio, still under 300 ms
  expect(s.latencyMs).toBeLessThan(300);
  expect(s.recoveries).toBe(0);
  if (!process.env.BASE_URL) expect((await statusOf(page)).audioClients).toBe(1);
});

test('🔊 toggles mute and is remembered', async ({ page }) => {
  await page.goto('/');
  await startPlayback(page);
  await page.waitForFunction(() => (window as any).__carcast.stats().audio?.playing, null, { timeout: 20_000 });
  expect((await stats(page)).audio!.muted).toBe(false);
  await page.locator('#btn-audio').click();
  expect((await stats(page)).audio!.muted).toBe(true);
  await expect(page.locator('#btn-audio')).toHaveText('🔇');
  await page.reload();
  expect((await stats(page)).audio!.muted).toBe(true);
  await page.locator('#btn-audio').click();
  expect((await stats(page)).audio!.muted).toBe(false);
});

test('?audio=off leaves only the video', async ({ page }) => {
  await page.goto('/?audio=off');
  await startPlayback(page);
  await page.waitForFunction(() => (window as any).__carcast.stats().framesDecoded > 10, null, { timeout: 20_000 });
  const s = await stats(page);
  expect(s.audio).toBeNull();
  expect(s.audioWs).toBeNull();
  await expect(page.locator('#btn-audio')).toBeHidden();
});

test('diag page probes audio', async ({ page }) => {
  await page.goto('/diag');
  await page.waitForFunction(() => (window as any).__diag?.done === true, null, { timeout: 90_000 });
  const diag = await page.evaluate(() => (window as any).__diag);
  expect(diag.audio.frames).toBeGreaterThan(20);
  expect(diag.audio.advancedMs).toBeGreaterThan(1000);
  expect(diag.audio.error).toBe('');
  expect(diag.audio.phone).toContain(process.env.BASE_URL ? '' : 'clip');
  await expect(page.locator('#summary')).toContainText('audio ');
  await expect(page.locator('#summary')).toContainText('plays');
});

test.describe('phone without audio', () => {
  test.skip(!!process.env.BASE_URL, 'fault injection needs the fake phone');
  const PORT = 3338;
  let proc: ChildProcess;

  test.beforeAll(async () => {
    proc = spawn('node', ['../../tools/fake-phone/server.mjs', '--port', String(PORT), '--host', '127.0.0.1', '--audio-silent'], { stdio: 'inherit' });
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

  test('video plays normally when the audio stream carries no frames', async ({ page }) => {
    await page.goto(`http://100.99.9.9:${PORT}/`);
    await startPlayback(page);
    await page.waitForFunction(() => (window as any).__carcast.stats().framesDecoded > 10, null, { timeout: 20_000 });
    const before = await stats(page);
    await sleep(4000);
    const s = await stats(page);
    // stream.spec.ts holds the fps bar; here it only has to keep playing (this box drops to ~24 fps at 1900x1040 with two fake phones up)
    expect(s.framesDecoded - before.framesDecoded, '30 fps for 4 s, minus jitter').toBeGreaterThan(85);
    expect(s.latencyMs).toBeLessThan(300);
    expect(s.recoveries).toBe(0);
    expect(s.audio!.packets).toBe(1);   // the init segment
    expect(s.audio!.frames).toBe(0);
    expect(s.audio!.recoveries).toBe(0);
    expect(s.audio!.lastError).toBe('');
    // the diag page names the phone as the reason
    await page.goto(`http://100.99.9.9:${PORT}/diag`);
    await page.waitForFunction(() => (window as any).__diag?.done === true, null, { timeout: 90_000 });
    const diag = await page.evaluate(() => (window as any).__diag);
    expect(diag.audio.frames).toBe(0);
    expect(diag.audio.error).toContain('no AAC frames');
    expect(diag.audio.phone).toContain('failed');
  });
});

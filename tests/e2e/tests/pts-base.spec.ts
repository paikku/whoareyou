// 서버가 한참 돌고 난 뒤 차가 접속하는 경우: 라이브 인코더는 자기 시계로 pts 를 찍으므로
// 타임라인이 0 이 아니라 한참 뒤에서 시작한다. 클립(pts≈0)으로는 절대 안 나오던 상황.
import { expect, test } from '@playwright/test';
import { spawn, type ChildProcess } from 'node:child_process';
import { startPlayback, stats } from './helpers';

test.skip(!!process.env.BASE_URL, 'needs the fake phone');
const PORT = 3341;
let proc: ChildProcess;
test.beforeAll(async () => {
  proc = spawn('node', ['../../tools/fake-phone/server.mjs', '--port', String(PORT), '--host', '127.0.0.1', '--pts-base', '131'], { stdio: 'inherit' });
  const started = Date.now();
  for (;;) {
    try { await fetch(`http://127.0.0.1:${PORT}/api/status`); break; }
    catch { if (Date.now() - started > 15_000) throw new Error('no fake phone'); await new Promise((r) => setTimeout(r, 250)); }
  }
});
test.afterAll(() => { proc?.kill(); });

test('타임라인이 131초에서 시작해도 재생된다', async ({ page }) => {
  await page.goto(`http://100.99.9.9:${PORT}/`);
  await startPlayback(page);
  // 묻는 것은 "재생이 이어지느냐"다. 순간 fps(지난 1 초 창)는 읽는 순간이 프레임 사이 어디냐에 따라 ±1 이
  // 흔들려서 30fps 클립이 부하 아래에서 정확히 20 을 찍고 `> 20` 에 걸리곤 했다(스위트 안에서 두 번).
  // 2 초 동안 늘어난 장수로 본다 — 30fps 면 60 장이고, 40 은 스케줄링 요동을 넉넉히 남긴 문턱이다.
  await page.waitForFunction(() => (window as any).__carcast.stats().framesDecoded > 50, null, { timeout: 20_000 });
  const a = await stats(page);
  await new Promise((r) => setTimeout(r, 2000));
  const b = await stats(page);
  expect(b.framesDecoded - a.framesDecoded).toBeGreaterThan(40);
  expect(b.latencyMs).toBeLessThan(1000);
});

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
  // fps 는 지난 1 초 동안 화면에 올라간 장수다. 21 장째에 바로 읽으면 창이 아직 차지 않아(그리기는 디코드보다
  // 한 vsync 뒤에 온다) 19 가 나온다 — 재생이 되느냐를 묻는 자리이니 창이 찬 뒤에 읽는다.
  await page.waitForFunction(() => (window as any).__carcast.stats().framesDecoded > 50, null, { timeout: 20_000 });
  const s = await stats(page);
  expect(s.fps).toBeGreaterThan(20);
  expect(s.latencyMs).toBeLessThan(1000);
});

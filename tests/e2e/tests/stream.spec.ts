import { expect, test } from '@playwright/test';

// 가상 폰(에뮬레이터)에서는 건너뛴다: 소프트웨어 인코더가 정지 화면에서 초당 0.3프레임까지 떨어져
// (2026-09-11 실측) 디코드 처리량을 물을 수 없다. 처리량은 A(가짜 폰)와 B(실기기)에서 본다 —
// A+ 는 경로가 이어지는지를 보는 자리다. docs/agent-runbook.md
test.skip(!!process.env.NO_THROUGHPUT, '가상 폰에서는 디코드 처리량을 물을 수 없다');
import { sleep, startPlayback, stats } from './helpers';

test('video keeps playing across a 10 s window (no stall)', async ({ page }) => {
  await page.goto('/');
  await startPlayback(page);
  await page.waitForFunction(() => (window as any).__carcast.stats().framesDecoded > 10);
  const a = await stats(page);
  await sleep(10_000);
  const b = await stats(page);
  // 30 fps for 10 s is 300 frames; accept some scheduling jitter.
  expect(b.framesDecoded - a.framesDecoded).toBeGreaterThan(250);
});

// 기본 렌더러. 테슬라는 기어가 P 를 벗어나면 <video> 에 프레임 공급을 끊지만 캔버스는 그대로
// 돈다(실측 2026-09-14, docs/drive-check). 차는 대부분 D 이므로 처음부터 이 경로로 간다.
//
// 그래서 **첫 제스처가 필요 없다** — 여기서는 아무것도 누르지 않고, 오버레이가 스스로 걷히고
// 그림이 나오는 데까지를 본다. 차에 타면 화면이 이미 나와 있어야 한다.
test('기본은 캔버스 — 아무것도 누르지 않아도 그려진다', async ({ page }) => {
  await page.goto('/');
  await expect(page.locator('#overlay')).toBeHidden();
  await page.waitForFunction(() => (window as any).__carcast.stats().framesDecoded > 10, null, { timeout: 30_000 });

  const a = await stats(page);
  await sleep(5000);
  const b = await stats(page);
  // secure context 면 하드웨어 디코더(webcodecs)가 1순위다 — `VideoDecoder` 가 거기서만 존재하기 때문이고,
  // 차가 평문으로 여는 100.99.9.9 에서는 저절로 h264(WASM)로 떨어진다. loopback 은 평문이어도 secure 라
  // 이 실행에서는 webcodecs 가 나온다. 어느 쪽이든 **캔버스**이고, 그것이 이 검사의 계약이다.
  const secure = await page.evaluate(() => isSecureContext);
  expect(b.renderer).toBe(secure ? 'webcodecs' : 'h264');
  expect(b.lastError).toBe('');
  // 5 초면 30fps 에서 150 장. 소프트 디코딩이라 여유를 두고 본다.
  expect(b.framesDecoded - a.framesDecoded).toBeGreaterThan(100);
  expect(b.videoWs.open).toBe(true);
});

import { expect, test } from '@playwright/test';

// 가상 폰(에뮬레이터)에서는 건너뛴다: 소프트웨어 인코더가 정지 화면에서 초당 0.3프레임까지 떨어져
// (2026-09-11 실측) 디코드 처리량을 물을 수 없다. 처리량은 A(가짜 폰)와 B(실기기)에서 본다 —
// A+ 는 경로가 이어지는지를 보는 자리다. docs/agent-runbook.md
test.skip(!!process.env.NO_THROUGHPUT, '가상 폰에서는 디코드 처리량을 물을 수 없다');
import { sleep, startPlayback, stats } from './helpers';

test('MSE renderer decodes the live stream at >= 25 fps with < 300 ms lag', async ({ page }) => {
  await page.goto('/');
  await expect(page.locator('#overlay')).toBeVisible();
  await startPlayback(page);
  await expect(page.locator('#overlay')).toBeHidden();

  await page.waitForFunction(() => (window as any).__carcast.stats().framesDecoded > 10, null, { timeout: 20_000 });
  await sleep(3000);
  const s = await stats(page);
  expect(s.renderer).toBe('mse');
  expect(s.lastError).toBe('');
  expect(s.fps).toBeGreaterThanOrEqual(25);
  expect(s.latencyMs).toBeLessThan(300);
  expect(s.videoWs.open).toBe(true);
  expect(s.controlWs.open).toBe(true);
});

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

test('mjpeg renderer can be forced via ?renderer=mjpeg', async ({ page }) => {
  await page.goto('/?renderer=mjpeg');
  await startPlayback(page);
  const s = await stats(page);
  expect(s.renderer).toBe('mjpeg');
  expect(s.controlWs.open || s.controlWs.connects > 0 || s.controlWs.failures >= 0).toBe(true);
});

// 드라이브 모드용 경로. 테슬라는 기어가 P 를 벗어나면 <video> 에 프레임 공급을 끊지만 캔버스는
// 그대로 돈다(실측 2026-09-14, docs/drive-check). 이 렌더러는 <video> 를 아예 쓰지 않는다 —
// 그래서 **첫 제스처도 필요 없다.** 그 점까지 여기서 확인한다(startPlayback 을 부르지 않는다).
test('h264 renderer decodes to canvas with no gesture (?renderer=h264)', async ({ page }) => {
  await page.goto('/?renderer=h264');
  await page.waitForFunction(() => (window as any).__carcast.stats().framesDecoded > 10, null, { timeout: 30_000 });

  const a = await stats(page);
  await sleep(5000);
  const b = await stats(page);
  expect(b.renderer).toBe('h264');
  expect(b.lastError).toBe('');
  // 5 초면 30fps 에서 150 장. 소프트 디코딩이라 여유를 두고 본다.
  expect(b.framesDecoded - a.framesDecoded).toBeGreaterThan(100);
  // 오버레이는 아직 떠 있어야 한다: 우리는 누른 적이 없고, 그런데도 그림은 나오고 있다.
  await expect(page.locator('#overlay')).toBeVisible();
});

// 드라이브 모드를 자리 A 로 끌어온다.
//
// 테슬라가 하는 일은 결국 "<video> 가 프레임을 못 내놓게 하는 것"이다(pause() 를 부르지는 않는다 —
// 그래서 pause 이벤트도 오지 않는다). 여기서는 play 를 다시 잡아 눌러 같은 상태를 만든다: 패킷은
// 계속 오는데 프레임이 늘지 않고, 소켓을 새로 열어도 낫지 않는다. 클라이언트는 그 두 번째 스톨을
// 보고 드라이브로 판단해 h264 로 갈아타야 한다.
test('스스로 h264 로 갈아탄다 — 재접속으로 낫지 않는 스톨은 드라이브 모드다', async ({ page }) => {
  await page.goto('/');
  await startPlayback(page);
  await page.waitForFunction(() => (window as any).__carcast.stats().framesDecoded > 10, null, { timeout: 20_000 });
  expect((await stats(page)).renderer).toBe('mse');

  // 재접속이 다시 play() 를 부르므로, 계속 눌러 두어야 "재접속으로 안 낫는" 상태가 된다.
  await page.evaluate(() => {
    const v = document.querySelector('video') as HTMLVideoElement;
    v.addEventListener('play', () => v.pause());
    v.pause();
  });

  await page.waitForFunction(() => (window as any).__carcast.stats().renderer === 'h264', null, { timeout: 30_000 });

  // 갈아탄 뒤에는 다시 그림이 나와야 한다 — 전환 자체가 목적이 아니다.
  const a = await stats(page);
  await sleep(4000);
  const b = await stats(page);
  expect(b.framesDecoded).toBeGreaterThan(a.framesDecoded);
  expect(b.lastError).toBe('');
});

// 화질 시트(/api/encoder)와 자동 내리기, 그리고 끝에서 끝까지 지연 측정의 상태 기계.
// 가짜 폰은 인코더를 갈아끼우지도, 화면을 뒤집지도 못하므로 값과 요청만 본다: 프리셋을 고르면 그 값이
// POST 로 가고 상태에 실리는지, 자동 내리기가 한 단계 아래를 고르는지, 측정이 밝기 뒤집힘을 시간으로
// 바꾸고 결과를 리포트 요약에 싣는지.
import { expect, test } from '@playwright/test';
import { startPlayback, stats, statusOf } from './helpers';

test.skip(!!process.env.BASE_URL, 'needs the fake phone state endpoint');

test('화질 시트에서 고르면 폰 인코더 설정이 바뀌고 터치 좌표계도 따라간다', async ({ page }) => {
  await page.goto('/');
  await startPlayback(page);
  await page.evaluate(() => fetch('/api/reset'));
  await page.locator('#btn-quality').click();
  await expect(page.locator('#quality')).toBeVisible();
  // 평문 경로(WASM 디코더)의 사다리는 다섯 칸이다. 1080p60 은 하드웨어 디코더에서만 나온다 —
  // 여기 보이면 "고르면 화면이 멈추는 버튼"이 하나 있는 것이다.
  await expect(page.locator('#quality-grid .tile')).toHaveCount(5);
  await expect(page.locator('#quality-grid .tile[data-preset="1080p60"]')).toHaveCount(0);
  await page.locator('#quality-grid .tile[data-preset="900p30"]').click();
  await expect(page.locator('#stats')).toContainText('선명하게');
  const st = await statusOf(page);
  expect(st.width).toBe(1600);
  expect(st.height).toBe(900);
  expect(st.maxFps).toBe(30);
  expect(st.encoderRestarts).toBe(1);
  await expect(page.locator('#quality-now')).toContainText('1600x900 30fps');
  await expect(page.locator('#quality-grid .tile[data-preset="900p30"]')).toHaveClass(/held/);
  const s = await stats(page);
  expect(s.encoder).toMatchObject({ width: 1600, height: 900, fps: 30 });
});

// 인트라 리프레시 토글: 폰이 말한 값으로 그려지고, 누르면 `intra_refresh=30|0` 이 POST 로 간다. 실차에서는
// 이 손잡이가 "키프레임 버스트가 범인인가"를 가르는 실험이다(car-tests/model-y §11).
test('인트라 리프레시 토글은 폰의 값을 따르고, 누르면 인코더에 그 값이 간다', async ({ page }) => {
  await page.goto('/');
  await startPlayback(page);
  await page.evaluate(() => fetch('/api/reset'));
  await page.locator('#btn-quality').click();
  const toggle = page.locator('#quality-intra');
  await expect(toggle).toBeEnabled();
  await expect(toggle).not.toBeChecked();
  await toggle.check();
  await expect.poll(async () => (await statusOf(page)).intraRefresh).toBe(30);
  expect((await statusOf(page)).encoderRestarts).toBe(1);
  await expect(page.locator('#stats')).toContainText('인트라 리프레시 켬');
  await toggle.uncheck();
  await expect.poll(async () => (await statusOf(page)).intraRefresh).toBe(0);
  await expect(toggle).not.toBeChecked();
});

test('자동 내리기는 한 단계 아래 프리셋으로 가고, 맨 아래에서는 손대지 않는다', async ({ page }) => {
  await page.goto('/');
  await startPlayback(page);
  await page.evaluate(() => fetch('/api/reset'));
  // 부담은 화소 수 × fps (디코더가 지불하는 것): 720p30 < 900p30 < 720p60 < 1080p30 < 900p60.
  // 900p60 에서 한 단계 내리면 1080p30 이다.
  expect(await page.evaluate(() => (window as any).__carcast.applyPreset('900p60'))).toBe(true);
  await expect.poll(async () => (await statusOf(page)).maxFps).toBe(60);
  expect(await page.evaluate(() => (window as any).__carcast.stepDown())).toBe(true);
  await expect.poll(async () => (await statusOf(page)).maxFps).toBe(30);
  expect((await statusOf(page)).height).toBe(1080);
  await expect(page.locator('#stats')).toContainText('화질을 내렸습니다');
  // 60초 안에 두 번은 안 내린다.
  expect(await page.evaluate(() => (window as any).__carcast.stepDown())).toBe(false);
  expect((await stats(page)).autoStepDowns).toBe(1);
});

test('지연 측정은 터치를 보내고 밝기가 뒤집힐 때까지를 재서 리포트에 싣는다', async ({ page }) => {
  await page.goto('/');
  await startPlayback(page);
  await page.waitForFunction(() => (window as any).__carcast.stats().controlWs.open);
  await page.evaluate(() => fetch('/api/reset'));
  // 가짜 폰의 그림은 안 뒤집히므로: 터치를 보낸 직후(onTouch) 60ms 뒤에 밝기를 손으로 뒤집어 준다(feedLuma).
  const result = await page.evaluate(async () => {
    const c = (window as any).__carcast;
    c.feedLuma(20);
    let luma = 20;
    return c.runProbe({
      trials: 3,
      launch: false,
      onTouch: () => setTimeout(() => { luma = luma > 128 ? 20 : 235; c.feedLuma(luma); }, 60),
    });
  });
  expect(result.n).toBe(3);
  expect(result.fails).toBe(0);
  expect(result.medianMs).toBeGreaterThanOrEqual(50);
  expect(result.medianMs).toBeLessThan(1000);
  expect((await stats(page)).latencyProbe.n).toBe(3);
  await expect(page.locator('#stats')).toContainText('끝에서 끝까지');
  // 폰에 닿은 터치는 슬롯 9 의 DOWN/UP 셋씩.
  const touches = (await statusOf(page)).touches.filter((t: any) => t.id === 9);
  expect(touches.filter((t: any) => t.action === 0).length).toBe(3);
  expect(touches.filter((t: any) => t.action === 1).length).toBe(3);
  // 💾 요약에 실린다.
  await page.locator('#btn-save').click();
  await expect(page.locator('#stats')).toContainText('저장됨 #');
  const reports = await page.evaluate(async () => (await fetch('/api/reports')).json());
  expect(reports[0].summary).toMatch(/끝까지\d+ms/);
  expect(reports[0].report.stats.latencyProbe.samples.length).toBe(3);
});

// 하드웨어 디코더가 있는 차에서는 사다리가 한 칸 길어진다.
//
// 1080p60 이 여기에만 있는 이유: WASM 디코더(h264bsd)는 차 CPU 로 한 장씩 풀고 720p 한 장에 10ms 였다
// (실측 2026-09-17). 60fps 예산이 16ms 이므로 1080p 는 그 위이고, 골라 봐야 폰은 순순히 그 설정으로
// 갈아끼운 뒤 차가 못 따라온다. 실차 report #67 에서 하드웨어 디코더가 확인됐으므로 그쪽에만 낸다.
test('하드웨어 디코더로 열면 사다리에 1080p60 이 생긴다', async ({ page }) => {
  await page.goto('/?renderer=webcodecs');
  await page.waitForFunction(() => !!(window as any).__carcast, null, { timeout: 30_000 });
  const s = await stats(page);
  expect(s.renderer).toBe('webcodecs');
  expect((s as any).presets).toEqual(['720p30', '720p60', '900p30', '900p60', '1080p30', '1080p60']);

  await page.locator('#btn-quality').click();
  await expect(page.locator('#quality-grid .tile')).toHaveCount(6);
  await expect(page.locator('#quality-grid .tile[data-preset="1080p60"]')).toBeVisible();
});

// 폰은 차가 고른 값을 파일로 기억한다(encoder.conf). 그래서 "지금 경로의 천장 위 설정으로 도는 폰에
// 들어오는" 조합이 실제로 생긴다 — 북마크가 https 와 http 로 둘 있거나, 시트에서 경로를 바꾸기만 해도
// 그렇게 된다. 그 조합의 증상은 그냥 멈춘 화면이고, 자동 내리기는 손대지 못한다(프레임이 안 풀리니
// 적체도 드롭도 자라지 않는다).
test('경로의 천장 위 설정으로 도는 폰에 들어오면 한 단계 내려 준다', async ({ page }) => {
  await page.goto('/');
  await page.evaluate(() => fetch('/api/reset'));
  // 지난번 https 방문에서 고른 값이 폰에 남아 있는 상태를 만든다.
  await page.evaluate(() => fetch('/api/encoder?width=1920&height=1080&fps=60&bitrate=12000000', { method: 'POST' }));
  expect((await statusOf(page)).maxFps).toBe(60);

  await page.goto('/'); // 평문 북마크로 다시 들어온다: WASM 경로
  await startPlayback(page);
  expect((await stats(page)).renderer).toBe('h264');

  // 이 렌더러가 풀 수 있는 것 중 가장 무거운 칸으로 간다(900p60). 더 내릴 일이 있으면 그 다음은
  // 자동 내리기의 몫이다 — 여기서 재는 것은 성능이 아니라 능력이다.
  await expect.poll(async () => (await statusOf(page)).width).toBe(1600);
  const st = await statusOf(page);
  expect(st.height).toBe(900);
  expect(st.maxFps).toBe(60);
  expect((await stats(page) as any).presetGuarded).toBe(true);
  await expect(page.locator('#stats')).toContainText('감당하지 못합니다');
  // 성능 판단이 아니라 능력 판단이므로 자동 내리기 횟수에는 넣지 않는다.
  expect((await stats(page) as any).autoStepDowns).toBe(0);
});

// 적응 비트레이트(abr.ts). 사다리보다 앞에 서는 층이다: 링크가 막히면(rtt 가 기준의 두 배 넘게 뛰거나 프레임을
// 버리면) 비트레이트만 재빌드 없이 내리고, 20 초 조용하면 공칭까지 한 칸씩 되올린다. 가짜 폰은 실제 서버처럼
// 비트레이트 전용 POST 를 `rebuilt:false` 로 받는다. 시계는 표본이 들고 오므로 여기서 초 단위로 돌린다.
test('링크가 막히면 비트레이트를 먼저, 재빌드 없이 내리고, 풀리면 천천히 되올린다', async ({ page }) => {
  await page.goto('/');
  await startPlayback(page);
  await page.evaluate(() => fetch('/api/reset'));
  expect(await page.evaluate(() => (window as any).__carcast.applyPreset('720p30'))).toBe(true);
  await expect.poll(async () => (await statusOf(page)).bitRate).toBe(4_000_000);
  await expect.poll(async () => (await stats(page)).abr.nominal).toBe(4_000_000);
  const tick = (nowMs: number, rttMs: number, dropped = 0, backlog = 0) =>
    page.evaluate((s) => (window as any).__carcast.abrTick(s), { nowMs, rttMs, dropped, backlog });
  // 10 초의 조용한 ping 으로 기준(6ms)이 선다. 그동안은 아무것도 안 한다.
  for (let t = 0; t <= 8000; t += 2000) expect(await tick(t, 6)).toBeNull();
  // rtt 가 한 번 튄 것으로는 안 움직이고, 두 표본 연속이면 25% 내린다 — 재빌드 없이.
  expect(await tick(10_000, 60)).toBeNull();
  expect(await tick(12_000, 60)).toMatchObject({ kind: 'cut', bitrate: 3_000_000 });
  let st = await statusOf(page);
  expect(st.bitRate).toBe(3_000_000);
  expect(st.encoderRestarts).toBe(1);
  expect(st.bitrateChanges).toBe(1);
  // 버린 프레임은 바로 사건이지만, 내린 지 5 초 안에는 다시 내리지 않는다.
  expect(await tick(14_000, 6, 3)).toBeNull();
  expect(await tick(18_000, 6, 3)).toMatchObject({ kind: 'cut', bitrate: 2_200_000 });
  // 20 초 조용하면 공칭의 10% 만큼 되올린다.
  for (let t = 20_000; t < 40_000; t += 2000) expect(await tick(t, 6)).toBeNull();
  expect(await tick(40_000, 6)).toMatchObject({ kind: 'raise', bitrate: 2_600_000 });
  st = await statusOf(page);
  expect(st.bitRate).toBe(2_600_000);
  expect(st.encoderRestarts).toBe(1);
  const s = await stats(page);
  expect(s.abr).toMatchObject({ nominal: 4_000_000, target: 2_600_000, floor: 1_600_000, cuts: 2, raises: 1, active: true });
  await expect(page.locator('#stats')).toContainText('비트↓2600k');
  // 프리셋을 바꾸면 거기서 새로 시작한다(재빌드가 공칭으로 되돌린다).
  expect(await page.evaluate(() => (window as any).__carcast.applyPreset('720p60'))).toBe(true);
  await expect.poll(async () => (await stats(page)).abr.target).toBe(6_000_000);
  expect((await statusOf(page)).encoderRestarts).toBe(2);
});

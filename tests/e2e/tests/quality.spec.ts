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
  await expect(page.locator('#quality-grid .tile')).toHaveCount(5);
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

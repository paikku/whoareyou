// 브라우저 클릭이 안드로이드 입력 주입까지 가는가. 좌표까지 보는 것은 가짜 폰 쪽(input.spec.ts)이고,
// 여기서는 진짜 폰/가상 폰의 카운터(/api/status 의 injected·injectFailed)로 끝단을 확인한다.
// 그래서 BASE_URL 이 있을 때만 — 즉 tools/virtual-phone 이나 핫스팟의 실기기에 대고 돌 때만 — 돈다.
import { expect, test } from '@playwright/test';
import { startPlayback, statusOf } from './helpers';

test.skip(!process.env.BASE_URL, '진짜 서버가 있어야 한다 (tools/virtual-phone 또는 실기기)');

test('화면을 누르면 폰의 가상 디스플레이에 이벤트가 주입된다', async ({ page }) => {
  await page.goto('/');
  await startPlayback(page);
  await page.waitForFunction(() => (window as any).__carcast.stats().controlWs.open);
  const before = await statusOf(page);
  test.skip(before.input !== true, '이 서버에는 입력 주입기가 없다 (source=clip?)');

  const box = await page.locator('#stage').boundingBox();
  if (!box) throw new Error('no stage');
  for (const [fx, fy] of [[0.5, 0.5], [0.4, 0.6], [0.6, 0.4]]) {
    await page.mouse.move(box.x + box.width * fx, box.y + box.height * fy);
    await page.mouse.down();
    await page.mouse.up();
  }
  await page.locator('#bar button[data-key=back]').click();

  await expect.poll(async () => (await statusOf(page)).injected, { timeout: 15_000 })
    .toBeGreaterThan(before.injected);
  const after = await statusOf(page);
  expect(after.injectFailed, 'INJECT_EVENTS 가 막혀 있다 (삼성: USB 디버깅(보안 설정))').toBe(before.injectFailed);
  expect(after.controlErrors).toBe(0);
});

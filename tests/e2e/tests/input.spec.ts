import { expect, test } from '@playwright/test';
import { startPlayback, statusOf } from './helpers';

test.skip(!!process.env.BASE_URL, 'touch assertions need the fake phone state endpoint');

test('touch on the picture arrives as normalised coordinates', async ({ page }) => {
  await page.goto('/');
  await startPlayback(page);
  await page.waitForFunction(() => (window as any).__carcast.stats().controlWs.open);
  await page.evaluate(() => fetch('/api/reset'));

  // The 16:9 picture is letterboxed inside #stage; compute the expected mapping from the layout.
  const box = await page.locator('#stage').boundingBox();
  if (!box) throw new Error('no stage');
  const aspect = 16 / 9;
  let vw = box.width, vh = box.height, ox = 0, oy = 0;
  if (box.width / box.height > aspect) { vw = box.height * aspect; ox = (box.width - vw) / 2; }
  else { vh = box.width / aspect; oy = (box.height - vh) / 2; }
  const target = { x: ox + vw * 0.25, y: oy + vh * 0.75 };
  await page.mouse.move(box.x + target.x, box.y + target.y);
  await page.mouse.down();
  await page.mouse.up();

  await expect.poll(async () => (await statusOf(page)).touches.length, { timeout: 5000 }).toBeGreaterThanOrEqual(2);
  const touches = (await statusOf(page)).touches;
  const down = touches.find((t: any) => t.action === 0);
  const up = touches.find((t: any) => t.action === 1);
  expect(down).toBeTruthy();
  expect(up).toBeTruthy();
  expect(down.x).toBeCloseTo(0.25, 2);
  expect(down.y).toBeCloseTo(0.75, 2);
});

test('nav bar buttons send Android key codes', async ({ page }) => {
  await page.goto('/');
  await startPlayback(page);
  await page.waitForFunction(() => (window as any).__carcast.stats().controlWs.open);
  await page.evaluate(() => fetch('/api/reset'));
  await page.locator('#bar button[data-key=back]').click();
  await page.locator('#bar button[data-key=home]').click();
  await expect.poll(async () => (await statusOf(page)).keys.length).toBeGreaterThanOrEqual(4);
  const keys = (await statusOf(page)).keys.map((k: any) => k.keycode);
  expect(keys).toContain(4);
  expect(keys).toContain(3);
});

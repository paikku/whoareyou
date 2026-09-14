// 개선 가능성 점검 페이지(/upgrade-check.html)의 계약.
//
// 이 페이지가 답하는 것은 "지금이 얼마나 좋은가"가 아니라 "다음에 무엇을 쓸 수 있는가"다. 차에서
// 한 번 열고 나면 다시 물을 수 없으므로, **모든 항목이 판정을 낸다**는 것과 **평문에서 못 잰 것을
// '없다'로 적지 않는다**는 두 가지가 계약이다. 두 번째를 어겼다가 진단 #7·#13 의 WebCodecs 를
// 반년 가까이 "없음"으로 잘못 읽었다(docs/drive-check/README.md).
import { expect, test } from '@playwright/test';
import { BASE_URL } from '../playwright.config';

/** 표에 있는 모든 항목. 하나라도 판정 없이 남으면 차에서 빈칸을 보게 된다. */
const PROBES = [
  'webcodecs', 'wasm-simd', 'wasm-threads', 'offscreen', 'webgpu',
  'track-processor', 'webtransport', 'codec-high', 'codec-modern',
  'wakelock', 'serviceworker', 'storage',
];
/** secure context 전용이라 평문에서는 가려지는 것들. 여기서 '안 된다'가 나오면 거짓말이다. */
const SECURE_ONLY = ['webcodecs', 'wasm-threads', 'webgpu', 'webtransport', 'codec-high', 'codec-modern', 'wakelock', 'serviceworker'];

async function runProbes(page: import('@playwright/test').Page, url: string): Promise<void> {
  await page.goto(url);
  await expect(page.locator('#summary')).not.toHaveText(/점검 중/, { timeout: 60_000 });
}

test('평문(차에서 폰으로 여는 길)에서는 모든 항목이 판정을 내고, 못 잰 것은 가려짐으로 남는다', async ({ page }) => {
  await runProbes(page, '/upgrade-check.html');
  expect(await page.evaluate(() => window.isSecureContext), '차와 같은 평문 origin 이어야 이 테스트가 뜻이 있다').toBe(false);

  for (const id of PROBES) {
    const state = await page.locator(`tr[data-probe="${id}"]`).getAttribute('data-state');
    expect(state, `${id} 가 판정 없이 남았다`).toBeTruthy();
  }
  for (const id of SECURE_ONLY) {
    const state = await page.locator(`tr[data-probe="${id}"]`).getAttribute('data-state');
    expect(state, `${id}: 평문에서 못 잰 것을 단정하면 안 된다`).toBe('hidden');
  }
  // 평문이라는 사실 자체가 화면 맨 위에 뜬다 — 안 그러면 아래 표를 곧이곧대로 읽게 된다.
  await expect(page.locator('#banner')).toBeVisible();

  // 사진으로 옮겨 적을 수 없으니 폰에 남는 것까지가 계약이다.
  await expect(page.locator('#report-result')).toContainText('폰에 저장됨');
  const reports = await page.evaluate(async () => (await fetch('/api/reports')).json());
  const mine = reports.find((r: any) => r.report.kind === 'upgrade');
  expect(mine, JSON.stringify(reports.map((r: any) => r.summary))).toBeTruthy();
  expect(mine.report.probes.webcodecs.state).toBe('hidden');
  expect(mine.report.env.secureContext).toBe(false);
});

// 127.0.0.1 은 인증서 없이도 secure context 라, 가려짐이 걷혔을 때 페이지가 **진짜 답을 내는지**를
// 여기서 본다. 실기기(BASE_URL)를 가리킬 때는 이 주소가 폰이 아니므로 건너뛴다.
test('secure context 에서는 가려짐이 걷히고 실제 디코드까지 시도한다', async ({ page }) => {
  test.skip(!!process.env.BASE_URL, '실기기를 가리키는 실행에서는 127.0.0.1 이 폰이 아니다');
  await runProbes(page, `${BASE_URL.replace(/\/\/[^:]+/, '//127.0.0.1')}/upgrade-check.html`);
  expect(await page.evaluate(() => window.isSecureContext)).toBe(true);

  await expect(page.locator('#banner')).toBeHidden();
  for (const id of SECURE_ONLY) {
    const state = await page.locator(`tr[data-probe="${id}"]`).getAttribute('data-state');
    expect(state, `${id}: secure 인데도 못 쟀다면 점검이 고장 난 것이다`).not.toBe('hidden');
  }
  // WebCodecs 는 기능 검출로 끝내지 않고 실제 키프레임을 넣어 본다. 그림이 나왔다면 크기가 적힌다.
  const wc = page.locator('tr[data-probe="webcodecs"]');
  if (await wc.getAttribute('data-state') === 'yes') {
    await expect(wc.locator('.detail')).toContainText('1280×');
  }
});

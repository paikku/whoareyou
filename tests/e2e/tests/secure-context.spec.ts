import { expect, test } from '@playwright/test';

// 차에 가기 전에 여기서 걸러야 하는 것: **자체서명 인증서를 넘긴 오리진이 정말 secure context 인가.**
//
// `VideoDecoder` 는 WebCodecs IDL 에서 `[SecureContext]` 다. 그래서 평문 http 인 차 화면에서 본
// `WebCodecs X` 는 "이 차에 없다"가 아니라 "물어볼 수 없었다"였고, 그 둘을 가르려면 secure context 가
// 필요하다. 진짜 인증서(도메인·DNS-01·갱신·APK 안의 개인키)를 만들기 전에, 폰이 스스로 만든
// 자체서명 인증서로 그 질문을 할 수 있는지부터 본다.
//
// Playwright 의 `ignoreHTTPSErrors` 는 차에서 "고급 → 계속"을 누르는 것과 같은 자리에 데려다 놓는다
// (인증서 오류를 무시한 https 오리진). 여기서 `isSecureContext` 가 true 이고 코덱 표가 채워지면,
// 차에서도 그 경로가 성립한다 — 남는 미지수는 "테슬라 브라우저가 경고를 넘게 해 주는가" 하나로 준다.
//
// 가짜 폰(node)은 TLS 를 서빙하지 않으므로 JVM shell 서버(`core dev`)나 실기기에서만 돈다.
test.skip(!process.env.BASE_URL, 'TLS listener 가 있는 진짜 서버가 있어야 한다 (core dev / 가상 폰 / 실기기)');

const BASE = process.env.BASE_URL ?? '';

async function httpsBase(): Promise<string | null> {
  const st = await (await fetch(`${BASE}/api/status`)).json();
  if (!st.httpsPort) return null;
  return `https://${new URL(BASE).hostname}:${st.httpsPort}`;
}

test('폰이 만든 인증서로 secure context 가 서고, 거기서 WebCodecs 를 물어본다', async ({ page }) => {
  const base = await httpsBase();
  test.skip(!base, '이 서버에는 TLS listener 가 없다 (https_port=0)');

  await page.goto(`${base}/diag.html`);
  // 이것이 이 검사의 전부다: 오류를 넘긴 https 오리진이 secure context 인가.
  expect(await page.evaluate(() => isSecureContext), 'https 인데 secure context 가 아니다').toBe(true);

  await page.waitForFunction(() => (window as any).__diag?.secure !== undefined, null, { timeout: 30_000 });
  const secure = await page.evaluate(() => (window as any).__diag.secure);
  expect(secure.isSecureContext).toBe(true);

  // Chrome for Testing 148 은 차와 같은 엔진이므로 여기서는 VideoDecoder 가 있어야 한다. 차에서 없다면
  // 그때는 진짜로 "테슬라 빌드에 없다"이고, 그것이 이 계측이 사려는 답이다.
  expect(secure.videoDecoder, 'Chrome 148 인데 VideoDecoder 가 없다 — 프로브가 잘못됐다').toBe(true);
  const configs = secure.configs as Record<string, string>;
  expect(Object.keys(configs).length, '코덱 지원 표가 비어 있다').toBeGreaterThan(0);
  expect(configs['우리 스트림 (Baseline) / no-preference'], JSON.stringify(configs)).toBe('supported');
  // 하드웨어 여부는 이 PC 의 사정이라 단언하지 않는다 — 차에서 무엇이 나오는지가 알고 싶은 것이고,
  // 여기서는 "물어봤고 답이 기록됐다"까지가 검사다.
  console.log('codec support here:', configs);

  // 리포트에 실려 폰에 저장되는지까지 — 차에서는 이 경로가 결과를 가져오는 유일한 길이다.
  await page.waitForFunction(() => (window as any).__diag?.done === true, null, { timeout: 120_000 });
  const diag = await page.evaluate(() => (window as any).__diag);
  expect(diag.report.ok, JSON.stringify(diag.report)).toBe(true);
  await expect(page.locator('#summary')).toContainText('secure=O webcodecs=O');
});

test('평문에서는 물어볼 수 없다고 말하고, 어디로 가야 하는지 알려 준다', async ({ page }) => {
  await page.goto(`${BASE}/diag.html`);
  await page.waitForFunction(() => (window as any).__diag?.secure !== undefined, null, { timeout: 30_000 });
  const secure = await page.evaluate(() => (window as any).__diag.secure);

  // 127.0.0.1 은 크로미엄이 언제나 "신뢰할 만한 오리진"으로 쳐서 평문이어도 secure context 다.
  // 차가 여는 100.99.9.9 에서는 그렇지 않다 — 그 구분이 이 검사의 요점이라 loopback 은 건너뛴다.
  test.skip(/^https?:\/\/(127\.0\.0\.1|localhost)[:/]/.test(page.url()), 'loopback 은 평문이어도 secure context 다');

  expect(secure.isSecureContext).toBe(false);
  expect(secure.videoDecoder, '평문에서 VideoDecoder 가 보인다면 명세가 바뀐 것이다').toBe(false);
  // X 를 "없다"로 읽지 않도록, 페이지가 갈 곳을 적어 준다.
  if (await httpsBase()) {
    await expect(page.locator('#codec-note')).toContainText('물어볼 수 없었습니다');
    expect(secure.httpsUrl).toMatch(/^https:\/\/.+\/diag\.html$/);
  }
});

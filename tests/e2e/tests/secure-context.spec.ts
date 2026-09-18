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

// 진짜 CA 가 서명한 인증서를 폰이 서빙할 때, 차가 **경고 없이** 열리는가.
//
// 2026-09-17 실차에서 자체서명은 넘길 수 없는 경고에 막혔다(고급 버튼 없음). 그래서 남은 길은 공개 CA 뿐이고,
// 도메인을 사지 않고도 되는 방법이 있다: `*.local-ip.sh` 는 Let's Encrypt 와일드카드 인증서와 키를 공개하고
// 그 DNS 는 이름에 적힌 주소를 돌려준다(`100-99-9-9.local-ip.sh` → `100.99.9.9`).
//
// 이 검사는 다른 검사와 달리 **인증서 오류를 무시하지 않는 브라우저**를 새로 띄운다 — 무시해 버리면 정작
// 알고 싶은 것("경고가 안 뜨는가")을 못 본다. 이름은 host-resolver-rules 로 테스트 서버에 꽂는다.
test('공개 CA 인증서를 쓰면 경고 자체가 없다', async ({ playwright }) => {
  const st = await (await fetch(`${BASE}/api/status`)).json();
  test.skip(!st.tlsTrusted, '이 빌드에는 공개 CA 인증서가 없다 (tools/tls/README.md)');
  const host = st.tlsHost as string;
  const port = new URL(BASE).port || '80';

  const browser = await playwright.chromium.launch({
    executablePath: process.env.CHROME_PATH || undefined,
    // --no-proxy-server: 개발 컨테이너의 HTTPS_PROXY 를 타면 host-resolver-rules 가 무시되고 프록시가
    // 연결을 끊는다. 차에는 프록시가 없으니 여기서도 없애는 쪽이 차와 같은 조건이다.
    args: [`--host-resolver-rules=MAP ${host} 127.0.0.1`, '--no-proxy-server'],
  });
  try {
    // ignoreHTTPSErrors 를 켜지 않는다: 경고가 뜨면 이 검사는 실패해야 한다.
    const page = await browser.newContext({ ignoreHTTPSErrors: false }).then((c) => c.newPage());
    const url = `https://${host}:${st.httpsPort}/diag.html`;
    const res = await page.goto(url, { waitUntil: 'domcontentloaded' });
    expect(res?.status(), `경고 화면이 떴다: ${url}`).toBe(200);
    expect(await page.evaluate(() => isSecureContext)).toBe(true);
    expect(await page.title()).toContain('CarCast');

    await page.waitForFunction(() => (window as any).__diag?.secure !== undefined, null, { timeout: 30_000 });
    const secure = await page.evaluate(() => (window as any).__diag.secure);
    expect(secure.videoDecoder).toBe(true);
    console.log(`trusted origin ${url} — port ${port} 의 평문과 같은 서버`);
  } finally {
    await browser.close();
  }
});

// 하드웨어 디코더 경로가 **실제로 그림을 낸다**는 것까지.
//
// 실차 report #67 이 "차에 WebCodecs 가 있다(Baseline·High 둘 다 prefer-hardware)"를 확인해 줬으므로,
// 이제 그 디코더로 실제 스트림을 푸는 렌더러가 기본이 된다 — 단, secure context 에서만. 이 검사는
// 신뢰받는 인증서 오리진에서 본 화면을 열어 렌더러가 webcodecs 로 골라지고, 프레임이 나오고,
// 폰 인코더가 High 로 올라가는지를 본다(Baseline 은 WASM 디코더 때문에 있던 제약이다).
test('secure context 에서는 하드웨어 디코더로 그리고, 인코더를 High 로 올린다', async ({ playwright }) => {
  const st = await (await fetch(`${BASE}/api/status`)).json();
  test.skip(!st.tlsTrusted, '이 빌드에는 공개 CA 인증서가 없다 (tools/tls/README.md)');
  const host = st.tlsHost as string;

  const browser = await playwright.chromium.launch({
    executablePath: process.env.CHROME_PATH || undefined,
    args: [`--host-resolver-rules=MAP ${host} 127.0.0.1`, '--no-proxy-server'],
  });
  try {
    const page = await browser.newContext({ ignoreHTTPSErrors: false }).then((c) => c.newPage());
    await page.goto(`https://${host}:${st.httpsPort}/`);
    await page.waitForFunction(() => !!(window as any).__carcast, null, { timeout: 30_000 });

    const picked = await page.evaluate(() => (window as any).__carcast.stats().renderer);
    expect(picked, 'secure context 인데 WASM 경로로 갔다').toBe('webcodecs');

    await page.waitForFunction(() => (window as any).__carcast.stats().framesDecoded > 10, null, { timeout: 30_000 });
    const s = await page.evaluate(() => (window as any).__carcast.stats());
    expect(s.lastError, s.lastError).toBe('');
    expect(s.fps).toBeGreaterThan(5);
    console.log(`webcodecs: ${s.fps}fps lag ${Math.round(s.latencyMs)}ms frames ${s.framesDecoded} backlog ${s.backlog}`);

    // 폰 인코더는 Baseline 으로 시작해서, 하드웨어 디코더가 붙으면 High 로 올라간다. 그 손잡이는 진짜
    // 디스플레이 소스가 있는 서버에만 있으므로(JVM 개발 서버는 클립을 튼다) 여기서는 있을 때만 본다 —
    // 없는 자리의 검사는 tests/device 의 09-encoder 다.
    const encoder = await fetch(`${BASE}/api/encoder`);
    if (encoder.ok) {
      await expect
        .poll(async () => (await (await fetch(`${BASE}/api/encoder`)).json()).profile, { timeout: 15_000 })
        .toBe('high');
    } else {
      console.log('/api/encoder 없음 (클립 소스) — profile 검사는 기기 층의 몫');
    }
  } finally {
    await browser.close();
  }
});

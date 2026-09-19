// The 💾 button on the main page posts the session's numbers and event log to the phone, the same
// way the diag page does — the only way to get "it stuttered" out of the car with numbers attached.
import { expect, test } from '@playwright/test';

// 가상 폰(에뮬레이터)에서는 건너뛴다: 소프트웨어 인코더가 정지 화면에서 초당 0.3프레임까지 떨어져
// (2026-09-11 실측) 디코드 처리량을 물을 수 없다. 처리량은 A(가짜 폰)와 B(실기기)에서 본다 —
// A+ 는 경로가 이어지는지를 보는 자리다. docs/agent-runbook.md
test.skip(!!process.env.NO_THROUGHPUT, '가상 폰에서는 디코드 처리량을 물을 수 없다');
import { startPlayback } from './helpers';

test('main page saves a session report with stats and events', async ({ page }) => {
  await page.goto('/');
  await startPlayback(page);
  await page.waitForFunction(() => (window as any).__carcast.stats().framesDecoded > 30, null, { timeout: 30_000 });
  // 성능 추이는 10 초에 한 칸이다. 한 칸은 모인 뒤에 저장해야 리포트에 추이가 실렸는지 물을 수 있다.
  await page.waitForFunction(() => (window as any).__carcast.perf.length >= 1, null, { timeout: 20_000 });
  await page.locator('#btn-save').click();
  await expect(page.locator('#stats')).toContainText('저장됨 #');
  const reports = await page.evaluate(async () => (await fetch('/api/reports')).json());
  const mine = reports.find((r: any) => r.report.kind === 'session');
  expect(mine, JSON.stringify(reports.map((r: any) => r.summary))).toBeTruthy();
  // 요약 줄은 차 쪽 수치로 시작하고, 그 뒤에 **폰 쪽 한 줄**이 붙는다. 리포트 #26·#27 을 가리지 못한
  // 이유가 그 폰 쪽 정보가 없어서였으므로(verification-log §3.10), 붙어 있다는 것까지가 계약이다.
  // 요약 줄은 차 쪽 수치로 시작하고, 폰 쪽 한 줄이 **붙어 있을 수 있다**(리포트 #26·#27 을 가리지
  // 못한 이유가 그 정보가 없어서였다 — verification-log §3.10). 붙는 시점은 차가 폰 상태를 한 번
  // 이상 받아 본 뒤이므로, 저장이 그보다 빠르면 없을 수도 있다. 붙었다면 모양이 맞아야 한다.
  // 첫 낱말은 그 세션이 쓴 렌더러다(secure context 면 webcodecs, 평문이면 h264; `?renderer=` 로 바꾼다) — 이름을 박아 두면
  // 기본값이 바뀔 때마다 여기가 깨진다. 계약은 "무엇으로 그렸는지가 맨 앞에 적힌다" 쪽이다.
  // 추이 한 칸은 표본이 1 분 모인 뒤에야 붙는다(perfTrend). 짧은 실행에서는 없는 것이 정상이다.
  expect(mine.summary).toMatch(/^session (h264|webcodecs) \d+fps lag \d+ms( rtt \d+ms)? frames \d+ packets \d+ ws↻0\/0 복구0 드롭\d+( 키프레임요청\d+)?( 끝까지\d+ms)?( 추이 \d+→\d+fps 적체\d+ \([\d.]+분\))?( \| 폰 .*)?$/);
  if (mine.summary.includes('| 폰 ')) {
    expect(mine.summary).toMatch(/화면(ON|OFF)/);
  }
  expect(mine.report.events.join('\n')).toContain('video ws open #1');
  expect(mine.report.stats.recoveries).toBe(0);
  // 성능 추이와 대응책 가능 여부. 차에서 한 번 나갔다 오면 다시 물을 수 없는 것들이라(devtools 가
  // 없다) 저장에 반드시 실려야 한다 — 없으면 "더워져서 느려졌나"를 영영 못 가린다.
  expect(Array.isArray(mine.report.perf)).toBe(true);
  expect(mine.report.perf[0]).toMatchObject({ t: expect.any(Number), fps: expect.any(Number), backlog: expect.any(Number), rttMs: expect.any(Number) });
  expect(mine.report.caps).toMatchObject({ webcodecs: expect.any(Boolean), secure: expect.any(Boolean), cores: expect.any(Number) });
  // The stats line shows only what is abnormal: a clean run has no reconnects or recoveries on it.
  await expect(page.locator('#stats')).not.toContainText('복구');
});

// 실차 #83: 폰만 새 빌드(`2c474d1`)였고 차의 탭은 39 분 전에 연 옛 페이지였다. APK 를 다시 깔면 서버가
// 다시 서고 소켓은 스스로 이어지므로 탭은 열린 채 **옛 자바스크립트로** 계속 돈다 — 리포트에는 폰의 빌드만
// 찍혀서, 그 빌드에서 고친 것이 하나도 안 도는 세션을 "안 고쳐졌다"로 읽을 뻔했다. 이제 페이지도 자기
// 빌드를 알고(HTML 의 `__BUILD__`), 다르면 빌드 하나당 한 번 스스로 다시 연다.
test('폰이 새 빌드로 바뀌면 페이지가 한 번 스스로 다시 열린다', async ({ page }) => {
  // 이 검사는 하나의 가짜 폰을 프로젝트마다 다시 쓰므로 빌드 이름을 매번 새로 짓는다 — 지난 실행이 남긴
  // localStorage 기록과 겹치면 "이미 한 번 열었다"가 되어 아무 일도 안 일어난다(그것도 맞는 동작이다).
  const old = `fake-${Date.now()}-a`;
  const neu = `fake-${Date.now()}-b`;
  await page.goto('/');
  await startPlayback(page);
  // 폰의 빌드를 바꾼다 = APK 를 다시 깐 것이다. 지금 열려 있는 페이지는 옛 것이 되므로 스스로 다시 열린다.
  const first = page.waitForEvent('load');
  await page.evaluate((sha) => fetch(`/api/build?sha=${sha}`, { method: 'POST' }), old);
  await first;
  await expect.poll(async () => page.evaluate(() => (window as any).__carcast?.stats().webBuild), { timeout: 15_000 }).toBe(old);
  const reloaded = page.waitForEvent('load');
  await page.evaluate((sha) => fetch(`/api/build?sha=${sha}`, { method: 'POST' }), neu);
  await reloaded;
  // 다시 열린 페이지는 새 빌드다 — 그러니 같은 이유로 또 열 일이 없다. 그 기록도 남는다(한 번뿐인 근거).
  await expect.poll(async () => page.evaluate(() => (window as any).__carcast?.stats().webBuild), { timeout: 15_000 }).toBe(neu);
  expect(await page.evaluate(() => localStorage.getItem('carcast.reloadedFor'))).toBe(neu);
});

// M4 / M4-b: 가상 화면에 앱 띄우기, 그리고 "폰과 차가 같은 앱을 쓸 때"의 핑퐁.
// 안드로이드는 앱마다 task 를 하나만 두므로 `am start --display N` 은 task 를 **옮긴다**. 그래서
// 서버는 다른 디스플레이에 있으면 강제 종료 후 새로 띄우고(restart=auto), 폰이 도로 가져가면 보고만 한다.
// 지금까지 이 경로는 실기기에서만 볼 수 있었다(docs/testing-guide.md "M4-b").
import { strict as assert } from 'node:assert';
import { test } from 'node:test';
import { adbAvailable, adbShell, pickLauncherApp, serverLog, startApp, status, waitFor } from '../lib.mjs';

// 앱 위치 감시자는 5초마다 돈다(DisplayVideoSource.APP_WATCH_INTERVAL_MS).
const WATCH_MS = 20_000;

test('가상 디스플레이에 앱을 띄운다', async () => {
  const s = await status();
  const pkg = adbAvailable ? pickLauncherApp() : (process.env.TEST_APP ?? 'com.android.settings');
  const r = await startApp(pkg);
  assert.equal(r.ok, true, `${pkg} 실행 실패: ${r.error}\n${await serverLog()}`);
  assert.equal(r.display, s.displayId);
  assert.ok(['started', 'front', 'restarted'].includes(r.action), `뜻밖의 action=${r.action}`);
  const after = await status();
  assert.equal(after.appDisplay, s.displayId);
  assert.equal(after.appOnPhone, false);
});

test('폰이 앱을 가져가면 보고하고, ▶ 를 다시 누르면 새로 띄운다 (M4-b)', { skip: adbAvailable ? false : 'adb 없음' }, async () => {
  const s = await status();
  const pkg = pickLauncherApp();
  await startApp(pkg);

  // 폰 런처에서 아이콘을 누른 것과 같다: task 가 기본 화면(0)으로 끌려간다.
  const component = adbShell(`cmd package resolve-activity --brief ${pkg}`).split('\n').pop().trim();
  adbShell(`am start --display 0 -n ${component} -a android.intent.action.MAIN -c android.intent.category.LAUNCHER`);
  const taken = await waitFor(async () => {
    const st = await status();
    return st.appOnPhone ? st : null;
  }, { timeoutMs: WATCH_MS, what: '폰이 앱을 가져간 것을 감지' });
  assert.equal(taken.appDisplay, 0, '감시자가 앱을 기본 화면에서 찾아야 한다');

  // 서버는 스스로 되찾지 않는다 — 되찾으면 사용자와 앱을 뺏는 싸움이 된다.
  assert.equal((await status()).appOnPhone, true);

  // ▶ 다시: 강제 종료 후 가상 화면에 새로 띄운다.
  const again = await startApp(pkg);
  assert.equal(again.ok, true);
  assert.equal(again.action, 'restarted', `action=${again.action}, fromDisplay=${again.fromDisplay} — ` +
    `'am stack list' 로 task 위치를 못 읽으면 여기서 'started' 가 된다(core/…/TaskList.kt 파서).\n${await serverLog()}`);
  assert.equal(again.fromDisplay, 0);
  assert.equal((await status()).appOnPhone, false);
  assert.equal((await status()).appDisplay, s.displayId);
});

test('없는 앱 이름은 500 이 아니라 이유가 담긴 실패로 돌아온다', async () => {
  const r = await startApp('com.example.nope');
  assert.equal(r.ok, false);
  assert.ok(r.error, '이유 없이 실패했다');
});

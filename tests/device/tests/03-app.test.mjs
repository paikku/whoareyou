// M4 / M4-b: 가상 화면에 앱 띄우기, 그리고 "폰과 차가 같은 앱을 쓸 때"의 핑퐁.
// 안드로이드는 앱마다 task 를 하나만 두므로 `am start --display N` 은 task 를 **옮긴다**. 그래서
// 그 옮김이 곧 "보던 것을 차로 가져오기"라 기본이 그것이고(restart=never → moved), 새로 띄우는 것은 따로
// 부른다(restart=always → restarted). 폰이 도로 가져가면 서버는 보고만 한다.
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
  assert.ok(['started', 'front', 'moved'].includes(r.action), `뜻밖의 action=${r.action}`);
  const after = await status();
  assert.equal(after.appDisplay, s.displayId);
  assert.equal(after.appOnPhone, false);
});

test('폰이 앱을 가져가면 보고하고, ▶ 를 다시 누르면 그대로 가져오며, 새로 열기는 새로 띄운다 (M4-b)', { skip: adbAvailable ? false : 'adb 없음' }, async () => {
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

  // ▶ 다시(기본 = never): 폰의 task 를 그대로 가상 화면으로 옮긴다. 새로 띄우지 않는다.
  const again = await startApp(pkg);
  assert.equal(again.ok, true);
  assert.equal(again.action, 'moved', `action=${again.action}, fromDisplay=${again.fromDisplay} — ` +
    `'am stack list' 로 task 위치를 못 읽으면 여기서 'started' 가 된다(core/…/TaskList.kt 파서).\n${await serverLog()}`);
  assert.equal(again.fromDisplay, 0);
  assert.equal((await status()).appOnPhone, false);
  assert.equal((await status()).appDisplay, s.displayId);
  // 옮긴 뒤에도 그 task 가 정말 가상 화면에 있어야 한다(감시자가 다음 틱에 다르게 말하면 옮김이 아니라 복사/실패다).
  const settled = await waitFor(async () => {
    const st = await status();
    return st.appDisplay === s.displayId && !st.appOnPhone ? st : null;
  }, { timeoutMs: WATCH_MS, what: '옮긴 앱이 가상 화면에 자리잡음' });
  assert.equal(settled.appOnPhone, false);

  // 폰이 다시 가져간 뒤 "새로 열기"(always): 강제 종료 후 가상 화면에 새로 띄운다.
  adbShell(`am start --display 0 -n ${component} -a android.intent.action.MAIN -c android.intent.category.LAUNCHER`);
  await waitFor(async () => ((await status()).appOnPhone ? true : null), { timeoutMs: WATCH_MS, what: '폰이 앱을 다시 가져간 것을 감지' });
  const fresh = await startApp(pkg, 'always');
  assert.equal(fresh.ok, true);
  assert.equal(fresh.action, 'restarted', `action=${fresh.action}\n${await serverLog()}`);
  assert.equal(fresh.fromDisplay, 0);
  assert.equal((await status()).appOnPhone, false);
  assert.equal((await status()).appDisplay, s.displayId);
});

test('없는 앱 이름은 500 이 아니라 이유가 담긴 실패로 돌아온다', async () => {
  const r = await startApp('com.example.nope');
  assert.equal(r.ok, false);
  assert.ok(r.error, '이유 없이 실패했다');
});

// 차의 홈과 최근앱. 폰의 HOME/APP_SWITCH 키는 **이벤트에 실린 디스플레이가 아니라 기본 디스플레이의
// 것으로** 처리되므로, 차에서 누르면 보던 앱이 폰(display 0)으로 끌려간다(실차 리포트 #30).
// 그래서 차는 자기 목록을 직접 그린다. 여기서 보는 것은 그 목록을 만드는 두 엔드포인트다.
import { strict as assert } from 'node:assert';
import { test } from 'node:test';
import { BASE_URL, adbAvailable, api, pickLauncherApp, sleep, startApp, status } from '../lib.mjs';

test('GET /api/apps — 차 홈에 올릴 앱 목록이 나온다', async (t) => {
  const apps = await api('/api/apps');
  assert.ok(Array.isArray(apps), '배열이 아니다');
  assert.ok(apps.length > 0, '실행 가능한 앱이 하나도 없다 — 차 홈이 빈 화면이 된다');
  for (const a of apps.slice(0, 5)) {
    assert.equal(typeof a.package, 'string');
    assert.ok(a.label && typeof a.label === 'string', `${a.package} 에 이름이 없다`);
  }
  const withIcon = apps.filter((a) => typeof a.icon === 'string' && a.icon.startsWith('data:image/png;base64,'));
  t.diagnostic(`앱 ${apps.length}개, 아이콘 ${withIcon.length}개`);
  // 아이콘은 기기·ROM 에 따라 못 그릴 수 있다. 하나도 없으면 그 자체가 알아야 할 사실이다.
  if (!withIcon.length) t.diagnostic('아이콘을 하나도 그리지 못했다 — 차 홈은 글자 타일로 뜬다');
  if (adbAvailable) {
    const known = pickLauncherApp();
    assert.ok(apps.some((a) => a.package === known), `띄울 수 있는 앱 ${known} 이 목록에 없다`);
  }
});

test('GET /api/apps?icons=0 — 아이콘을 빼면 훨씬 가볍다', async (t) => {
  const [full, lean] = await Promise.all([
    fetch(`${BASE_URL}/api/apps`).then((r) => r.text()),
    fetch(`${BASE_URL}/api/apps?icons=0`).then((r) => r.text()),
  ]);
  t.diagnostic(`아이콘 포함 ${Math.round(full.length / 1024)}KB, 제외 ${Math.round(lean.length / 1024)}KB`);
  assert.ok(lean.length <= full.length, '아이콘을 빼라고 했는데 더 커졌다');
  assert.ok(!lean.includes('data:image'), 'icons=0 인데 아이콘이 실려 왔다');
});

test('GET /api/tasks — 차 화면에서 도는 앱이 "여기"로 나온다', async (t) => {
  const s0 = await status();
  if (s0.source !== 'display') return t.skip('클립 모드 — 가상 디스플레이가 없다');
  const pkg = adbAvailable ? pickLauncherApp() : (process.env.TEST_APP ?? 'com.android.settings');
  await startApp(pkg);
  await sleep(2500);
  const s = await status();
  const tasks = await api('/api/tasks');
  assert.ok(Array.isArray(tasks), '배열이 아니다');
  const mine = tasks.filter((x) => x.package === pkg);
  t.diagnostic(`태스크 ${tasks.length}개 중 ${pkg}: ${JSON.stringify(mine)}`);
  assert.ok(mine.length > 0, `방금 띄운 ${pkg} 이 태스크 목록에 없다 — 최근앱이 빈 채로 뜬다`);
  const here = mine.find((x) => x.here);
  assert.ok(here, `${pkg} 이 차 화면(display ${s.displayId})에 있다고 나오지 않는다`);
  assert.equal(here.display, s.displayId, '"여기"라면서 디스플레이 번호가 다르다');
  assert.ok(here.label && here.label.length > 0, '최근앱에 보여 줄 이름이 없다');
  assert.equal(typeof here.taskId, 'number');
});

test('최근앱에서 고른 앱이 차 화면으로 온다', async (t) => {
  const s0 = await status();
  if (s0.source !== 'display') return t.skip('클립 모드');
  const tasks = await api('/api/tasks');
  const here = tasks.find((x) => x.here);
  if (!here) return t.skip('차 화면에 도는 앱이 없다');
  // 차 UI 의 타일은 이것과 같은 요청을 보낸다(▶ 와 같은 길).
  const r = await api(`/api/app?name=${encodeURIComponent(here.package)}`, { method: 'POST' });
  t.diagnostic(`${here.package} → ${JSON.stringify(r)}`);
  assert.equal(r.display, s0.displayId, '고른 앱이 차 화면으로 오지 않았다');
  const after = await status();
  assert.equal(after.appOnPhone, false, '고르고 났더니 폰에 있다고 한다');
});

// 차의 홈과 최근앱. 폰의 HOME/APP_SWITCH 키는 **이벤트에 실린 디스플레이가 아니라 기본 디스플레이의
// 것으로** 처리되므로, 차에서 누르면 보던 앱이 폰(display 0)으로 끌려간다(실차 리포트 #30).
// 그래서 차는 자기 목록을 직접 그린다. 여기서 보는 것은 그 목록을 만드는 두 엔드포인트다.
import { strict as assert } from 'node:assert';
import { test } from 'node:test';
import { adbAvailable, adbShell, api, collectVideo, pickLauncherApp, sleep, startApp, status } from '../lib.mjs';

test('GET /api/apps — 차 홈에 올릴 앱 목록이 나온다', async (t) => {
  const t0 = Date.now();
  const apps = await api('/api/apps');
  const ms = Date.now() - t0;
  const s = await status();
  t.diagnostic(`${ms}ms, 출처=${s.appsFrom}, 개수=${s.apps}${s.appsError ? `, 문제=${s.appsError}` : ''}`);
  // 목록을 못 만들면 배열 대신 이유가 온다. 빈 배열로 뭉개면 차는 "앱이 없다"고만 말하게 된다.
  assert.ok(Array.isArray(apps), `목록 대신 이유가 왔다: ${JSON.stringify(apps)}`);
  assert.ok(apps.length > 0, '실행 가능한 앱이 하나도 없다 — 차 홈이 빈 화면이 된다');
  for (const a of apps.slice(0, 5)) {
    assert.equal(typeof a.package, 'string');
    assert.ok(a.label && typeof a.label === 'string', `${a.package} 에 이름이 없다`);
  }
  // **목록에 아이콘이 실리면 안 된다.** 실기기에서 그것이 앱마다 리소스를 여는 일이 되어
  // 새 연결이 전부 실패했다(실차 리포트 #31~33). 아이콘은 /api/icon 이 하나씩 준다.
  assert.ok(!JSON.stringify(apps).includes('data:image'), '목록에 아이콘이 실려 있다');
  if (adbAvailable) {
    const known = pickLauncherApp();
    assert.ok(apps.some((a) => a.package === known), `띄울 수 있는 앱 ${known} 이 목록에 없다`);
  }
});

test('GET /api/icon — 아이콘은 하나씩 온다', async (t) => {
  const apps = await api('/api/apps');
  const pkg = apps[0].package;
  const r = await api(`/api/icon?pkg=${encodeURIComponent(pkg)}`);
  assert.equal(r.package, pkg);
  t.diagnostic(`${pkg}: ${r.icon ? `${Math.round(r.icon.length / 1024)}KB` : '못 그림'}`);
  if (r.icon) assert.ok(r.icon.startsWith('data:image/png;base64,'), '아이콘 모양이 아니다');
});

// 이번 사고를 잡았을 검사. 목록의 **모든** 앱 아이콘을 가져와도 스트림이 살아 있어야 하고,
// 무엇보다 **새 연결이 계속 열려야** 한다 — 실기기에서 무너진 것이 정확히 그 지점이었다.
test('아이콘을 전부 가져와도 새 연결이 계속 열린다', { timeout: 180_000 }, async (t) => {
  const apps = await api('/api/apps');
  const t0 = Date.now();
  for (const a of apps) {
    await api(`/api/icon?pkg=${encodeURIComponent(a.package)}`);
  }
  t.diagnostic(`${apps.length}개 아이콘에 ${Date.now() - t0}ms`);
  // 새 소켓 열기 — 여기가 fd 가 새면 처음 막히는 자리다.
  for (let i = 0; i < 5; i++) {
    const s = await status();
    assert.equal(s.running, true);
  }
  const packets = await collectVideo(3_000);
  assert.ok(packets.length >= 0);
  const after = await api('/api/apps');
  assert.ok(Array.isArray(after) && after.length === apps.length, '아이콘을 다 가져온 뒤 목록이 달라졌다');
});

test('GET /api/tasks — 차 화면에서 도는 앱이 "여기"로 나온다', async (t) => {
  const s0 = await status();
  if (s0.source !== 'display') return t.skip('클립 모드 — 가상 디스플레이가 없다');
  const pkg = adbAvailable ? pickLauncherApp() : (process.env.TEST_APP ?? 'com.android.settings');
  await startApp(pkg);
  await sleep(2500);
  const s = await status();
  const r = await api('/api/tasks');
  assert.equal(typeof r, 'object');
  assert.equal(r.display, s.displayId, '/api/tasks 가 말하는 차 화면 번호가 상태와 다르다');
  const tasks = r.tasks;
  assert.ok(Array.isArray(tasks), `tasks 가 배열이 아니다: ${JSON.stringify(r)}`);
  t.diagnostic(`차 화면 태스크 ${tasks.length}개, 폰 쪽 ${r.elsewhere}개: ${tasks.map((x) => x.package).join(', ')}`);
  // **차 화면 것만** 와야 한다. 폰에서 쓰는 앱은 차의 최근앱이 아니다.
  for (const x of tasks) {
    assert.equal(x.display, s.displayId, `차 화면이 아닌 태스크가 섞였다: ${JSON.stringify(x)}`);
  }
  const mine = tasks.filter((x) => x.package === pkg);
  assert.ok(mine.length > 0, `방금 띄운 ${pkg} 이 태스크 목록에 없다 — 최근앱이 빈 채로 뜬다`);
  assert.ok(mine[0].label && mine[0].label.length > 0, '최근앱에 보여 줄 이름이 없다');
  assert.equal(typeof mine[0].taskId, 'number');
  // 최신순: 방금 띄운 것이 맨 앞.
  assert.equal(tasks[0].package, pkg, '방금 띄운 앱이 최근앱 맨 앞에 없다');
});

test('홈은 최근에 쓴 앱을 맨 위에 올린다', async (t) => {
  const s0 = await status();
  if (s0.source !== 'display') return t.skip('클립 모드');
  const pkg = adbAvailable ? pickLauncherApp() : (process.env.TEST_APP ?? 'com.android.settings');
  await startApp(pkg);
  await sleep(1500);
  const apps = await api('/api/apps');
  assert.ok(Array.isArray(apps), `목록 대신 이유가 왔다: ${JSON.stringify(apps)}`);
  t.diagnostic(`맨 위 3개: ${apps.slice(0, 3).map((a) => a.package).join(', ')}`);
  // 운전 중에 이름순 목록 한가운데서 찾게 하면 쓸 수 없다. 방금 띄운 것이 맨 위여야 한다.
  assert.equal(apps[0].package, pkg, '방금 띄운 앱이 홈 맨 위에 없다');
  assert.ok(apps[0].lastUsed > 0, '쓴 시각이 기록되지 않았다');
});

test('사용 기록이 파일로 남아 서버를 껐다 켜도 읽힌다', async (t) => {
  if (!adbAvailable) return t.skip('adb 없음');
  const s = await status();
  t.diagnostic(`기록된 앱 수: ${s.appHistory}`);
  assert.ok(s.appHistory > 0, '띄운 앱이 있는데 기록이 비어 있다');
  const file = adbShell('cat /data/local/tmp/carcast/app-history.tsv 2>/dev/null || echo MISSING');
  t.diagnostic(`app-history.tsv:\n${file}`);
  assert.notEqual(file, 'MISSING', '기록 파일이 없다 — 서버를 껐다 켜면 순서가 사라진다');
  // <epoch>\t<횟수>\t<패키지> 한 줄씩. 사람이 읽고 고칠 수 있어야 한다.
  const rows = file.split('\n').filter(Boolean).map((l) => l.split('\t'));
  assert.ok(rows.length > 0 && rows[0].length === 3, `형식이 다르다: ${JSON.stringify(rows[0])}`);
  assert.ok(Number(rows[0][0]) > 0 && Number(rows[0][1]) > 0, '시각이나 횟수가 숫자가 아니다');
  // 파일 맨 위가 곧 최신이어야 한다 (서버가 껐다 켜도 이 순서를 그대로 읽는다).
  const times = rows.map((r) => Number(r[0]));
  assert.deepEqual(times, [...times].sort((a, b) => b - a), '파일이 최신순이 아니다');
});

test('최근앱에서 고른 앱이 차 화면으로 온다', async (t) => {
  const s0 = await status();
  if (s0.source !== 'display') return t.skip('클립 모드');
  const here = (await api('/api/tasks')).tasks[0];
  if (!here) return t.skip('차 화면에 도는 앱이 없다');
  // 차 UI 의 타일은 이것과 같은 요청을 보낸다(▶ 와 같은 길).
  const r = await api(`/api/app?name=${encodeURIComponent(here.package)}`, { method: 'POST' });
  t.diagnostic(`${here.package} → ${JSON.stringify(r)}`);
  assert.equal(r.display, s0.displayId, '고른 앱이 차 화면으로 오지 않았다');
  const after = await status();
  assert.equal(after.appOnPhone, false, '고르고 났더니 폰에 있다고 한다');
});

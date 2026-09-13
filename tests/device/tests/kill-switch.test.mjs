// 킬 스위치: loopback 의 POST /api/stop 하나로 분리 실행된 서버가 사라져야 한다.
// 이 검사는 서버를 죽이므로 마지막에 따로 돌린다 (npm run test:kill-switch).
import { strict as assert } from 'node:assert';
import { test } from 'node:test';
import { BASE_URL, adbAvailable, adbShell, api, sleep, status } from '../lib.mjs';

test('POST /api/stop → 서버가 내려간다, 그리고 건드린 설정을 되돌린다', async (t) => {
  // 서버는 폰의 유휴 타이머를 밀어 두고 돈다. 내려갈 때 원래 값으로 못 되돌리면 폰은 그 뒤로도
  // 영영 안 꺼지는 화면을 갖게 된다 — 사용자가 모르는 채로. 그래서 여기서 같이 본다.
  const before = await status();
  assert.equal((await api('/api/stop', { method: 'POST' })).ok, true);
  let down = false;
  for (let i = 0; i < 20; i++) {
    await sleep(500);
    try { await fetch(`${BASE_URL}/api/status`); } catch { down = true; break; } // 연결 거부 = 내려간 것
  }
  assert.ok(down, '10초가 지나도 서버가 응답한다 — 킬 스위치가 듣지 않는다');
  if (!adbAvailable) return t.diagnostic('adb 없음 — 설정 복원은 확인하지 못했다');
  if (before.screenOffTimeoutWas == null) return t.diagnostic('이 서버는 screen_off_timeout 을 건드리지 않았다');
  await sleep(1500); // 되돌리기는 소켓이 닫힌 **뒤에** 돈다 (ServerMain.run 의 onStopped)
  const now = adbShell('settings get system screen_off_timeout');
  t.diagnostic(`screen_off_timeout: 돌기 전 ${before.screenOffTimeoutWas} → 도는 동안 ${before.screenOffTimeout} → 지금 ${now}`);
  assert.equal(now, String(before.screenOffTimeoutWas), '서버가 내려갔는데 유휴 타이머가 원래대로 안 돌아왔다');
  // 되돌렸으면 "아직 되돌릴 것이 남았다"는 기록도 사라져야 한다. 남아 있으면 다음 실행이 이미 정상인
  // 값을 사용자 값으로 착각하고 또 되돌린다.
  const stash = adbShell('cat /data/local/tmp/carcast/screen_off_timeout.prev 2>/dev/null || echo GONE');
  assert.equal(stash, 'GONE', `되돌렸는데 기록이 남아 있다: ${stash}`);
});

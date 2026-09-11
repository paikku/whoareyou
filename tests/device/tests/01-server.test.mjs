// 서버가 폰에서와 같은 모습으로 떠 있는가. (실기기 체크리스트의 "응답 중 shell uid=2000" 줄)
import { strict as assert } from 'node:assert';
import { test } from 'node:test';
import { serverLog, status } from '../lib.mjs';

test('shell uid 로 돌고, 클립이 아니라 가상 디스플레이를 송출한다', async () => {
  const s = await status();
  assert.equal(s.running, true);
  assert.equal(s.process, 'shell', '앱 uid 로 돌면 차(핫스팟 클라이언트)가 VPN 주소에 닿지 못한다');
  assert.equal(s.uid, 2000, `uid=${s.uid} — app_process 가 shell 로 뜨지 않았다`);
  assert.ok(s.build, 'build id 가 없다');
  // source=clip 은 가상 디스플레이 생성에 실패해 번들 클립으로 대체됐다는 뜻 — M4 가 깨진 것이다.
  assert.equal(s.source, 'display', `source=${s.source}. 서버 로그:\n${await serverLog()}`);
  assert.ok(s.displayId > 0, `displayId=${s.displayId} — 가상 디스플레이가 기본 화면(0)일 리 없다`);
  assert.equal(s.input, true, '입력 주입기가 없다 (터치가 안 먹는 상태)');
  assert.ok(s.encoder, '인코더 이름이 비어 있다');
});

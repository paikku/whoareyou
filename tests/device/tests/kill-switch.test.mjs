// 킬 스위치: loopback 의 POST /api/stop 하나로 분리 실행된 서버가 사라져야 한다.
// 이 검사는 서버를 죽이므로 마지막에 따로 돌린다 (npm run test:kill-switch).
import { strict as assert } from 'node:assert';
import { test } from 'node:test';
import { BASE_URL, api, sleep } from '../lib.mjs';

test('POST /api/stop → 서버가 내려간다', async () => {
  assert.equal((await api('/api/stop', { method: 'POST' })).ok, true);
  for (let i = 0; i < 20; i++) {
    await sleep(500);
    try { await fetch(`${BASE_URL}/api/status`); } catch { return; } // 연결 거부 = 내려간 것
  }
  assert.fail('10초가 지나도 서버가 응답한다 — 킬 스위치가 듣지 않는다');
});

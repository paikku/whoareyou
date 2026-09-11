// M7: 📵 — 폰 화면만 끄고 가상 디스플레이는 계속 돈다.
// SurfaceControl.setDisplayPowerMode 는 기기마다 되고 안 되고가 갈린다(에뮬레이터 포함). 그래서
// "요청이 성공했다고 답했다면 상태도 따라와야 한다"까지를 여기서 보고, 실제 소등 확인은 실기기 몫이다.
import { strict as assert } from 'node:assert';
import { test } from 'node:test';
import { api, collectVideo, status } from '../lib.mjs';

test('화면 OFF 요청이 상태에 반영되고, 그동안에도 영상은 계속 나온다', async (t) => {
  const off = await api('/api/screen?on=0', { method: 'POST' });
  assert.equal(typeof off.ok, 'boolean');
  if (!off.ok) {
    t.diagnostic('이 기기에서는 디스플레이 전원 전환이 안 된다 — 실기기(M7)에서 확인할 것');
  } else {
    assert.equal(off.screenOn, false);
    assert.equal((await status()).screenOn, false);
    const packets = await collectVideo(3_000);
    assert.ok(packets.filter((p) => p.type !== 0).length > 0, '화면을 끄자 가상 디스플레이까지 멈췄다');
  }
  const on = await api('/api/screen?on=1', { method: 'POST' });
  if (off.ok) assert.equal(on.screenOn, true, '다시 켜지지 않았다');
});

// M5: 차 브라우저의 터치·키가 가상 디스플레이에 실제로 주입되는가.
// 삼성에서 "USB 디버깅(보안 설정)" 이 꺼져 있으면 INJECT_EVENTS 가 막혀 injectFailed 만 올라간다 —
// 여기서 잡고 싶은 것이 그 상태다.
import { strict as assert } from 'node:assert';
import { test } from 'node:test';
import { control, serverLog, sleep, status, waitFor } from '../lib.mjs';

test('터치와 키가 주입되고, 실패 카운터는 늘지 않는다', async () => {
  const before = await status();
  const c = await control();
  try {
    for (const [x, y] of [[0.5, 0.5], [0.3, 0.7], [0.7, 0.3]]) await c.tap(x, y);
    c.key(0, 4); c.key(1, 4); // BACK
    await sleep(500);
    const after = await waitFor(async () => {
      const s = await status();
      return s.injected > (before.injected ?? 0) ? s : null;
    }, { timeoutMs: 10_000, what: 'injected 증가' }).catch(async (e) => {
      throw new Error(`${e.message}\n서버 로그:\n${await serverLog()}`);
    });
    assert.ok(after.injected >= (before.injected ?? 0) + 3, `injected ${before.injected} → ${after.injected}`);
    assert.equal(after.injectFailed, before.injectFailed ?? 0,
      `injectFailed 가 늘었다 (${before.injectFailed} → ${after.injectFailed}) — INJECT_EVENTS 권한을 보라`);
    assert.equal(after.controlErrors, 0, '제어 패킷 파싱에 실패한 것이 있다');
  } finally {
    c.close();
  }
});

// 차의 제어 소켓은 제스처 도중에 가장 잘 죽는다(테슬라 브라우저의 WS 실패율, 후진, 핫스팟 흔들림).
// 그때 UP 이 영영 안 오면 안드로이드는 그 손가락을 계속 눌린 것으로 들고 있고, 다음 탭은 어딘가에 유령
// 손가락이 있는 두 손가락 제스처가 된다 — 탭이 핀치가 되고, 운전자에게는 "차 화면이 미쳤다"로 보인다.
// 스스로 회복되지 않으므로 서버가 소켓이 사라진 순간 제스처를 취소해야 한다.
test('제스처 도중 소켓이 끊기면 눌린 손가락이 남지 않는다', async () => {
  const before = await status();
  assert.equal(before.pointersDown ?? 0, 0, '시작부터 눌린 손가락이 있다');

  const c = await control();
  c.touch(0, 0.5, 0.5);          // 손가락을 대고
  await sleep(300);
  assert.equal((await status()).pointersDown, 1, 'DOWN 이 반영되지 않았다');
  c.close();                      // UP 없이 소켓이 죽는다

  const after = await waitFor(async () => {
    const s = await status();
    return (s.pointersDown ?? 0) === 0 ? s : null;
  }, { timeoutMs: 10_000, what: '눌린 손가락 정리' }).catch(async (e) => {
    throw new Error(`${e.message}\n서버 로그:\n${await serverLog()}`);
  });
  assert.equal(after.pointersDown, 0);
  assert.equal(after.injectFailed, before.injectFailed ?? 0, '취소를 주입하다 실패했다');
});

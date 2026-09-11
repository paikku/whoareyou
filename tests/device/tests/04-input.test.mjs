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

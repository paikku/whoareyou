// 차 하나가 안 읽으면 폰이 멎는가. (실차 리포트 #41-42: 그림이 얼고 두 소켓이 계속 재접속했다)
//
// 원인은 init 세그먼트를 **기다리면서** 보냈던 데 있다 — 안 읽는 소켓 하나가 인코더 출력 스레드를
// 붙잡고 있었고, 그 스레드가 EncodedH264Sink 의 자물쇠를 쥔 채였다. 그 계약은 core 단위 테스트
// (StalledClientTest) 가 확정적으로 못박는다. 여기서는 실제 서버에서 같은 성질을 확인한다:
// 안 읽는 손님이 붙어 있어도 읽는 손님의 그림은 계속 와야 하고, 그 사이 앱을 띄워도(= 인코더가
// 다시 시작하고 init 세그먼트가 나간다) 마찬가지여야 한다.
import { strict as assert } from 'node:assert';
import { test } from 'node:test';
import { WebSocket } from 'ws';
import { BASE_URL, ensureApp, sleep, startApp, status, wiggle } from '../lib.mjs';

const WS_BASE = BASE_URL.replace(/^http/, 'ws');

test('받은 연결 수와 accept 오류를 보고한다', async () => {
  const s = await status();
  // "죽었다"고 할 때 폰까지 닿기는 했는지를 가르는 유일한 증거다. 없으면 끊긴 링크와 멎은
  // 서버가 브라우저에서 똑같아 보인다.
  assert.equal(typeof s.accepts, 'number', 'accepts 가 없다 — 리포트로 원인을 가를 수 없다');
  assert.ok(s.accepts > 0, `accepts=${s.accepts} — 방금 우리가 붙었는데 0일 수 없다`);
  assert.equal(s.accepting, true, 'accept 루프가 끝났다 — 프로세스는 살아 있어도 아무도 못 붙는다');
  assert.equal(s.acceptErrors, 0, `accept 가 ${s.acceptErrors}번 실패했다`);
});

test('안 읽는 손님이 하나 붙어 있어도, 읽는 손님의 그림은 계속 온다', async () => {
  const first = await ensureApp();
  const pkg = (first.app ?? '').split('/')[0] || process.env.TEST_APP || 'com.android.settings';
  const stop = await wiggle();
  try {
    // 안 읽는 손님: 붙기만 하고 소켓을 멈춰 둔다(노드가 커널 버퍼를 비우지 않는다).
    const deaf = new WebSocket(`${WS_BASE}/ws/video`);
    await new Promise((r, e) => { deaf.on('open', r); deaf.on('error', e); });
    deaf.pause();

    const live = new WebSocket(`${WS_BASE}/ws/video`);
    let frames = 0;
    live.on('message', () => { frames++; });
    await new Promise((r, e) => { live.on('open', r); live.on('error', e); });

    await sleep(2000);
    const before = frames;
    assert.ok(before > 0, '읽는 손님이 아무것도 못 받았다');

    // 앱을 다시 띄우면 인코더가 다시 시작하고 init 세그먼트가 나간다 — 예전에 서버가 멎던 바로 그 지점.
    // restart=always 여야 실제로 다시 뜬다(이미 우리 화면에 있으면 ensureApp 은 아무것도 하지 않는다).
    await startApp(pkg, 'always');
    await sleep(3000);
    assert.ok(frames > before, `init 세그먼트 뒤로 그림이 멈췄다 (${before} → ${frames})`);

    const s = await status();
    assert.equal(s.running, true, '서버가 응답을 멈췄다');
    assert.equal(s.accepting, true, 'accept 루프가 죽었다');
    try { deaf.terminate(); } catch { /* 이미 닫힘 */ }
    try { live.close(); } catch { /* 이미 닫힘 */ }
  } finally {
    stop();
  }
});

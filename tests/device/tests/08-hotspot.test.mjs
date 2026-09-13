// 핫스팟 제어(/api/hotspot)와 바탕화면 위젯이 **기기에 실제로 붙어 있는지**.
//
// 여기서 볼 수 있는 것과 없는 것이 분명하다. 에뮬레이터에는 진짜 AP 가 없으므로 "핫스팟이 켜졌다"는
// 실기기(B) 몫이고(docs/testing-guide.md), 이 자리에서 지키는 것은 그 앞의 것들이다:
//   1. 엔드포인트가 있고, 늘 JSON 으로 답하고, **모르는 것을 안다고 하지 않는다**
//   2. /api/status 가 핫스팟 상태를 함께 싣는다 (위젯이 요청을 한 번 더 하지 않아도 되게)
//   3. 위젯 provider 가 플랫폼에 등록됐다 — appwidget xml 이나 receiver 가 깨졌으면 여기서 걸린다
//   4. 켜기 요청이 무엇을 하든 **서버를 죽이지 않는다** (일괄 끄기의 1단계가 서버를 통과하므로)
import { strict as assert } from 'node:assert';
import { test } from 'node:test';
import { adbAvailable, adbShell, api, status } from '../lib.mjs';

const PKG = 'com.carcast';

test('/api/hotspot 이 있고, 모르는 것은 모른다고 답한다', async (t) => {
  const h = await api('/api/hotspot');
  t.diagnostic(`on=${h.on} known=${h.known} via=${h.via} controllable=${h.controllable} interfaces=${JSON.stringify(h.interfaces)}`);
  assert.equal(typeof h.on, 'boolean');
  assert.equal(typeof h.known, 'boolean');
  assert.equal(typeof h.controllable, 'boolean', 'controllable 이 없다 — 옛 빌드다');
  assert.ok(Array.isArray(h.interfaces), 'interfaces 가 배열이 아니다');
  // known=false 는 실패가 아니라 답이다. 거짓말만 실패다: 모르면서 "꺼져 있다"고 단정하면
  // 일괄 끄기가 1단계를 건너뛰고 핫스팟을 켜 둔 채 서버를 죽인다.
  assert.ok(typeof h.via === 'string' && h.via.length > 0, 'via 가 비었다 — 무엇이 답했는지 알 수 없다');
});

test('/api/status 가 핫스팟 상태를 함께 싣는다', async () => {
  const s = await status();
  assert.ok(s.hotspot && typeof s.hotspot === 'object', '/api/status 에 hotspot 이 없다 — 위젯이 매번 따로 물어야 한다');
  assert.equal(typeof s.hotspot.known, 'boolean');
  assert.equal(typeof s.hotspot.controllable, 'boolean');
});

test('켜기 요청이 무엇을 하든 서버는 살아남는다', { timeout: 90_000 }, async (t) => {
  const before = await status();
  // wait 를 짧게 준다: 여기서 알고 싶은 것은 "켜졌나"가 아니라 "이 경로가 서버를 부수지 않나"다.
  const on = await api('/api/hotspot?on=1&wait=3000', { method: 'POST' });
  t.diagnostic(`켜기: ok=${on.ok} detail=${on.detail}`);
  assert.equal(typeof on.ok, 'boolean');
  assert.ok(typeof on.detail === 'string' && on.detail.length > 0, '실패든 성공이든 이유를 말해야 한다');
  try {
    const off = await api('/api/hotspot?on=0&wait=3000', { method: 'POST' });
    t.diagnostic(`끄기: ok=${off.ok} detail=${off.detail}`);
  } finally {
    const after = await status();
    assert.equal(after.running, true, '핫스팟 요청 뒤 서버가 죽었다');
    assert.equal(after.uid, before.uid, '서버가 다른 프로세스로 바뀌었다 — 죽었다가 다시 떴다는 뜻');
  }
});

test('바탕화면 위젯 provider 가 플랫폼에 등록돼 있다', async (t) => {
  if (!adbAvailable) return t.skip('adb 없음');
  const installed = adbShell(`pm list packages ${PKG}`);
  if (!installed.includes(PKG)) return t.skip('앱이 설치돼 있지 않다 (서버만 띄운 실행)');
  // appwidget xml 이 잘못됐거나 receiver 가 빠졌으면 provider 자체가 목록에 안 뜬다.
  // 즉 이 한 줄이 "위젯을 홈에 추가할 수 있다"의 기기 쪽 증거다.
  const providers = adbShell('dumpsys appwidget | grep -i carcast || true');
  t.diagnostic(providers || '(carcast provider 없음)');
  assert.ok(providers.includes(PKG), 'dumpsys appwidget 에 CarCast provider 가 없다 — 위젯을 추가할 수 없다');
  assert.ok(/CarCastWidget/.test(providers), 'provider 이름이 CarCastWidget 이 아니다');
});

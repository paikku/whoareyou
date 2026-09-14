// 핫스팟 상태 표시(/api/hotspot)와 바탕화면 위젯이 **기기에 실제로 붙어 있는지**.
//
// 핫스팟을 **켜고 끄는 것은 우리 일이 아니다.** 폰이 uid 2000 에게 테더링 변경을 주지 않는다
// (`NO_CHANGE_TETHERING_PERMISSION`, 에뮬레이터와 S26U 둘 다 — verification-log 열린 질문 0).
// 그래서 남은 것은 읽기뿐이고, 이 파일이 지키는 것은 그 읽기가 정직한지다:
//   1. 엔드포인트가 있고, 늘 JSON 으로 답하고, **모르는 것을 안다고 하지 않는다**
//   2. /api/status 가 핫스팟 상태를 함께 싣는다 (앱과 위젯이 요청을 한 번 더 하지 않아도 되게)
//   3. 쓰기 요청은 이유를 붙여 거절한다 (옛 빌드의 앱이 물어볼 수 있다)
//   4. 위젯 provider 가 플랫폼에 등록됐다 — appwidget xml 이나 receiver 가 깨졌으면 여기서 걸린다
import { strict as assert } from 'node:assert';
import { test } from 'node:test';
import { adbAvailable, adbShell, api, status } from '../lib.mjs';

const PKG = 'com.carcast';

test('/api/hotspot 이 있고, 모르는 것은 모른다고 답한다', async (t) => {
  const h = await api('/api/hotspot');
  t.diagnostic(`on=${h.on} known=${h.known} via=${h.via} interfaces=${JSON.stringify(h.interfaces)}`);
  assert.equal(typeof h.on, 'boolean');
  assert.equal(typeof h.known, 'boolean');
  assert.ok(Array.isArray(h.interfaces), 'interfaces 가 배열이 아니다');
  // known=false 는 실패가 아니라 답이다. 거짓말만 실패다: 모르면서 "꺼져 있다"고 단정하면
  // 운전자는 이미 켜 둔 스위치를 다시 만지러 가고, 차는 그대로 못 붙는다.
  assert.ok(typeof h.via === 'string' && h.via.length > 0, 'via 가 비었다 — 무엇이 답했는지 알 수 없다');
});

test('/api/status 가 핫스팟 상태를 함께 싣는다', async () => {
  const s = await status();
  assert.ok(s.hotspot && typeof s.hotspot === 'object', '/api/status 에 hotspot 이 없다 — 앱이 매번 따로 물어야 한다');
  assert.equal(typeof s.hotspot.known, 'boolean');
  assert.equal(typeof s.hotspot.on, 'boolean');
});

test('켜라는 요청은 이유를 붙여 거절한다', async (t) => {
  const r = await api('/api/hotspot?on=1', { method: 'POST' });
  t.diagnostic(JSON.stringify(r));
  assert.equal(r.ok, false, '바꿀 수 없는데 바꿨다고 답했다');
  assert.ok(typeof r.error === 'string' && r.error.includes('read only'),
    '거절하면서 이유를 말하지 않았다 — 옛 빌드의 앱이 무엇이 잘못됐는지 알 수 없다');
  // 그리고 그 요청이 서버를 흔들지 않아야 한다.
  assert.equal((await status()).running, true, '핫스팟 요청 뒤 서버가 죽었다');
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

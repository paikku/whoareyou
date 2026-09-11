// M4: 가상 디스플레이 → H.264 인코더 → fMP4 조각이 실제로 흘러나오는가.
// 화면이 정지해 있어도 인코더는 REPEAT_PREVIOUS_FRAME_AFTER(100ms) 로 계속 뱉어야 한다 —
// 이 구간이 비는 것이 2026-09-10 실차에서 멈춤을 만든 원인이었다(docs/verification-log.md).
import { strict as assert } from 'node:assert';
import { test } from 'node:test';
import { collectVideo, ensureApp, serverLog, status } from '../lib.mjs';

const WINDOW_MS = 8_000;

// 이 파일이 먼저 돌 때(앱을 아직 아무것도 안 띄웠을 때)의 상태를 기록해 둔다.
// 빈 가상 디스플레이는 합성할 내용이 없어 인코더가 **한 장도** 내지 않는다 → 차는 init 세그먼트조차 못 받는다.
// 즉 차에서 페이지를 열고 ▶ 를 누르기 전까지는 아무것도 안 보이는 것이 정상 동작이다. 단언하지 않고 남긴다.
test('▶ 전, 빈 가상 디스플레이에서 차가 보는 것', async (t) => {
  const before = await status();
  if (before.appDisplay !== null) {
    t.diagnostic(`이미 앱이 떠 있어(appDisplay=${before.appDisplay}) 빈 화면 상태를 볼 수 없다`);
    return;
  }
  const packets = await collectVideo(3_000);
  t.diagnostic(`앱 없는 가상 디스플레이에서 3초 동안 받은 패킷: ${packets.length}개 (frames=${before.frames})`);
  if (packets.length === 0) {
    t.diagnostic('→ 인코더가 한 장도 내지 않는다. 차는 ▶ 를 누를 때까지 검은 화면을 본다.');
  }
});

test('앱이 떠 있으면 init 세그먼트와 키프레임이 오고, 그 뒤로 프레임이 끊기지 않는다', async () => {
  const before = await ensureApp();
  const packets = await collectVideo(WINDOW_MS);
  assert.ok(packets.length > 0, `${WINDOW_MS}ms 동안 한 패킷도 못 받았다. 서버 로그:\n${await serverLog()}`);
  assert.equal(packets[0].type, 0, '첫 패킷은 init 세그먼트(type 0)여야 한다');
  assert.ok(packets.some((p) => p.type === 2), '키프레임(type 2)이 하나도 없다 — 디코더가 시작할 수 없다');
  const media = packets.filter((p) => p.type !== 0);
  // 에뮬레이터의 소프트웨어 인코더는 실기기보다 느리다. 여기서 보려는 것은 프레임률이 아니라
  // "정지 화면에서도 타임라인이 이어지는가"이므로 기준을 낮게 둔다. fps 수치는 tests/e2e 의 stream.spec.ts.
  assert.ok(media.length >= 5, `${WINDOW_MS}ms 동안 미디어 조각 ${media.length}개뿐`);
  const ptsSorted = media.every((p, i) => i === 0 || p.ptsUs >= media[i - 1].ptsUs);
  assert.ok(ptsSorted, 'pts 가 뒤로 갔다');
  const gaps = media.slice(1).map((p, i) => p.at - media[i].at);
  const worst = Math.max(...gaps);
  assert.ok(worst < 3_000, `프레임 사이가 ${worst}ms 벌어졌다 — 정지 화면에서 인코더가 쉬고 있다`);

  const after = await status();
  assert.ok(after.frames > before.frames, `frames 가 늘지 않았다 (${before.frames} → ${after.frames})`);
});

// 런타임 인코더 재설정(/api/encoder)과 지연 측정 액티비티: 앱을 그대로 둔 채 인코더만 갈아끼우고,
// 크기를 바꾸면 가상 디스플레이도 따라 바뀌며, 선택은 파일로 남는다. 측정 액티비티는 차 화면에 뜨되
// 앱 히스토리(차 홈의 순서)에는 들어가지 않는다.
import { strict as assert } from 'node:assert';
import { test } from 'node:test';
import { api, control, serverLog, sleep, status, waitFor, wiggle } from '../lib.mjs';

test('/api/encoder 로 fps 를 바꾸면 인코더가 다시 뜨고 프레임이 이어진다', async (t) => {
  const s0 = await status();
  if (s0.source !== 'display') { t.skip('가상 디스플레이가 없다'); return; }
  const before = await api('/api/encoder');
  assert.equal(before.width, s0.width);
  assert.equal(before.fps, s0.maxFps);

  const r = await api('/api/encoder?fps=60', { method: 'POST' });
  assert.equal(r.ok, true, JSON.stringify(r));
  assert.equal(r.fps, 60);
  assert.equal(r.width, before.width);
  const s1 = await status();
  assert.equal(s1.maxFps, 60);
  assert.equal(s1.encoderRestarts, (s0.encoderRestarts ?? 0) + 1);
  assert.equal(s1.source, 'display');

  // 새 인코더가 실제로 프레임을 낸다(화면을 흔들어야 나온다).
  const stop = await wiggle();
  try {
    await waitFor(async () => {
      const s = await status();
      return s.frames > s1.frames + 5 ? s : null;
    }, { timeoutMs: 15_000, what: '재설정 뒤 프레임 증가' }).catch(async (e) => {
      throw new Error(`${e.message}\n서버 로그:\n${await serverLog()}`);
    });
  } finally {
    stop();
  }
  // 2초 안의 재요청은 거부된다.
  const tooSoon = await api('/api/encoder?fps=30', { method: 'POST' });
  assert.equal(tooSoon.ok, false);
  await sleep(2100);
  const back = await api(`/api/encoder?fps=${before.fps}`, { method: 'POST' });
  assert.equal(back.ok, true, JSON.stringify(back));
});

test('/api/encoder 로 크기를 바꾸면 가상 디스플레이도 따라 바뀐다', async (t) => {
  const s0 = await status();
  if (s0.source !== 'display') { t.skip('가상 디스플레이가 없다'); return; }
  await sleep(2100);
  const r = await api('/api/encoder?width=960&height=540', { method: 'POST' });
  assert.equal(r.ok, true, JSON.stringify(r));
  assert.equal(r.width, 960);
  assert.equal(r.height, 540);
  assert.equal(r.dpi, 120, '밀도는 높이에 비례해 720p 의 160 에서 줄어든다');
  const s1 = await status();
  assert.equal(s1.width, 960);
  assert.equal(s1.height, 540);
  await sleep(2100);
  const back = await api(`/api/encoder?width=${s0.width}&height=${s0.height}`, { method: 'POST' });
  assert.equal(back.ok, true, JSON.stringify(back));
  assert.equal((await status()).width, s0.width);
  // 잘못된 값은 거부되고 상태는 그대로다.
  await sleep(2100);
  const bad = await api('/api/encoder?width=100&height=100', { method: 'POST' });
  assert.equal(bad.ok, false);
  assert.equal((await status()).width, s0.width);
});

test('지연 측정 액티비티가 차 화면에 뜨고, 앱 히스토리에는 남지 않는다', async (t) => {
  const s0 = await status();
  if (s0.source !== 'display') { t.skip('가상 디스플레이가 없다'); return; }
  const r = await api('/api/app?name=com.carcast/.ui.LatencyProbeActivity', { method: 'POST' });
  assert.equal(r.ok, true, JSON.stringify(r));
  assert.equal(r.package, 'com.carcast');
  const tasks = await waitFor(async () => {
    const x = await api('/api/tasks');
    return x.tasks?.some((k) => k.package === 'com.carcast' && k.display === s0.displayId) ? x : null;
  }, { timeoutMs: 10_000, what: '측정 액티비티가 차 화면에' });
  assert.ok(tasks.tasks.length >= 1);
  const apps = await api('/api/apps');
  const self = apps.find?.((a) => a.package === 'com.carcast');
  assert.ok(!self || self.lastUsed === undefined, 'CarCast 자신이 히스토리에 올랐다');
  // 탭하면 화면이 뒤집힌다 — 여기서는 주입이 실패하지 않는 것까지만 본다(밝기는 브라우저 쪽 몫).
  const c = await control();
  try {
    const before = (await status()).injected;
    await c.tap(0.5, 0.5);
    await waitFor(async () => ((await status()).injected > before ? true : null), { timeoutMs: 5000, what: '탭 주입' });
    assert.equal((await status()).injectFailed, s0.injectFailed ?? 0);
    c.key(0, 4); c.key(1, 4); // BACK: 측정 액티비티에서 나간다
    await sleep(800);
  } finally {
    c.close();
  }
});

// 프로파일: Baseline 은 차의 WASM 디코더(h264bsd)가 그것밖에 못 읽어서 있는 제약이다. 하드웨어 디코더가
// 있는 차(실차 report #67 에서 확인)는 High 도 읽고, High 는 같은 화질에 비트레이트를 덜 쓴다. 그래서
// 차의 렌더러가 webcodecs 면 스스로 `?profile=high` 를 부른다 — 그 손잡이가 실제로 도는지를 여기서 본다.
// SPS 가 최종 답이므로 요청이 아니라 `codec` 문자열(avc1.<profile>…)을 본다.
test('인코더 프로파일을 High 로 올렸다가 Baseline 으로 되돌릴 수 있다', async () => {
  const before = await api('/api/encoder');
  try {
    const high = await api('/api/encoder?profile=high', { method: 'POST' });
    assert.equal(high.ok, true, `profile=high 실패: ${high.error}`);
    assert.equal(high.profile, 'high');
    // avc1.64.... = High(0x64). 벤더가 거부하면 42 로 남는데, 그것도 기록할 값이다.
    if (!String(high.codec ?? '').startsWith('avc1.64')) {
      console.log(`주의: High 를 요청했으나 SPS 는 ${high.codec} — 이 기기 인코더가 거부했다`);
    }
  } finally {
    const back = await api('/api/encoder?profile=baseline', { method: 'POST' });
    assert.equal(back.profile, 'baseline', '되돌리기 실패 — 다음 검사가 Baseline 을 기대한다');
    assert.ok(String(back.codec ?? '').startsWith('avc1.42'), `되돌린 뒤 SPS 가 ${back.codec}`);
    assert.ok((await api('/api/encoder')).width === before.width, '되돌리면서 크기가 바뀌었다');
  }
});

// 큰 프리셋에서 Baseline 이 살아남는가 — H264Level 이 생긴 이유.
//
// 인코더는 Baseline 을 요청할 때 레벨도 같이 말해야 하는데(레벨 없는 프로파일을 무시하는 벤더가 있다),
// 그 레벨이 크기·fps 와 무관하게 3.2 로 고정돼 있었다. 3.2 는 프레임당 5120 매크로블록·초당 216000 까지라
// 900p(5700)도 1080p30(244800/s)도 넘는다.
//
// S26U 의 `c2.qti.avc.encoder` 는 그것을 **무시하고** 스스로 4.2 를 골랐다(report #61: 1080p 에서 SPS 가
// `avc1.42C02A`). 즉 이 기기에서는 사고가 나지 않았다. 거부하는 인코더에서는 다르다 — configure 가 거부되면
// H264Encoder 는 벤더 기본값(우리 기기 전부 High)으로 물러나고, 평문 경로의 차는 WASM 디코더라 High 를
// 읽지 못한다. 증상은 "화질을 올렸더니 화면이 멈춤"이고 로그에는 인코더가 다시 떴다고만 남는다.
//
// 그래서 여기서 보는 것은 속도가 아니라 **SPS 가 여전히 Baseline 인가** 하나다(avc1.42…) — 기기가 바뀌면
// 이 검사가 그 차이를 먼저 잡는다.
test('큰 프리셋에서도 Baseline 요청이 살아남는다', async (t) => {
  const s0 = await status();
  if (s0.source !== 'display') { t.skip('가상 디스플레이가 없다'); return; }
  const before = await api('/api/encoder');
  try {
    for (const [w, h, fps] of [[1600, 900, 60], [1920, 1080, 30], [1920, 1080, 60]]) {
      await sleep(2100); // /api/encoder 는 2초에 한 번만 받는다
      const r = await api(`/api/encoder?width=${w}&height=${h}&fps=${fps}&profile=baseline`, { method: 'POST' });
      assert.equal(r.ok, true, `${w}x${h}@${fps} 거부: ${r.error}`);
      assert.equal(r.width, w);
      assert.equal(r.fps, fps);
      assert.ok(
        String(r.codec ?? '').startsWith('avc1.42'),
        `${w}x${h}@${fps} 에서 SPS 가 ${r.codec} — Baseline 요청이 거부돼 벤더 기본값으로 물러났다`,
      );
    }
  } finally {
    await sleep(2100);
    const back = await api(`/api/encoder?width=${before.width}&height=${before.height}&fps=${before.fps}&bitrate=${before.bitrate}&profile=baseline`, { method: 'POST' });
    assert.equal(back.ok, true, JSON.stringify(back));
  }
});

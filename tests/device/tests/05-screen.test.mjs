// M7: 폰 화면과 차 화면의 전원. 📵 하나가 아니라 손잡이가 여럿이고, 여기서 보는 것은
// **"그 손잡이가 진짜로 걸렸나"** 다 — 눈으로 봐야 아는 "패널이 어두워졌나"는 실기기(B) 몫이다.
//
// 이 파일이 지키는 것:
//   1. 가상 디스플레이가 **요청한 플래그를 실제로 받았는지** (받았다고 믿는 것과 받은 것은 다르다)
//   2. 깨우기 신호(userActivity)가 **먹히는지** — 안 먹히면 조용히 무시되므로 호출 횟수는 증거가 아니다
//   3. 📵 요청과 상태가 맞는지, 그동안 영상이 계속 나오는지
//   4. 화면을 끈 채 **1분을 버티는지** (검은 면은 10초쯤 뒤에 덮인다 — 3초만 보면 못 본다)
//   5. 유휴 타이머 손잡이(screen_off_timeout)가 걸렸는지
//   6. 충전 중일 때 stay_on 손잡이가 실제로 발동하는지 (가짜 충전으로)
//   7. 폰이 잠들었을 때 차가 되살아나는지, 그리고 그때 차 화면 그룹이 어떤 상태였는지
//   8. **잠금화면이 떠 있는 동안에도** 차 화면이 덮이지 않는지 (§4 가설의 결정적 실험)
import { strict as assert } from 'node:assert';
import { test } from 'node:test';
import { adb, adbAvailable, adbShell, api, collectVideo, ensureApp, sleep, status, wiggle } from '../lib.mjs';

/** android.view.Display.FLAG_TRUSTED (@hide 라 값만 옮겨 적는다). 나머지 둘은 서버가 풀어서 준다. */
const FLAG_TRUSTED = 1 << 7;

const isDisplaySource = async () => (await status()).source === 'display';

test('가상 디스플레이가 요청한 플래그를 실제로 받았다', async (t) => {
  const s = await status();
  if (s.source !== 'display') return t.skip('클립 모드 — 가상 디스플레이가 없다');
  assert.equal(typeof s.displayFlags, 'number', '/api/status 에 displayFlags 가 없다 (옛 빌드)');
  t.diagnostic(`displayFlags=0x${(s.displayFlags >>> 0).toString(16)}`);
  // TRUSTED 가 없으면 앱이 이 화면에서 제대로 안 돈다. OWN_DISPLAY_GROUP 이 없으면 폰이 잠들 때
  // 같이 끌려간다. ALWAYS_UNLOCKED 가 없으면 폰에 잠금화면이 뜨는 순간 이 화면이 덮인다
  // (AOSP RootWindowContainer.handleNotObscuredLocked). 셋 다 **요청만으로는 안 붙는다.**
  assert.ok(s.displayFlags & FLAG_TRUSTED, 'TRUSTED 플래그가 빠졌다');
  assert.equal(s.displayOwnGroup, true, 'OWN_DISPLAY_GROUP 이 빠졌다 — 폰이 자면 차 화면도 끌려간다');
  assert.equal(s.displayAlwaysUnlocked, true,
    'ALWAYS_UNLOCKED 가 빠졌다 — 폰에 잠금화면이 뜨면 차 화면이 검은 면으로 덮인다');
});

test('깨우기 신호가 실제로 먹힌다 (keptActive 는 호출 횟수일 뿐이다)', async (t) => {
  if (!(await isDisplaySource())) return t.skip('클립 모드');
  // keep-active 는 차가 보고 있을 때만 돈다. 영상 소켓 하나가 곧 "차가 보고 있다"이다.
  const stop = await wiggle();
  try {
    const video = collectVideo(12_000);
    await sleep(12_000);
    await video;
    const s = await status();
    t.diagnostic(`keptActive=${s.keptActive} keepActiveEffective=${s.keepActiveEffective}`);
    assert.ok(s.keptActive > 0, '차가 보고 있는데 keep-active 가 한 번도 안 돌았다');
    // 서버가 logcat 에서 직접 읽어 둔 답. null 이면 아직 못 읽은 것이고, false 면 권한이 없어
    // **조용히 버려지고 있다**는 뜻이다 — 이 경우 keptActive 숫자는 전부 거짓이다.
    assert.notEqual(s.keepActiveEffective, false,
      'userActivity 가 PowerManagerService 에 의해 무시되고 있다 (DEVICE_POWER/USER_ACTIVITY 없음)');
    if (adbAvailable) {
      // 같은 것을 바깥에서 한 번 더 확인한다: 서버의 판정 자체가 틀렸을 수 있다.
      const warn = adb('logcat', '-d', '-t', '600', 'PowerManagerService:W', '*:S');
      assert.ok(!warn.includes('Ignoring call to PowerManager.userActivity'),
        `logcat 이 userActivity 무시를 말한다:\n${warn.split('\n').filter((l) => l.includes('Ignoring')).join('\n')}`);
    }
    if (s.keepActiveEffective === null) t.diagnostic('서버가 아직 판정을 못 읽었다 (logcat 접근 실패)');
  } finally {
    stop();
  }
});

test('화면 OFF 요청이 상태에 반영되고, 그동안에도 영상은 계속 나온다', async (t) => {
  const off = await api('/api/screen?on=0', { method: 'POST' });
  assert.equal(typeof off.ok, 'boolean');
  if (!off.ok) {
    t.diagnostic('이 기기에서는 디스플레이 전원 전환이 안 된다 — 실기기(M7)에서 확인할 것');
  } else {
    assert.equal(off.screenOn, false);
    const s = await status();
    assert.equal(s.screenOn, false);
    t.diagnostic(`panelOffMethod=${s.panelOffMethod} panelOffFailures=${s.panelOffFailures}`);
    const packets = await collectVideo(3_000);
    assert.ok(packets.filter((p) => p.type !== 0).length > 0, '화면을 끄자 가상 디스플레이까지 멈췄다');
  }
  const on = await api('/api/screen?on=1', { method: 'POST' });
  if (off.ok) assert.equal(on.screenOn, true, '다시 켜지지 않았다');
});

// scrcpy#6787 의 증상은 **물리 화면이 꺼진 뒤 10초쯤**에 온다: 가상 디스플레이 크기의 검은 면이 덮이고
// 그 아래에서 앱은 계속 그려진다. 화면이 안 변하니 인코더가 멈추고 차에는 얼어붙은 그림만 남는다.
// 그래서 흔들면서(=픽셀이 계속 바뀌게 하면서) 1분을 보고, **마지막 20초에도 프레임이 오는지**를 묻는다.
test('화면을 끈 채 1분을 버틴다 — 검은 면이 덮지 않는다', { timeout: 180_000 }, async (t) => {
  if (!(await isDisplaySource())) return t.skip('클립 모드');
  await ensureApp();
  const off = await api('/api/screen?on=0', { method: 'POST' });
  if (!off.ok) return t.skip('이 기기에서는 디스플레이 전원 전환이 안 된다');
  const stop = await wiggle();
  try {
    const windows = [];
    for (let i = 0; i < 3; i++) {
      const packets = await collectVideo(20_000);
      windows.push(packets.filter((p) => p.type !== 0).length);
    }
    const s = await status();
    t.diagnostic(`20초 구간별 프레임 수: ${windows.join(' / ')}`);
    t.diagnostic(`screenOn=${s.screenOn} interactive=${s.interactive} vdInteractive=${s.vdInteractive}`
      + ` keptActive=${s.keptActive} keepActiveEffective=${s.keepActiveEffective} panelState=${s.panelState}`);
    assert.ok(windows[windows.length - 1] > 0,
      `화면을 끄고 40~60초 사이에 프레임이 끊겼다 (구간별 ${windows.join('/')}) — 가상 디스플레이가 덮인 것으로 보인다`);
  } finally {
    stop();
    await api('/api/screen?on=1', { method: 'POST' });
  }
});

test('유휴 타이머 손잡이가 걸려 있다 (충전과 무관하게 듣는 유일한 것)', async (t) => {
  const s = await status();
  if (!s.screenOffTimeout) return t.skip('screen_off_timeout 없이 기동된 서버');
  t.diagnostic(`screen_off_timeout=${s.screenOffTimeout} (원래 ${s.screenOffTimeoutWas})`);
  if (!adbAvailable) return t.skip('adb 없음');
  assert.equal(adbShell('settings get system screen_off_timeout'), String(s.screenOffTimeout),
    '서버는 걸었다고 하는데 기기의 설정값이 다르다');
});

test('충전 중이면 stay_on 손잡이가 실제로 발동한다', async (t) => {
  if (!adbAvailable) return t.skip('adb 없음');
  const stayOn = adbShell('settings get global stay_on_while_plugged_in');
  assert.equal(stayOn, '7', 'stay_on_while_plugged_in 이 안 걸려 있다 (stay_awake=false 로 뜬 서버?)');
  // 에뮬레이터는 충전 중이 아니라서 이 손잡이가 평소에는 **검사되지 않는다**. 충전 중인 척해서
  // PowerManagerService 가 실제로 mStayOn 을 올리는지까지 본다 (커뮤니티가 쓰는 방법).
  try {
    adbShell('dumpsys battery set ac 1');
    let on = false;
    for (let i = 0; i < 10 && !on; i++) {
      await sleep(500);
      on = /mStayOn=true/.test(adbShell('dumpsys power | grep -m1 mStayOn || true'));
    }
    if (!on) {
      t.diagnostic(`이 기기의 dumpsys power 에 mStayOn=true 가 안 뜬다: ${adbShell('dumpsys power | grep -m1 mStayOn || echo none')}`);
    }
    assert.ok(on, '충전 중인 척했는데도 stay_on 이 발동하지 않았다');
  } finally {
    adbShell('dumpsys battery reset');
  }
});

test('폰이 잠들면 차 화면이 되살아난다', { timeout: 120_000 }, async (t) => {
  if (!(await isDisplaySource())) return t.skip('클립 모드');
  if (!adbAvailable) return t.skip('adb 없음');
  await ensureApp();
  const stop = await wiggle();
  // 되살리기는 **차가 보고 있을 때만** 돈다. 그 판정은 영상 소켓 수이므로, 흔드는 것만으로는 모자라다.
  const video = collectVideo(45_000);
  try {
    await sleep(1000);
    const before = await status();
    adbShell('input keyevent 223'); // KEYCODE_SLEEP: 전원 버튼을 누른 것과 같은 길
    let after = before;
    for (let i = 0; i < 30; i++) {
      await sleep(500);
      after = await status();
      if (after.sleepRecoveries > before.sleepRecoveries) break;
    }
    t.diagnostic(`sleepRecoveries ${before.sleepRecoveries}→${after.sleepRecoveries}`
      + ` vdWakes=${after.vdWakes} lastSleepVdInteractive=${after.lastSleepVdInteractive}`
      + ` interactive=${after.interactive} screenOn=${after.screenOn} vdInteractive=${after.vdInteractive}`);
    assert.ok(after.sleepRecoveries > before.sleepRecoveries,
      '차가 보고 있는데 폰이 잠든 것을 되살리지 않았다');
    // 되살렸다면 차 화면 쪽은 살아 있어야 한다: 프레임이 다시 온다.
    const packets = await collectVideo(6_000);
    assert.ok(packets.filter((p) => p.type !== 0).length > 0, '되살렸다는데 영상이 안 온다');
  } finally {
    stop();
    await video.catch(() => {});
    adbShell('input keyevent 224');
    adbShell('wm dismiss-keyguard');
    await api('/api/screen?on=1', { method: 'POST' }).catch(() => {});
  }
});

/** `dumpsys window` 가 말하는 잠금화면 상태. 못 읽으면 null. */
function keyguardShowing() {
  for (const cmd of ['dumpsys window policy', 'dumpsys window']) {
    let out = '';
    try { out = adbShell(`${cmd} 2>/dev/null | grep -i keyguard | head -8 || true`); } catch { continue; }
    if (!out) continue;
    // 무엇이 근거였는지를 로그에 남긴다. "떠 있다고 했는데 실은 아니었다"가 이 검사를 통째로
    // 무의미하게 만드는 유일한 길이라, 근거가 되는 그 토큰을 그대로 적는다.
    const hit = out.match(/\S*(?:isKeyguardShowing|KeyguardShowing|IsShowing|showing)=(?:true|false)/i);
    if (hit) return { on: /=true$/i.test(hit[0]), raw: hit[0], all: out };
  }
  return { on: null, raw: '(잠금화면 상태를 읽지 못했다)' };
}

// §4 의 가설: 폰에 잠금화면이 뜨면 안드로이드가 **보조 디스플레이의 앱 내용을 가린다**
// (AOSP RootWindowContainer.handleNotObscuredLocked). 면제는 하나뿐 — 그 디스플레이가
// FLAG_ALWAYS_UNLOCKED 를 가진 경우다. 우리는 그 플래그를 받았으니(위 검사) 면제여야 한다.
//
// 지금까지 이 질문은 하네스에서 **물을 수조차 없었다**: vphone.sh 도, 생애주기의 '폰을 깨운다' 도
// `wm dismiss-keyguard` 를 부르고, 에뮬레이터에는 보안 잠금이 아예 없다. 그래서 여기서는 일부러
// PIN 을 걸고, 잠금화면이 **실제로 떠 있는지 먼저 확인한 뒤에** 프레임을 센다. 안 떴으면 실패시킨다 —
// 재현되지 않은 검사를 통과로 세는 것이 지금까지의 함정이었다.
test('잠금화면이 떠 있어도 차 화면이 덮이지 않는다', { timeout: 240_000 }, async (t) => {
  if (!(await isDisplaySource())) return t.skip('클립 모드');
  if (!adbAvailable) return t.skip('adb 없음');
  const s0 = await status();
  if (s0.displayAlwaysUnlocked !== true) {
    t.diagnostic('이 디스플레이에는 ALWAYS_UNLOCKED 가 없다 — 덮이는 것이 정상이다');
  }
  await ensureApp();
  let pinSet = false;
  const stop = await wiggle();
  const keep = collectVideo(120_000); // 차가 보고 있다는 신호를 검사 내내 유지한다
  try {
    try {
      adbShell('locksettings set-pin 1234');
      pinSet = true;
    } catch (e) {
      return t.skip(`이 기기에 PIN 을 걸 수 없다 (이미 잠금이 있거나 막혀 있다): ${e.message}`);
    }
    // 운전자가 실제로 하는 것: 전원 버튼. 기기가 잠들면서 잠금화면이 걸리고, 우리 되살리기가
    // 깨워서 📵 상태로 바꾼다 — 그 뒤에도 잠금화면은 떠 있다.
    adbShell('input keyevent 223');
    await sleep(6000);
    const kg = keyguardShowing();
    t.diagnostic(`잠금화면: ${kg.on} — 근거 \`${kg.raw}\``);
    t.diagnostic(`(dumpsys 원문) ${String(kg.all ?? '').replace(/\s+/g, ' ').slice(0, 300)}`);
    if (kg.on === null) return t.skip('잠금화면 상태를 읽지 못해 이 기기에서는 판정할 수 없다');
    assert.equal(kg.on, true, 'PIN 을 걸고 재웠는데 잠금화면이 뜨지 않았다 — 이 검사는 아무것도 증명하지 못한다');

    const windows = [];
    for (let i = 0; i < 3; i++) {
      const packets = await collectVideo(20_000);
      windows.push(packets.filter((p) => p.type !== 0).length);
    }
    const s = await status();
    t.diagnostic(`잠금화면이 뜬 채 20초 구간별 프레임 수: ${windows.join(' / ')}`);
    t.diagnostic(`screenOn=${s.screenOn} interactive=${s.interactive} vdInteractive=${s.vdInteractive}`
      + ` sleepRecoveries=${s.sleepRecoveries} vdWakes=${s.vdWakes}`);
    assert.ok(windows[windows.length - 1] > 0,
      `잠금화면이 뜬 뒤 40~60초 사이에 프레임이 끊겼다 (구간별 ${windows.join('/')})`
      + ' — §4 가설대로 보조 디스플레이가 가려진 것이다. ALWAYS_UNLOCKED 면제가 듣지 않는다는 뜻');
  } finally {
    stop();
    await keep.catch(() => {});
    // 여기서 PIN 을 못 지우면 **이 실행의 나머지 검사가 전부 잠긴 폰 위에서 돈다.** 몇 번 더 시도한다.
    for (let i = 0; pinSet && i < 3; i++) {
      try { adbShell('locksettings clear --old 1234'); pinSet = false; } catch { await sleep(1000); }
    }
    if (pinSet) t.diagnostic('PIN 을 지우지 못했다 — 뒤따르는 검사가 잠긴 폰 위에서 돈다');
    adbShell('input keyevent 224');
    adbShell('wm dismiss-keyguard');
    await api('/api/screen?on=1', { method: 'POST' }).catch(() => {});
  }
});

// 폴백은 **한 번도 돌아 본 적 없으면 폴백이 아니다.** 평소에는 power-mode 가 성공하므로 나머지 둘은
// 영영 안 돈다. `?via=` 로 하나씩 강제해서, 이 기기에서 무엇이 실제로 되는지 기록한다.
// (되는 것이 하나뿐이어도 실패가 아니다 — 답을 아는 것이 목적이다. power-mode 가 죽는 것만 실패다.)
test('패널을 끄는 세 가지 길 중 이 기기에서 무엇이 되나', { timeout: 120_000 }, async (t) => {
  if (!(await isDisplaySource())) return t.skip('클립 모드');
  const results = {};
  try {
    for (const via of ['power-mode', 'cmd-display', 'brightness']) {
      const off = await api(`/api/screen?on=0&via=${via}`, { method: 'POST' });
      let frames = 0;
      if (off.ok) frames = (await collectVideo(3_000)).filter((p) => p.type !== 0).length;
      await api(`/api/screen?on=1&via=${via}`, { method: 'POST' }).catch(() => {});
      await api('/api/screen?on=1', { method: 'POST' }).catch(() => {});
      results[via] = { ok: off.ok, via: off.via, frames };
      t.diagnostic(`${via}: ok=${off.ok} (서버가 쓴 길=${off.via}) 끈 동안 프레임=${frames}`);
    }
    assert.equal(results['power-mode'].ok, true, '기본 경로(SurfaceControl)가 이 기기에서 안 된다');
    for (const [via, r] of Object.entries(results)) {
      if (r.ok) assert.ok(r.frames > 0, `${via} 로 껐더니 가상 디스플레이까지 멈췄다`);
    }
    const working = Object.entries(results).filter(([, r]) => r.ok).map(([v]) => v);
    t.diagnostic(`이 기기에서 되는 길: ${working.join(', ')}`);
  } finally {
    await api('/api/screen?on=1', { method: 'POST' }).catch(() => {});
  }
});

// 셸 프로세스는 ACTION_SCREEN_OFF 를 못 받으므로 서버는 **볼 때만** 안다. 차가 보고 있는 동안에는
// 250ms 마다 보게 했고(전에는 1초), 그 차이가 곧 운전자가 멈춘 그림을 보는 시간이다. 숫자로 지킨다.
test('폰이 잠든 것을 얼마나 빨리 알아채나', { timeout: 90_000 }, async (t) => {
  if (!(await isDisplaySource())) return t.skip('클립 모드');
  if (!adbAvailable) return t.skip('adb 없음');
  await ensureApp();
  const stop = await wiggle();
  const keep = collectVideo(60_000); // "차가 보고 있다" = 250ms 주기가 켜지는 조건
  try {
    await sleep(1000);
    const before = await status();
    const t0 = Date.now();
    adbShell('input keyevent 223');
    let noticedMs = null;
    while (Date.now() - t0 < 10_000) {
      const s = await status();
      if (s.lastPowerEvent !== before.lastPowerEvent || s.sleepRecoveries > before.sleepRecoveries) {
        noticedMs = Date.now() - t0;
        break;
      }
      await sleep(50);
    }
    t.diagnostic(`잠든 것을 알아채기까지: ${noticedMs}ms`);
    assert.ok(noticedMs !== null, '10초가 지나도 폰이 잠든 것을 알아채지 못했다');
    // 감시 주기 250ms + 상태를 묻는 왕복. 1초를 넘으면 옛 주기(1000ms)로 돌아간 것이다.
    assert.ok(noticedMs < 1500, `알아채는 데 ${noticedMs}ms 걸렸다 — 감시 주기가 촘촘하지 않다`);
  } finally {
    stop();
    await keep.catch(() => {});
    adbShell('input keyevent 224');
    adbShell('wm dismiss-keyguard');
    await api('/api/screen?on=1', { method: 'POST' }).catch(() => {});
  }
});

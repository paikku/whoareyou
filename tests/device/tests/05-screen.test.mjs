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

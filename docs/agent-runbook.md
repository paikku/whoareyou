# 작업 지침: 이런 요청이 오면 어디서 어떻게

"앱 전환이 이상하다", "전원 버튼 누르면 차가 얼어붙는다", "폰과 웹 사이가 매끄럽지 않다" —
이런 요청을 받았을 때 **실기기·실차 없이** 어디까지 스스로 확인하고 고칠 수 있는지, 그 절차만 적는다.
무엇을 어디서 테스트하는지의 전체 지도는 [testing-guide.md](testing-guide.md), 지금까지 확인된 사실은
[verification-log.md](verification-log.md)에 있다.

## 0. 전제: 실차는 마지막 확인처다

이 프로젝트의 원칙은 **"C(실차)에서 처음 발견되는 버그가 없어야 한다"** 이다. 그래서 작업 순서는 언제나
위에서 아래로 내려간다. 아래로 내려갈수록 느리고 비싸며, 아래 단계에서만 잡히는 것은 따로 정해져 있다.

| | 자리 | 무엇을 |
|---|---|---|
| A | 개발 PC / CI | 웹 클라이언트, 먹서, 프로토콜, 재연결 — 가짜 폰(`tools/fake-phone`) 상대 |
| **A+** | **가상 폰(에뮬레이터)** | **폰 쪽 코드 전부: 가상 디스플레이·앱 실행·앱 충돌·입력 주입·화면 전원·생애주기** |
| B | 실기기 + 노트북 | VPN 주소 배달, 핫스팟, 무선 디버깅 페어링, One UI 전용 동작 |
| C | 실차 | 테슬라 브라우저 정책, 차 Wi-Fi, 후진 기어·탭 폐기 |

**A+ 가 이 문서의 핵심이다.** 앱 전환과 전원 버튼은 예전에는 B/C 에서만 볼 수 있었지만, 지금은 여기서 본다.

## 1. 환경 세우기

```bash
npm ci
cd tests/e2e && npm run install-chrome && cd ../..   # 차와 같은 엔진(Chrome for Testing 148)
tools/virtual-phone/vphone.sh sdk                    # 최초 1회, 약 2GB
./gradlew :app:assembleDebug
tools/virtual-phone/vphone.sh up                     # → http://127.0.0.1:3333

export BASE_URL=http://127.0.0.1:3333
export CHROME_PATH=$(find tests/e2e/.cache -name chrome -type f | head -1)
```

리눅스 + `/dev/kvm` 이 필요하다. 없으면(맥/윈도, KVM 없는 컨테이너) **GitHub Actions 의 `emulator` 워크플로가
같은 순서를 돈다** — 푸시하고 로그를 읽는 것이 유일한 길이다. 이 저장소의 원격 세션이 그 경우다.

## 2. 무엇부터 돌리나

```bash
npm run device        # 기기 검사: source=display, 앱 실행, M4-b 충돌, 입력 주입, 화면 전원, 킬 스위치
npm run lifecycle     # 폰↔웹 생애주기 시나리오 (앱 전환 / 웹 / 폰 / 전원 버튼 × 📵)
EXPLORE_STEPS=40 npm run explore   # 무작위 순서로 스스로 돌아다니기
npm run e2e           # 가짜 폰 상대 웹 회귀 (BASE_URL 없이)
```

보고서는 `out/lifecycle/report.md`·`explore.md`. 표의 열이 곧 "쾌적한가"의 정의다:
**차 화면 복구 시간 / 재접속 횟수 / 앱 위치 / 폰 화면 / fps / 어긋남**.

## 3. 이 두 가지를 고칠 때 보는 곳

### 앱 전환 (폰과 차가 같은 앱을 두고 다툴 때)

안드로이드는 앱마다 task 를 하나만 둔다. `am start --display N` 은 **복사가 아니라 이동**이다. 그래서
폰에서 쓰던 앱을 차에서 띄우면 폰에서 사라지고, 폰 런처에서 다시 누르면 차 화면이 빈다.

- 서버: `DisplayVideoSource.startApp`(restart=auto 로 강제 종료 후 새로), `TaskList`(`am stack list` 파서),
  앱 감시자(빈 화면이면 1초, 아니면 5초 간격)
- 차: `/api/status.appOnPhone` → 상태 패널 `app-on-phone` → **"차로 가져오기"** 한 번
- 검사: `tests/device/tests/03-app.test.mjs`, `tests/e2e/tests/app-conflict.spec.ts`,
  `npm run lifecycle` 의 "앱 전환" 시나리오

**빈 가상 디스플레이는 인코더가 한 장도 내지 않는다**(합성할 내용이 없다). 그래서 "앱이 없다"와
"폰이 가져갔다"는 화면상 똑같이 멈춘 그림이다. 그 둘을 말로 갈라 주는 것이 상태 패널이다.

### 전원 버튼 · 화면 꺼짐 (폰 화면과 차 화면이 서로를 끌고 갈 때)

폰의 전원 버튼과 차의 📵 는 **같은 패널**을 움직인다. 차는 그 버튼을 가로챌 수 없으므로, 서버는
자기 장부(`forcedOff`)를 진실로 믿으면 안 된다.

- 서버: `ScreenPower` — `SurfaceControl.setDisplayPowerMode`(scrcpy 방식, `requestDisplayPower` 아님),
  1초 감시자가 `dumpsys display` 의 `mScreenState` 와 대조해 어긋나면 **기기 쪽을 믿고 장부를 버린다**
- 상태: `/api/status` 의 `screenOn` `interactive` `forcedOff` `panelState` `powerReconciled` `lastPowerEvent`
- 검사: `tests/device/tests/05-screen.test.mjs`, `npm run lifecycle` 의 "전원 버튼 × 📵" 시나리오

되돌리기만 하고 **되눌러 주지 않는다.** 사용자가 끈 화면을 서버가 도로 켜면 그때부터는 싸움이다.
단 하나의 예외가 차가 보고 있는 동안의 잠듦이다(아래).

**알아 둘 것 — 이것은 상류에도 열려 있는 문제다.** 물리 화면이 꺼지면 안드로이드는 가상 디스플레이도
유휴로 보고 약 10초 뒤 검은 면으로 덮는다([scrcpy#6787](https://github.com/Genymobile/scrcpy/issues/6787),
조사는 [prior-art.md](prior-art.md)). 우리가 가진 손잡이는 셋뿐이고 전부 `/api/status` 에 수치로 나온다:

| 손잡이 | 끄는 법 | 상태 필드 |
|---|---|---|
| 충전 중 잠들지 않기 (`stay_on_while_plugged_in`) | `stay_awake=false` | — |
| **유휴 타이머 밀어 두기 (`screen_off_timeout`)** — 충전과 무관하게 듣는 유일한 것 | `screen_off_timeout=` 를 빼면 안 건드린다 | `screenOffTimeout`, `screenOffTimeoutWas` |
| 5초마다 가상 디스플레이에 `userActivity` (scrcpy `--keep-active`) | `keep_active=false` | `keptActive`, **`keepActiveEffective`** |
| 위가 무시당하는 ROM 에서 VD 에 WAKEUP (Castla) — **기본 꺼짐** | `keep_active_fallback=true` 로 켠다 | `keptActiveFallback` |
| 차가 볼 때 잠들면 깨워서 📵 상태로 되돌리기 | `sleep_recovery=false` | `sleepRecoveries`, `recoveryPausedMs` |
| 폰이 차 화면 그룹까지 재웠을 때 **그 그룹만** 깨우기 (폰은 어두운 채로) | `vd_wake=false` | `vdWakes`, `lastSleepVdInteractive` |

**`keptActive` 를 증거로 읽지 마라.** `userActivity` 는 권한이 없으면 **예외 없이 조용히 버려진다** —
숫자는 그래도 오른다. 서버가 logcat 에서 그 경고를 한 번 읽어 **`keepActiveEffective`** 로 답하니 그쪽을 본다
(`false` = 이 ROM 에서는 이 경로가 죽어 있다). 마찬가지로 **`displayAlwaysUnlocked`** 는 플래그를
*요청했다*가 아니라 *받았다*는 뜻이고, 이게 `false` 면 폰에 잠금화면이 뜨는 순간 차 화면이 덮인다.
그리고 **`vdInteractive`** 가 "폰이 어두운 것"과 "차 그림이 죽은 것"을 가른다.

조사와 아직 안 가져온 후보: [prior-art.md §"전원·화면 끄고 켜기 — 2차 조사"](prior-art.md#전원화면-끄고-켜기--2차-조사-2026-09-12).

**증상이 오면 먼저 💾 세션 리포트의 요약 줄을 본다** — `폰 build=… 잠듦/깨어있음 화면ON/OFF 되살림N
활성유지N idleN`. 이 한 줄이 "기기가 잠든 것 / 패널만 꺼진 것 / 유휴 블랭킹"을 가른다. 이 줄이 없으면
옛 빌드이므로 먼저 APK 부터 올린다(리포트 #26·#27 을 그것 때문에 가리지 못했다).

### 일괄 켜기·끄기 와 바탕화면 위젯 (서버·VPN 을 한 번에, 핫스팟은 표시만)

- 끄기 **서버 → 세션**, 켜기 **세션 → 서버**. 끄기 순서가 중요하다: 킬 스위치는 그 서버로 보내는 HTTP 요청이고
  세션이 그 통로다 — 세션을 먼저 내리면 서버가 남은 채 끌 방법이 사라지는데, 밖에서 보면 성공한 것과 똑같다
- **핫스팟은 우리 일이 아니다.** 폰이 uid 2000 에게 테더링 변경을 주지 않는다(에뮬레이터·S26U 모두
  `NO_CHANGE_TETHERING_PERMISSION`, verification-log 열린 질문 0). 바꾸는 코드는 지웠고 — 그와 함께
  `tether_dun_required` 를 건드리던 것도 없어졌다 — 남은 것은 읽기뿐이다
- 상태: `GET /api/hotspot` 과 `/api/status.hotspot` 의 `on` `known` `via`. **`known=false` 는 "꺼짐"이 아니라
  "아무도 답해 주지 않았다"** 이다. 이 둘을 섞으면 운전자는 이미 켜 둔 스위치를 다시 만지러 가고 차는 그대로 못 붙는다
- **위젯에는 핫스팟을 싣지 않는다.** 스위치가 다루지 않는 것을 스위치 밑에 적으면, 그것도 이 스위치가
  건드리는 것으로 읽힌다. 핫스팟은 앱 화면에만 한 줄로 있다
- 서버가 없을 때는 앱이 직접 본다(`HotspotState`): AP 이름의 인터페이스에 IPv4 가 붙어 있나.
  서버 답이 있으면 그쪽이 우선이다(`getWifiApState`)
- 앱: `BulkControl.kt`(순서와 그 이유), `StreamService.ACTION_ALL_ON/ALL_OFF`, `CarCastWidget.kt`
- **위젯은 스스로 폴링하지 못한다.** 상태를 가진 쪽이 바뀔 때마다 `CarCastWidget.refresh()` 를 불러 줘야 한다 —
  `StreamService`(세션·서버)와 `CarVpnService`(tun)가 그렇게 한다. VPN 이 자기 전이를 알리지 않아 위젯이
  끈 뒤에도 `VPN ●` 로 남아 있던 것이 그 교훈이다: 멈추라고 **보낸** 시점과 실제로 멈춘 시점은 다르다
- 검사: `tests/device/tests/08-hotspot.test.mjs`(엔드포인트·상태·위젯 등록), 단위 `HotspotTest`("모름"과 "꺼짐"),
  `BulkControlTest`(끄기 순서·재진입 거부), `CarCastWidgetTest`(스위치가 언제 켜져 보이나)

**남은 위험:** TCP 모드도 USB 디버깅도 꺼져 있으면 Wi-Fi 가 끊길 때(핫스팟을 켜는 순간이 그렇다) 서버가 adbd 와
함께 cgroup 째 SIGKILL 된다(§3.5). `BulkControl.precondition()` 이 셋 중 무엇인지 먼저 말하고, 앱은 경고하되
막지는 않는다 — TCP 모드가 켜진 폰에서는 이게 매일 쓰는 정상 경로다.

**USB 디버깅은 앱이 켠다 (2026-09-14):** 일괄 켜기의 1/3 단계가 `UsbDebugging.ensureOn()` 이다. 그게 되는 이유는
`ShellServerLink` 가 shell 을 쥘 때마다 `pm grant com.carcast android.permission.WRITE_SECURE_SETTINGS` 를 실행해
두기 때문이고(재부팅을 넘어 유지), 그래서 재부팅 직후처럼 shell 이 없는 순간에도 앱이 `adb_enabled` 를 쓸 수 있다.
무선 디버깅은 건드리지 않는다 — adbd 를 살려 두는 건 USB 토글이고, TCP 모드 포트는 adbd 가 다시 뜰 때 `service.adb.tcp.port` 로
되살아난다(재부팅 전까지). 결과는 `Outcome` 넷 중 하나로 로그와 위젯에 남는다; `NO_PERMISSION`/`REFUSED` 일 때만 위젯이
"USB 디버깅을 켜 주세요" 를 붙인다.

**위젯은 진행 중인 단계를 그대로 보여 준다:** `BulkControl.step`(예: `3/3 서버 응답 대기 12/30초`)과 `lastFailure`,
그리고 세션은 있는데 서버가 없을 때는 `ShellServerLink.summary()`(무선 디버깅 꺼짐 / 페어링 필요 / 재시도 대기 …).
둘 다 `onChange`/`onStateChange` 콜백으로 `CarCastWidget.refresh()` 를 부른다 — 위젯은 폴링하지 못하므로.

## 4. 새 상황을 추가하는 법

`tests/e2e/tests/lifecycle/actions.ts` 에 동작 하나를 더하면 시나리오와 무작위 탐색 양쪽에 자동으로 들어간다.

```ts
{
  id: 'phone.airplane-mode',
  side: 'phone',
  title: '비행기 모드를 켠다',
  mayStopVideo: true,                    // 여기서 영상이 끊기는 것은 실패가 아니다
  async run() { adbShell('cmd connectivity airplane-mode enable'); await sleep(2000); },
}
```

불변 조건을 더하고 싶으면 `lifecycle/probe.ts` 의 `disagreements()` 에 한 줄 넣는다 —
그 줄이 모든 시나리오와 탐색 보고서의 "어긋남" 칸에 뜬다.

## 5. 확인 없이 끝내지 않기

- **증거 없이 고치지 않는다.** 실차 증상이 오면 먼저 리포트에 그 원인을 가를 필드가 있는지 본다. 없으면
  그것부터 넣는 것이 첫 수정이다 — #26·#27 은 폰 쪽 상태가 없어 두 번이나 추측으로 끝날 뻔했다.
- **가상 디스플레이는 픽셀이 바뀔 때만 낸다.** 정지 화면에서는 8초에 한 조각까지 떨어진다(실측). 인코딩 경로를
  보려면 화면을 움직여 줘야 한다 — `tests/device/lib.mjs` 의 `wiggle()` 이 그 일을 한다. 정지 화면에서도
  바닥(100ms)이 지켜지는지는 실기기에서 `MAX_GAP_MS=500` 으로 본다.
- **A+ 에서 처리량(fps·지연)을 묻지 않는다.** 에뮬레이터의 소프트웨어 인코더는 정지 화면에서 0.3fps 까지
  떨어진다(실측). `NO_THROUGHPUT=1` 이 그 검사들을 건너뛰게 한다. 숫자가 필요하면 A(가짜 폰)나 B(실기기)로 간다.
- **기기마다 갈리는 것은 단언하지 않는다.** 전원·패널·인코더 속도는 에뮬레이터(AOSP)와 S26U(One UI)가 다르다.
  기록만 하고, 어디서나 성립해야 하는 것(서버 생존, 앱 위치, 되돌아올 수 있는가)만 단언한다.
- **CI 에서 탐색을 한 번 돌리려면** `tools/virtual-phone/explore.steps` 의 숫자를 바꿔 푸시하고, 아티팩트
  `virtual-phone-logs` 의 `out/lifecycle/explore.md` 를 읽은 뒤 **다시 0 으로 되돌린다**. 매 푸시마다 돌리면
  CI 가 5~8분 길어진다. (Actions 의 "Run workflow" 로 `explore_steps` 를 줘도 된다.)
- **탐색에서 나온 문제는 씨앗으로 재현한다.** 보고서 맨 위의 `EXPLORE_SEED` 를 그대로 넣으면 같은 순서가 나온다.
- **고친 뒤에는 A+ 를 다시 돌린다.** CI 라면 푸시하고 `emulator` 워크플로가 녹색이 될 때까지 따라간다.
  로그에서 볼 것: `uid=2000`, `source=display`, `injectFailed:0`, 시나리오 표의 "어긋남" 칸.
- **A+ 가 통과했다고 B 를 건너뛰지 않는다.** 순서가 바뀔 뿐이다 — 폰에 올리기 전에 여기서 먼저 깨진다.
  A+ 에서 못 보는 것의 전체 목록: [tools/virtual-phone/README.md](../tools/virtual-phone/README.md).

## 6. 막혔을 때

| 증상 | 볼 곳 |
|---|---|
| `source=clip` | 가상 디스플레이 생성 실패. `vphone.sh logs` 의 `라이브 소스 실패` |
| 에뮬레이터가 바로 죽음 | 디스크. `FATAL | Not enough space to create userdata partition` — CI 는 선설치 툴체인을 지우고 userdata 를 4GB 로 줄여 둔다 |
| `adb shell "... &"` 가 안 돌아옴 | stdin 까지 `/dev/null` 로 떼야 한다(`vphone.sh start_server`) |
| 차 화면 버튼이 안 눌림 | 스테이지 위 UI 는 `data-ui` 를 달아야 한다. 안 그러면 `setPointerCapture` 가 클릭을 삼킨다 |
| 실차에서만 나는 증상 | 차에서 디버깅하지 않는다. 💾 로 세션 리포트를 남기고, 그 `events` 를 집에서 재현한다 |

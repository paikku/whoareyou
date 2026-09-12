# 남들은 어떻게 하고 있나 (2026-09-05 조사)

폰 앱을 테슬라 화면에 올리는 앱 4개를, **사용 안내 문서와 (공개된 경우) 소스**로 확인했다.
추측과 확인을 구분해 적는다. 우리 설계에 반영할 것은 §6.

## 0. 한눈에

| | Tesor | TeslaMirror / TslaMirror | TeslaDisplay / TesDisplay | **Castla** (Apache-2.0, 소스 공개) |
|---|---|---|---|---|
| 권한 | **Shizuku**(=ADB shell) | **없음** (접근성만) | 미공개(정황상 없음) | **Shizuku** |
| 화면 | 별도 가상 디스플레이 | 물리 화면 미러 | 물리 화면 미러 | **별도 가상 디스플레이** (+미러 모드) |
| 입력 | shell 주입 | `AccessibilityService` | 있음(방식 미공개) | `InputManager.injectInputEvent` (리플렉션, Shizuku) |
| 화면 분할 | ✅ | ❌ | ❌ | ✅ `am start --windowingMode 5`(freeform) |
| 차가 여는 주소 | 도메인(`tesor.arter97.com`) | 도메인(`https://TSL6.com`) | 도메인(`https://td9.cc:7777`) | **생 IP** (`http://<ip>:8192`) |
| 비-사설 주소 확보 | 미공개 | VpnService 가상 IP `100.99.9.9` | 가상 IP → **2024-06 이후 깨짐** | **포기하고 실측**(§3) |
| 재부팅 후 | Wi-Fi 1회 필요(명시) | 미공개 | 미공개 | Wi-Fi 1회 필요(명시) |
| 렌더러 | 미공개 | 미공개 | 미공개 | **WebCodecs** → MSE → MJPEG |

핵심: **기능이 많은 쪽(Tesor·Castla)은 예외 없이 shell(Shizuku)을 쓴다.** 권한 없이 가는 쪽은 미러링·보기 위주이고,
게다가 2024년 6월 Google Play 시스템 업데이트 이후 네트워크 경로가 깨져 고생하고 있다.

---

## 1. Tesor (한국, 비공개)

- 공식 매뉴얼(Notion) "시작하기"에 그대로: **"⚠️ 초기 Tesor 페어링/설정을 위해 Wi-Fi 연결이 필요합니다."**,
  **"⚠️ 휴대폰을 재부팅할 때마다 아무 Wi-Fi에 1회 다시 연결해야 합니다."** → 우리와 같은 제약.
- 설치 2단계가 "Shizuku 설치 후 권한 부여". 화면 분할이 되는 것 자체가 shell 권한의 증거다(MediaProjection으로는 불가).
- 배울 점: **"Wi-Fi가 연결되면 Tesor가 자동으로 백그라운드에서 연결을 설정합니다"** — 사용자가 앱을 열 필요가 없다.
  또 **Bluetooth 시작 장치**(차 BT를 보면 자동 시작)와 **핫스팟 자동 시작**.

## 2. TeslaMirror / TslaMirror (비공개, 국제적으로 가장 유명)

- Android FAQ: 원격 제어는 **`AccessibilityService` API**로 터치를 변환한다고 명시. **ADB·Shizuku·루트 언급 없음.**
- 접속 주소는 **`https://TSL6.com`**(폴백 `https://TeslaMirror.net:9999`). 내부적으로 `100.99.9.9`를 쓰지만
  사용자가 직접 그 IP로 접속하는 건 아니라고 안내 — 즉 **공개 도메인의 A 레코드를 가상 IP로 지정**해 HTTPS(정상 인증서)로 여는 방식.
  우리 `implementation-proposal.md` §2.3이 적어 둔 "TSL6.com 패턴"이 이것으로 확인됐다.
- 우리 `Config.TUN_ADDRESS = 100.99.9.9`가 이 앱에서 온 값이다(구버전은 `3.3.3.3`).
- 한계: 미러링이라 **화면 분할·별도 화면이 없다.**

## 3. TeslaDisplay / TesDisplay (비공개, 앱은 무료 / 사이트는 GitHub Pages)

- 공식 사이트에 **"2024년 6월 1일 이후의 Google Play 시스템 업데이트가 가상 IP로 가는 요청을 차단한다"** 는 전용 안내 페이지가 있다.
  → 우리가 verification-log §3.2에서 독립적으로 찾아낸 **Android 14 ingress-discard**와 같은 사건이다.
- 그들이 제시한 해결책:
  - **방법 A:** "Disable SSL"을 켜고, **통신사가 준 내부 IP가 테슬라 차단 범위 밖일 때만** 동작.
    → 우리 [hotspot-only.md](hotspot-only.md) §4.1의 **CLAT 주소(`192.0.0.x`)** 아이디어와 같은 것.
  - **방법 B:** **폰 두 대 릴레이** — 2024-06 이전 업데이트에 머문 구형 폰을 중계기로 세운다(자동 업데이트도 꺼야 함).
  - 방법 C: 작업 중.
- 즉 **권한 없이 가는 진영은 이 문제를 아직 못 풀었다.** shell uid는 이 BPF 규칙에서 면제되므로,
  우리의 "불편한" shell 구조가 사실은 **현행 안드로이드에서 동작하게 만드는 바로 그 요소**다.

## 4. Castla (Apache-2.0, 소스 공개 — 가장 많이 배울 곳)

`github.com/Suprhimp/castla`. Tesor와 기능이 거의 같고 전부 읽을 수 있다.

- 구조: NanoHTTPD WebSocket(8192) + MediaCodec H.264 → **WebCodecs**(폴백 MSE, MJPEG) + `AudioPlaybackCapture` → AAC.
- 권한: Shizuku `IPrivilegedService`. 가상 디스플레이 생성, `InputManager.injectInputEvent` 리플렉션 주입,
  `--windowingMode 5`로 freeform 분할, `TetheringManager.startTethering`에 **`setExemptFromEntitlementCheck(true)`** 로 통신사 핫스팟 제한 우회.
- **네트워크 — 가장 중요한 발견:**
  - `network/TunTcpRelay.kt`(377줄)에 **VpnService tun fd 위의 유저스페이스 TCP 스택**이 실제로 구현되어 있다
    (우리 [hotspot-only.md](hotspot-only.md) §1.2의 플랜 B와 같은 것). **그런데 어디서도 호출되지 않는다 — 사실상 폐기 코드.**
  - Shizuku로 인터페이스에 IP 별칭을 붙이는 `setupTeslaNetworking(ifName, "100.99.9.9")`와
    **핫스팟 서브넷 자체를 CGNAT(`100.64.0.1/24`)로 재시작**하는 `restartTetheringWithCgnat()`도 있는데,
    **둘 다 구현이 `return ""` 스텁**이다 — 시도했다가 접은 흔적.
  - 실제로 쓰는 방식은 `IpSelector` + `ReachableIp`: **폰의 IPv4를 전부 열거해 우선순위로 하나를 권하고, 나머지도 후보로 보여준 뒤,
    브라우저가 실제로 접속해 온 `Host` 헤더의 IP를 기억해 다음부터 그걸 우선한다.** 주석이 솔직하다 —
    *"Two opposite guesses have already shipped and failed, so the app measures instead of guessing a third time."*
  - 그 주석에 우리에게 결정적인 실측이 있다(issue #51): **`http://192.0.0.8`은 되는데 `192-0-0-8.sslip.io`는 안 됐다** —
    IPv6 전용 망의 DNS64가 A 레코드를 `64:ff9b::c000:8`로 합성해 통신사 NAT64로 보내버리기 때문. 그래서 **호스트명을 버리고 생 IP만 광고한다.**
    → **TeslaMirror·TeslaDisplay의 도메인 방식은 IPv6 전용 통신사에서 같은 이유로 깨질 수 있다.**
- **수명:** Shizuku 서버가 죽으면 되살리는 **2단(outer/inner) 셸 워치독**을 `/data/local/tmp`에 설치하고 heartbeat 파일로 감시한다.
  pid 파일 + `/proc/<pid>/cmdline`을 NUL로 쪼개 **정확히 일치**할 때만 kill — 우리가 `306d41a`에서 겪은 `pkill -f` 자살 버그를 그들도 피해 갔다.
  추가로 doze 화이트리스트·`RUN_ANY_IN_BACKGROUND` appop 등 "fortify"를 건다.
- **그래도 재부팅은 못 넘는다.** 설치 가이드에 그대로: *"If you restart your phone, you will need to open Shizuku and tap 'Start' again."*

---

## 5. 그래서 확인된 사실

1. **재부팅 후 Wi-Fi 1회는 업계 공통 제약이다.** Tesor·Castla 모두 문서에 명시. 아무도 못 넘었다.
   (근거: [hotspot-only.md](hotspot-only.md) §1.3의 AOSP `AdbDebuggingManager` 코드.)
2. **화면 분할·별도 화면을 원하면 shell은 선택이 아니라 필수다.** 미러링 진영은 그 기능이 아예 없다.
3. **가상 IP(VpnService) 경로는 2024-06 이후 앱 uid에서 죽었고, 아무도 복구하지 못했다.** 유저스페이스 TCP 릴레이까지 만들어 봤지만
   Castla는 쓰지 않는다. 우리가 shell uid로 이 규칙을 면제받는 것이 현재로선 가장 깨끗한 해답이다.
4. **WebCodecs가 평문 http에서 쓰인다.** Castla는 `http://<ip>:8192`에서 WebCodecs를 1순위로 쓴다 —
   우리 제안서의 "WebCodecs는 secure context가 필요하니 HTTPS 이후"라는 가정은 **재검토 대상**이다.

## 6. 우리가 가져올 것 (우선순위)

| # | 무엇 | 왜 | 어디에 |
|---|---|---|---|
| 1 | **주소를 추측하지 말고 측정한다** — 후보 IPv4를 전부 광고하고, 브라우저가 실제로 접속해 온 `Host` 헤더 IP를 기억해 다음부터 우선 | Castla가 두 번 틀린 뒤 도달한 결론. `192.0.0.x`가 되는 경우가 실제로 있다 | `core` 서버가 `Host`를 기록 → 앱이 URL/QR로 표시 |
| 2 | **Wi-Fi가 잡히면 앱을 열지 않아도 백그라운드에서 자동 기동** | Tesor의 동작. "재부팅 후 1회"의 체감을 없앤다 | `ShellServerLink`를 네트워크 콜백으로 깨우기 |
| 3 | **셸 워치독** — 서버가 죽으면 셸 쪽에서 되살린다 | Castla의 2단 워치독. 우리 TCP 모드와 합치면 차에서의 복구가 이중화된다 | `ServerCommand`에 워치독 스크립트 추가 |
| 4 | **핫스팟 자동 켜기** (`TetheringManager` + `setExemptFromEntitlementCheck(true)`) + **차 BT 감지 자동 시작** | Tesor·Castla 공통. 차에 타면 아무것도 안 눌러도 된다 | shell-server(권한 필요) |
| 5 | **WebCodecs 렌더러를 http에서 시험** | 평문에서 되면 fMP4 먹싱을 우회해 지연이 준다 | `web/` 렌더러 3번째 구현 |
| 6 | 도메인 방식은 **채택하지 않는다** | IPv6 전용 통신사에서 DNS64가 NAT64로 보내 깨진다(Castla issue #51 실측) | — |

## 7. 출처

- Tesor 공식 매뉴얼: <https://tesor.arter97.com/manual> (Notion으로 리다이렉트), [Google Play](https://play.google.com/store/apps/details?id=com.arter97.tesor)
- TeslaMirror: [teslamirror.com Android FAQ](https://teslamirror.com/faq-android.html), [Google Play](https://play.google.com/store/apps/details?id=com.hustmobile.teslamirror)
- TeslaDisplay: [tesladisplay.com](https://tesladisplay.com/), [2024-06 업데이트 해결책 페이지](https://tesladisplay.com/solution-for-new-update/), [사이트 소스](https://github.com/blackpill/tesla-display)
- Castla: [github.com/Suprhimp/castla](https://github.com/Suprhimp/castla) (Apache-2.0). 인용한 파일:
  `app/src/main/java/com/castla/mirror/network/{IpSelector,ReachableIp,TunTcpRelay}.kt`,
  `.../shizuku/{ShizukuSetup,PrivilegedService}.kt`, `.../input/TouchInjector.kt`, `.../service/MirrorForegroundService.kt`,
  `app/src/main/assets/web/js/{decoder,mse-decoder}.js`, `shizuku-install-guide.md`
- 테슬라 사설 IP 차단: [TMC](https://teslamotorsclub.com/tmc/threads/cant-access-private-websites-on-wifi.44134/) · Shizuku 재부팅 제약: [공식 가이드](https://shizuku.rikka.app/guide/setup/)

## 전원/화면 끄기와 가상 디스플레이 — 남들도 못 푼 문제 (2026-09-11 조사)

실차 리포트 #26 의 "전원 버튼을 누르면 차가 무응답"을 두고 상류를 뒤졌다. scrcpy 에 **열려 있는 같은 문제**가 있다.

**[Genymobile/scrcpy#6787](https://github.com/Genymobile/scrcpy/issues/6787)** — *"Virtual displays go black after being
idle for 10sec when screen is off"*. 보고자의 묘사가 우리 증상과 정확히 같다: 가상 디스플레이 크기의 **불투명한
검은 면**이 덮이고, 그 아래에서 앱은 계속 그려진다. 화면이 변하지 않으니 인코더가 멈추고 차에는 얼어붙은
그림만 남는다. **카운트다운은 물리 화면이 꺼진 뒤에 시작된다.**

그쪽에서 찾은 회피책도 둘뿐이다:
- `--stay-awake` — `stay_on_while_plugged_in` 을 건드리므로 **충전 중에만** 듣는다(안드로이드 동작).
  우리도 이미 켠다(`ScreenPower.stayAwake`).
- 물리 화면을 깨어 있게 유지 — 손으로 만지거나 Caffeinate 같은 도구로.

그리고 scrcpy 가 그 "깨어 있게 유지"를 옵션으로 만든 것이 **`--keep-active`**: 주기적으로
`PowerManager.userActivity()` 를 보내 유휴 타임아웃을 되돌린다(`Device.keepActive` → `userActivity(displayId)`).

**우리가 가져온 것:** 차가 보고 있는 동안 5초마다 **가상 디스플레이의 id 로** `userActivity` 를 보낸다
(`keep_active=false` 로 끌 수 있다). 그 디스플레이는 자기 display group 을 가지므로 폰 본체를 깨우지 않는다.

**가져오지 않은 것과 이유:**
- scrcpy 의 `keepDisplayPowerOff` 는 **자기가 주입한** POWER/WAKEUP 키 뒤에 200ms 지연으로 패널을 다시 끄는
  장치다(`Controller.scheduleDisplayPowerOff`). 사용자가 **직접 누른** 물리 전원 버튼에는 닿지 않으므로
  우리 경우에는 그대로 쓸 수 없다. 대신 잠든 것을 감지해 되살리는 쪽을 택했다(§verification-log 3.9).
- 가상 디스플레이 플래그는 scrcpy 최신(`NewDisplayCapture`)과 **한 글자도 다르지 않다** — 빠진 플래그 때문이
  아니라는 뜻이다.

**남은 불확실성:** #6787 은 아직 열려 있다. 즉 상류에도 확실한 해법이 없다. 우리 쪽에서 `userActivity` 가
One UI 8 에서 실제로 유휴 시계를 되돌리는지는 실기기에서만 답이 나온다 — `/api/status` 의 `keptActive` 가
올라가는데도 화면이 검어지면 이 경로는 그 ROM 에서 듣지 않는 것이다.

---

# 전원·화면 끄고 켜기 — 2차 조사 (2026-09-12)

§"전원/화면 끄기와 가상 디스플레이"는 scrcpy 하나만 봤다. 이번에는 **scrcpy 말고** 같은 문제를 만난
다른 구현들과 AOSP 원본을 읽었다. 읽은 것: Castla(소스), Extinguish(소스), SecondScreen(소스),
DisplayToggle(문서), 그리고 AOSP `PowerManagerService`·`RootWindowContainer`·`DisplayManagerService`.
**모두 코드/문서 읽기다. 실기기 실측은 하나도 없다** — 실측이 필요한 항목은 §6에 따로 적었다.

## 1. 패널을 끄는 방법은 이미 한 곳으로 수렴했다

| 프로젝트 | 권한 | 패널 끄기 |
|---|---|---|
| scrcpy | adb shell | `SurfaceControl.setDisplayPowerMode(token, 0/2)` |
| Castla | Shizuku | 같음 (`PrivilegedService.setPhysicalDisplayPower`) |
| Extinguish | Shizuku | 같음 (`DisplayControlService.setPowerModeToSurfaceControl`) |
| DisplayToggle | adb / root | 같음. dex 하나를 `app_process` 로 띄우는 것까지 우리와 같다 |
| SecondScreen | **root** | sysfs 백라이트에 `0` (`/sys/class/leds/lcd-backlight/brightness` 외 6종 하드코딩) |

**넷 중 넷이 `setDisplayPowerMode` 다. Android 15 의 `DisplayManager.requestDisplayPower` 를 쓰는 곳은 없다**
— 우리가 `d98be88` 에서 겪은 것과 같은 이유(scrcpy#5530)로 보인다. 이 선택은 이제 확정된 것으로 본다.

곁가지 둘:
- **Extinguish 는 물리 디스플레이 중 첫 번째 하나만 끈다**(`getPhysicalDisplayIds()[0]`). scrcpy·우리는 전부 끈다.
  폴더블에서 갈릴 자리다(우리 쪽이 안전한 선택).
- **Extinguish 에는 `SurfaceControl.setDisplayBrightness(token, 0f)` 경로도 있다.** `setDisplayPowerMode`
  가 먹지 않는 ROM 의 대안으로 쓸 수 있다. SecondScreen 의 sysfs 도 같은 성격의 폴백인데 루트가 필요하다.

## 2. "깨어 있게 두기" 의 전체 목록 — AOSP 가 답을 적어 놓았다

`PowerManagerService.isBeingKeptAwakeLocked(powerGroup)` 한 함수가 **display group 단위로** 조건을 나열한다:

```java
return mStayOn                                             // stay_on_while_plugged_in && 충전 중 (전역)
    || mProximityPositive
    || (powerGroup.getWakeLockSummaryLocked() & WAKE_LOCK_STAY_AWAKE) != 0   // 그 그룹의 화면 웨이크락
    || (powerGroup.getUserActivitySummaryLocked() & (USER_ACTIVITY_SCREEN_BRIGHT|_DIM)) != 0
    || mScreenBrightnessBoostInProgress;
```

우리 가상 디스플레이는 `OWN_DISPLAY_GROUP` 으로 자기 그룹을 가지므로(§3 참고), 여기서 **그 그룹에**
해당하는 것만 듣는다. 남들이 실제로 쓰는 수단을 이 목록에 대보면:

| 수단 | 쓰는 곳 | 성질 |
|---|---|---|
| `stay_on_while_plugged_in` | scrcpy `-w`, SecondScreen, **우리** | 충전 중에만. 전역 |
| **`screen_off_timeout` 를 크게** | scrcpy `--screen-off-timeout`(종료 시 복원), SecondScreen(`2147482000`) | 충전과 무관. **우리는 안 쓴다** |
| **`FLAG_KEEP_SCREEN_ON` 창 하나** | Extinguish `AwakeHost`(1×1 `TYPE_APPLICATION_OVERLAY`), Castla(VD 위 `Presentation`) | 주기 호출 없음. 상태가 유지된다 |
| 주기적 `userActivity(displayId)` | scrcpy `--keep-active`(4초), Castla(30초), **우리**(5초) | 계속 두드려야 함 |
| 가짜 충전 `dumpsys battery set ac 1` | 커뮤니티 (테스트용, `dumpsys battery reset` 로 복원) | 위 1번을 충전 없이 켜는 우회 |

세 번째가 이번 조사에서 가장 값어치 있는 발견이다. `PowerManager.newWakeLock(level, tag)` 은 AOSP 에서
`newWakeLock(levelAndFlags, tag, mContext.getDisplayId())` 로 가고, `WakeLock.acquire()` 는
`acquireWakeLock(..., mDisplayId, ...)` 로 간다. 즉 **웨이크락은 만든 컨텍스트의 디스플레이 그룹에 묶인다.**
창에 `FLAG_KEEP_SCREEN_ON` 을 다는 것은 WindowManager 가 그 창의 디스플레이로 같은 락을 잡아 주는 것이다.
→ **앱이 `createDisplayContext(가상 디스플레이)` 로 1×1 투명 오버레이를 VD 에 붙이면, 5초마다 두드리는
대신 그 그룹을 계속 깨어 있게 둘 수 있다.** (추측: 앱 uid 에서 VD 에 창을 붙일 수 있는지는 실측 전. 우리 VD 는
`PUBLIC` 이라 `DisplayManager.getDisplay(id)` 로 보이기는 한다.)

### 2.1 우리 `keptActive` 는 "먹혔다" 는 증거가 아니다 — 고칠 것

`PowerManagerService.userActivity` 의 권한 검사는 이렇게 끝난다:

```java
// Once upon a time applications could call userActivity().
// Now we require the DEVICE_POWER permission.  Log a warning and ignore the
// request instead of throwing a SecurityException so we don't break old apps.
return;
```

**권한이 없으면 예외가 아니라 조용한 무시다.** 우리 `ScreenPower.pokeVirtualDisplay()` 는 예외가 없으면
`keptActive++` 하므로, **이 카운터는 호출 횟수이지 효과의 증거가 아니다.** verification-log §3.10 이
"`keptActive` 가 오르는데도 검어지면 이 경로가 안 듣는 것"이라고 적어 둔 판정 기준은 그래서 반쪽이다
(권한이 없어서 안 듣는 경우와 권한은 있는데 효과가 없는 경우를 못 가른다).

권한 자체는 있을 가능성이 높다 — AOSP `packages/Shell/AndroidManifest.xml` 에 `DEVICE_POWER` 가 들어 있고
(`protectionLevel="signature|role"`, 셸은 플랫폼 서명), scrcpy 가 이 경로로 동작한다. 하지만 One UI 에서
확인된 것은 아니다. **판정은 카운터가 아니라 logcat 의 `Ignoring call to PowerManager.userActivity()`
경고 유무로 해야 한다.**

## 3. 디스플레이 그룹 — 우리 플래그는 맞다, 하나는 무시되고 있다

AOSP `DisplayManagerService.createVirtualDisplayLocked`:

- `OWN_DISPLAY_GROUP` 은 `ADD_TRUSTED_DISPLAY` 권한이 있어야 하고(셸에 있다), 자기 그룹을 만든다. ✅
- `DEVICE_DISPLAY_GROUP` 은 **virtual device 가 있을 때만** 의미가 있다. 없으면
  *"Display created with VIRTUAL_DISPLAY_FLAG_DEVICE_DISPLAY_GROUP set, but no virtual device.
  The display will not be added to a device display group."* 를 찍고 **무시한다.**
  우리(그리고 scrcpy)는 API 34+ 에서 이 플래그를 함께 준다 — 해가 되지는 않지만 하는 일도 없다.

그리고 전원 쪽 함의: `IPowerManager.goToSleep()` 은 내부적으로
`goToSleepInternal(DEFAULT_DISPLAY_GROUP_IDS, ...)` 다. **기본 그룹만 재운다.** 즉 순수 AOSP 라면 전원 버튼이
VD 그룹을 재우지는 않아야 한다. 그런데 리포트 #26 에서는 차 화면이 죽었다 → 실제로 죽인 것은 "기기가
잠들어서"가 아니라 §4 쪽일 가능성이 있다. (추측. 갈라 보는 실험은 §6.)

## 4. 검은 면의 정체 — 키가드일 가능성 (가설, 미검증)

scrcpy#6787 의 묘사("물리 화면이 꺼진 **뒤에** 카운트다운이 시작되고, 10초 뒤 불투명한 검은 면이 덮이며,
그 아래 앱은 계속 그려진다")와 아귀가 맞는 코드가 AOSP `RootWindowContainer.handleNotObscuredLocked` 에 있다:

```java
// While a dream or keyguard is showing, obscure ordinary application content on
// secondary displays ...
if (w.isDreamWindow() || mWmService.mPolicy.isKeyguardShowing()) {
    mObscureApplicationContentOnSecondaryDisplays = true;
}
...
} else if (displayContent != null &&
        (!mObscureApplicationContentOnSecondaryDisplays
                || displayContent.isKeyguardAlwaysUnlocked()      // ← FLAG_ALWAYS_UNLOCKED
                || (obscured && w.mAttrs.type == TYPE_KEYGUARD_DIALOG))) {
    displayHasContent = true;
}
```

`isKeyguardAlwaysUnlocked()` 는 `mDisplayInfo.flags & Display.FLAG_ALWAYS_UNLOCKED` 이고, 그것이 곧
`VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED` 다. 즉:

- **물리 화면이 꺼진 뒤 "10초"는 유휴 타임아웃이 아니라 잠금 지연(`lock_screen_lock_after_timeout`)일 수 있다.**
  키가드가 뜨는 순간 보조 디스플레이의 앱 콘텐츠가 "내용 없음"으로 떨어진다.
- 우리는 API 33+ 에서 `ALWAYS_UNLOCKED` 를 주므로 **면제 대상이어야 한다.** 면제가 실제로 붙었는지는
  플래그를 요청했다는 사실이 아니라 **만들어진 디스플레이가 그 플래그를 갖고 있는지**로 확인해야 한다
  (`virtualDisplay.getDisplay().getFlags()`). API 33 미만이거나 `ADD_ALWAYS_UNLOCKED_DISPLAY` 가 거부되면
  플래그는 조용히 빠진다.
- AOD/Always-On Display 를 쓰는 폰에서는 `isDreamWindow()` 쪽으로도 같은 스위치가 켜진다. One UI 의 AOD 가
  여기 걸리는지는 모른다.

이것이 맞다면 회피책은 `userActivity` 가 아니라 **잠금을 늦추거나(잠금 지연 설정) 플래그를 확실히 받는 것**이다.

## 5. 전원 버튼 — 가로챌 수 없다는 전제는 모두 같고, 대응만 다르다

| | 대응 |
|---|---|
| scrcpy | **자기가 주입한** POWER/WAKEUP 뒤 200ms 에 패널을 다시 끈다(`keepDisplayPowerOff`). 물리 버튼은 못 건드린다고 문서에 명시 |
| Castla | `ACTION_SCREEN_OFF`/`ACTION_SCREEN_ON` **브로드캐스트**로 상태기계를 돌린다(폴링 없음). 패널 끄기가 실패하면 `isPanelOffSupported=false` 로 **기억해 두고** keep-alive 로 강등한다. 정리 시 항상 패널을 켜 놓는다 |
| SecondScreen | `ACTION_SCREEN_ON` 을 받아 **`sleep 2` 뒤** 백라이트를 도로 끈다 |
| 우리 | 1초 폴링 감시자 + 잠들면 깨워서 📵 상태로 되돌리기 + 3연타 탈출구 |

가져올 만한 것 둘:
1. **브로드캐스트로 바꾸거나 보강하기.** 1초 폴링은 최대 1초를 잃고, 그동안 차는 얼어 있다.
2. **실패를 기억하기.** Castla 의 `ScreenOffPolicy` 는 패널 끄기가 한 번 실패하면 다시 시도하지 않고
   keep-alive 로 내려간다. 우리는 `keepActive` 에만 그런 강등이 있다.

한편 Castla 의 keep-alive 구현(`PrivilegedService.wakeUpDisplay`)은 세 가지를 함께 쏜다:
`input -d <displayId> keyevent 224`(WAKEUP), 리플렉션 `userActivity`, 그리고 VD 위의 (1,1) 무해한 터치.
**그중 `userActivity` 는 Android 12+ 에서 死코드로 보인다** — 4인자 시그니처는 `(int displayId, long, int, int)`
인데 `m.invoke(pm, displayId.toLong(), now, 0, 0)` 로 첫 인자를 `Long` 으로 넘긴다. `IllegalArgumentException`
이 나고 그 자리에서 삼켜진다(코드 읽기 기반, 실행해 보지는 않았다). 실제로 듣는 것은 keyevent 와 가짜 터치뿐일 것이다.
**우리 구현이 이 점에서는 맞다.** 반대로 그쪽에서 배울 것은 **가짜 터치** 라는 폴백이다 — 권한이 필요 없고,
`userActivity` 가 조용히 무시되는 ROM 에서도 유효하다.

## 6. 아무도 안 쓰는데 열려 있는 길 — 그룹 단위 sleep/wake

`IPowerManager` 에는 디스플레이를 지정하는 짝이 있다:

```aidl
void wakeUpWithDisplayId(long time, int reason, String details, String opPackageName, int displayId);
void goToSleepWithDisplayId(int displayId, long time, int reason, int flags);
```

둘 다 `DEVICE_POWER` 를 요구하고, 그 권한은 셸 패키지에 있다(§2.1). 우리에게 의미하는 것:

- 지금 `ScreenPower.wake()` 는 `input keyevent WAKEUP` 으로 **폰을 통째로** 깨운 다음 패널을 도로 끈다.
  그 사이 화면이 한 번 번쩍인다. `wakeUpWithDisplayId(..., 가상디스플레이id)` 면 **그 그룹만** 깨운다.
- 거꾸로 `goToSleepWithDisplayId(0, ...)` 는 기본 그룹만 재운다 — `setDisplayPowerMode` 와 달리
  PowerManager 의 장부와 어긋나지 않는다(지금 우리가 `forcedOff` 를 따로 들고 다니는 이유가 그 어긋남이다).
  다만 그러면 키가드가 뜨므로 §4 의 위험을 같이 짊어진다.

**전부 추측이다.** One UI 에서 셸 uid 로 이 호출이 통하는지, VD 그룹만 깨어 있을 때 인코더가 계속 도는지
확인된 바 없다.

## 7. 그래서 다음에 할 것

우선순위대로. 위쪽 셋은 실기기 없이 **가상 폰(A+)에서** 바로 볼 수 있다.

| # | 무엇 | 왜 | 어디에 |
|---|---|---|---|
| 1 | `keptActive` 대신 **logcat 경고로 판정**하고, VD 의 `getFlags()` 를 `/api/status` 에 싣는다 | 지금 카운터는 효과를 증명하지 못한다(§2.1). 플래그는 §4 가설을 가른다 | `ScreenPower`, `DisplayCapture`, `/api/status` |
| 2 | **`screen_off_timeout` 손잡이**(크게 잡고 종료 시 복원) | 충전과 무관하게 듣는 유일한 수단. scrcpy·SecondScreen 둘 다 쓴다 | `ScreenPower`, 서버 옵션 |
| 3 | 잠금 지연을 바꿔 가며 검어지는 시점이 따라 움직이는지 본다 | §4 가설의 유일한 결정적 실험. 맞으면 손잡이가 통째로 바뀐다 | `npm run lifecycle` 시나리오 하나 추가 |
| 4 | **VD 에 묶인 `FLAG_KEEP_SCREEN_ON` 오버레이** | 두드리기 대신 상태로 유지. Extinguish·Castla 둘 다 이 방식 | 앱(`createDisplayContext`) |
| 5 | `ACTION_SCREEN_ON/OFF` 로 폴링 보강 + 패널 끄기 실패를 기억 | Castla. 최대 1초의 얼어붙음을 없앤다 | `ScreenPower`, 앱 |
| 6 | `wakeUpWithDisplayId` 로 폰을 깨우지 않고 VD 만 되살리기 | 지금은 깨웠다 끄느라 번쩍인다 | `ScreenPower.wake` |
| 7 | 폴백들: `setDisplayBrightness(token,0)`, VD 위 가짜 터치, `cmd display power-off 0`(A15+) | ROM 이 갈리는 자리마다 하나씩 | — |
| 8 | A+ 에서 `dumpsys battery set ac 1` 로 `stay_awake` 경로를 실제로 통과시켜 본다 | 지금은 에뮬레이터가 충전 중이 아니라 이 손잡이가 검사되지 않는다 | `tools/virtual-phone` |

**가져오지 않을 것:** sysfs 백라이트(SecondScreen — 루트 + 기기별 경로), `PowerManager.goToSleep()`·
`DevicePolicyManager.lockNow()`(기기를 재워 VD 까지 죽인다 — 리포트 #26 의 원인 그 자체), 도메인/HTTPS 경로(§6 기존).

## 8. 출처

- Castla `app/src/main/java/com/castla/mirror/{policy/ScreenOffPolicy.kt, service/MirrorForegroundService.kt,
  capture/VirtualDisplayManager.kt, shizuku/PrivilegedService.kt}` — <https://github.com/Suprhimp/castla>
- Extinguish (`shizuku-service/.../DisplayControlService.kt`, `service/hosts/AwakeHost.kt`) — <https://github.com/Moderpach/Extinguish>
- SecondScreen (`util/U.java`, `service/{ScreenOnService,TempBacklightOnService,ProfileLoadService}.java`) — <https://github.com/farmerbb/SecondScreen>
- DisplayToggle — <https://github.com/Rehtt/DisplayToggle>
- scrcpy `doc/device.md`(keep-active·stay-awake·screen-off-timeout·turn-screen-off), `Device.java`, `Controller.java`;
  이슈 [#6787](https://github.com/Genymobile/scrcpy/issues/6787)(가상 디스플레이 블랭킹, 열림),
  [#6491](https://github.com/Genymobile/scrcpy/issues/6491)(전원/잠금 뒤 검은 화면, 열림),
  [#5530](https://github.com/Genymobile/scrcpy/issues/5530)(Android 15 `requestDisplayPower`)
- AOSP `frameworks/base`: `services/core/java/com/android/server/power/PowerManagerService.java`
  (`isBeingKeptAwakeLocked`, `userActivity` 권한 검사, `goToSleepInternal`, `wakeUpWithDisplayId`),
  `services/core/java/com/android/server/wm/{RootWindowContainer,DisplayContent}.java`
  (`handleNotObscuredLocked`, `isKeyguardAlwaysUnlocked`),
  `services/core/java/com/android/server/display/DisplayManagerService.java`(VD 플래그 처리),
  `core/java/android/os/{PowerManager.java,IPowerManager.aidl}`, `packages/Shell/AndroidManifest.xml`

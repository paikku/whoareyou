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

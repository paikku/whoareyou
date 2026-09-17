# 검증 기록 (2026-09-05 기준)

이 프로젝트가 의존하는 가정들을 **어디서, 어떤 테스트로, 무엇을 확인했는지** 한곳에 모은 문서.
계획은 [dev-plan.md](dev-plan.md), 절차는 [testing-guide.md](testing-guide.md), 이 문서는 **결과**만 다룬다.
새 테스트를 하면 여기에 한 줄을 더한다. 기기별 원본 표는 [car-tests/](car-tests/)에 있다.

표기: ✅ 확인됨 · ❌ 틀린 것으로 확인됨 · ⚠️ 조건부 · ⏳ 미실시

---

## 1. 한눈에: 가정별 상태

번호는 [implementation-proposal.md §4](implementation-proposal.md)의 가정 번호.

| # | 가정 | 상태 | 어디서 | 근거 |
|---|---|---|---|---|
| 1 | 라우트 없는 VpnService tun 주소(100.99.9.9)로 핫스팟 클라이언트가 폰 서버에 접속된다 | ⚠️ **조건부 통과** — 서버 소켓이 **shell uid(2000)** 일 때만. 앱 uid 소켓은 ❌ | B (S26U + 노트북) | §3.2, §3.3 |
| 2 | 테슬라 2026.26 브라우저가 `http://100.99.9.9`를 열고 MSE H.264를 디코딩한다 | ✅ **실차 확인 (2026-09-10, Model Y 2026.26, 빌드 `1424f30`, report #9)**: 페이지 열림, MSE H.264 디코드 5초 120프레임 24fps. 핫스팟 주소(10.207.x.x)는 차단 → tun 우회가 필요한 이유도 실측 | C | §4, car-tests/model-y |
| 3 | shell 권한으로 띄운 scrcpy 서버 포크가 갤럭시에서 VD 생성 + 타 앱 실행 + 터치 주입이 된다 | ✅ M0 (stock scrcpy 4.1, 2026-09-05, 8/8 항목): VD 생성·앱 실행·터치·IME 로컬·UHID 한글·`--turn-screen-off --stay-awake`로 폰 화면만 끄기·서버 단독 기동 모두 됨. 전원 버튼 화면 OFF는 전체 정지. 단 "앱 자신의 APK를 `app_process`로 shell uid에서 실행"은 ✅, **앱이 내장 ADB로 직접 띄우는 것도 ✅** | B | §3.3, §3.4 |
| 4 | 오디오 캡처(`output`/`playback`)가 One UI 8에서 된다 | ✅ `output`: 원격 재생 + 폰 무음. `playback --audio-dup`: 양쪽 재생 (M0 2026-09-05) | B | car-tests/s26u |
| — | 폰 화면만 끄고 VD를 유지할 수 있다 (`--turn-screen-off --stay-awake`) | ✅ M0 4번 (충전 중). 앱 구현: `requestDisplayPower` 경로는 ❌ "전환 실패"(d98be88) → scrcpy와 같은 SurfaceControl 경로로 교체, 폰 ⏳ | B | car-tests/s26u, §3.6 |
| 5 | WS 간헐 실패가 재시도로 해결된다 | PC ✅ (거부 34%·절단 5초마다 → 15초 내 복구) / 실차 ✅ 핸드셰이크 20/20 ×3회(24~25ms) — 2026.26에서는 간헐 실패 자체가 안 보임 | A → C | §2.3, §4 |
| 6 | MSE 지연이 터치 조작에 견딜 수준(<300ms) | PC ✅ (fps ≥ 25, lag < 300ms) / **실차 ✅ lag 61ms, 24fps** (report #9). 본 화면 영상·터치도 사용자 확인 "전부 작동" | A → B/C | §2.3, §4 |
| — | 앱 하나(APK)에 shell 서버 dex를 넣고 `CLASSPATH=<base.apk> app_process`로 실행할 수 있다 | ✅ uid=2000, build id 검증 동작 | B | §3.3 |
| — | Kadb(순수 JVM ADB 페어링)로 NDK 없이 갈 수 있다 | ✅ POM 확인(okio, spake2-java, hiddenapibypass, BouncyCastle). 코드는 M3에서 | A | dev-plan |
| — | 매 CI 빌드의 APK를 덮어 설치할 수 있다 | ✅ 고정 debug keystore 커밋 후 | B | §3.1 |
| — | Kadb 페어링 + NsdManager `_adb-tls-pairing` 발견이 One UI 8(Android 16)에서 된다 | ✅ 2026-09-05 빌드 `15ea085`: 포트 39727 발견 → 페어링 성공 (수동 입력 불필요) | B | §3.4 |
| — | 무선 디버깅을 핫스팟 상태에서 켤 수 있다 | ❌ Wi-Fi 클라이언트 연결 중에만 토글 활성 (사용자 실측 2026-09-05) → 서버는 Wi-Fi에서 분리 실행, 차에서는 adb 불사용 | B | dev-plan M3 |
| — | 분리 실행(`daemon=true`) 서버가 adb 스트림·Wi-Fi·무선 디버깅 종료·화면 OFF 후에도 유지된다 | ⚠️ **조건부 통과 — USB 디버깅 토글이 켜져 있을 때만.** 꺼져 있으면 Wi-Fi가 끊길 때 adbd가 멈추고 init이 adbd의 cgroup(`/system/uid_0/pid_N`)을 통째로 SIGKILL → 서버 사망 (2026-09-05 `306d41a`). shell은 cgroup을 못 벗어남(`d98be88`에서 전 경로 EACCES). 켜 두면 핫스팟 전환 후 유지 + 노트북에서 `100.99.9.9:3333` 접속 ✅. 하룻밤·재부팅은 ⏳ | B | §3.4, §3.5 |

| — | **One UI 8(Android 16)의 adbd가 `tcpip:<포트>`를 받아 준다** | ✅ **확인됨 (2026-09-05, 빌드 `add8b48`)**: `restarting in TCP mode port: N` → 1초 만에 `uid=2000(shell)` 재접속. 되돌리기(`usb:`)도 동작. 2회 재현(36788, 44161) | B | [hotspot-only.md](hotspot-only.md) §2 |
| — | **TCP 모드 포트가 Wi-Fi·무선 디버깅 없이도 살아 있고, 그것만으로 서버를 재기동할 수 있다** | ✅ **확인됨 (2026-09-05 10:12, Wi-Fi OFF)**: `서버 종료` 후 `TCP 모드 포트 44161: 열려 있음` → `mDNS _adb-tls-connect 레코드 없음`(= 무선 디버깅 꺼짐) → `adb 접속(TCP 모드): uid=2000(shell)` → `서버 기동 확인 (500ms)`. **무선 디버깅 없이 shell을 얻어 서버를 다시 띄운 첫 사례** | B | §3.8 | — 근거는 Shizuku #864(S21, Android 14)의 보고. One UI 8의 adbd가 받아 주는지, 전환 후 무선 디버깅이 꺼지는지, "USB 디버깅 허용" 다이얼로그가 뜨는지 모두 미확인 | B | [hotspot-only.md](hotspot-only.md) §2 |
| — | `persist.adb.tcp.port`를 shell이 설정할 수 있어 재부팅 후에도 adbd가 포트를 연다 | ❌ **막힘 (2026-09-05 실측)**: `Failed to set property 'persist.adb.tcp.port' to '36788'. See dmesg for error reason.` — 2회 모두 동일. **콜드 부팅 후 Wi-Fi 1회는 남는다**(Tesor·Castla와 동일) | B | [hotspot-only.md](hotspot-only.md) §3 |
| — | 테소르(Tesor)가 Wi-Fi 없이 동작한다 | ❌ 테소르는 Shizuku 위에서 돈다(설치 안내 2단계). 비루팅 Shizuku는 재부팅 시 종료되고 무선 디버깅 = Wi-Fi로만 다시 시작된다 — 같은 제약 | 문헌 | [hotspot-only.md](hotspot-only.md) §1.4 |
| — | **앱이 USB 디버깅 토글을 스스로 켤 수 있다** (`pm grant … WRITE_SECURE_SETTINGS` 를 shell 링크가 뜰 때 앱이 직접 실행 → 이후 shell 없이 `Settings.Global.adb_enabled=1`) | ✅ **확인됨 (2026-09-14 20:56, S26U/One UI 8, Wi-Fi 없음, 빌드 `f8e7e89`)**: 토글 꺼진 채 위젯 ON → `USB 디버깅: 꺼져 있어서 켰음` → 5초 뒤 `TCP 모드 포트 31432: 열려 있음`(adbd 가 `service.adb.tcp.port` 를 읽어 재개방) → `adb 접속(TCP 모드): uid=2000` → 서버 기동. **손댄 것 없이 위젯 하나로 끝난 첫 사례.** grant 가 이미 있었으므로 ① 도 통과. ④ 재부팅 뒤 grant 유지는 아래 행(21:28)에서 확인. 그 5초 동안 링크 루프가 "Wi-Fi 없음" 을 찍던 것은 `BulkControl.awaitTcpPort` 로 막음 | B | `UsbDebugging.kt`, `BulkControl.allOn` |
| — | **앱이 무선 디버깅 토글도 스스로 켤 수 있다** (`adb_wifi_enabled=1`, 같은 grant) — 재부팅 뒤 TCP 포트가 없을 때 남는 유일한 손작업이 이것 | ✅ **확인됨 (2026-09-14 21:28, S26U/One UI 8, 재부팅 직후, 빌드 `870b7a5`)**: 재부팅 → Wi-Fi 만 켬 → 위젯 ON → `무선 디버깅: 꺼져 있어서 켰음` → 2초 뒤 `mDNS _adb-tls-connect: 192.168.219.107:35091` → `adb 접속(mDNS): uid=2000` → `restarting in TCP mode port: 31432` → 서버 기동. 위젯 누른 뒤 **7초, 개발자 옵션 안 열음.** AdbService 는 앱의 쓰기를 토글과 똑같이 받는다. 같은 로그의 `USB 디버깅: 이미 켜져 있음` 으로 **grant 가 재부팅을 넘는 것(④)도 확인.** 재부팅 뒤 남는 손작업은 Wi-Fi 켜기 하나 | B | `UsbDebugging.ensureWirelessOn`, `ShellServerLink` 루프 |
| — | **최초 설치 절차가 넷으로 끝난다**: 개발자 옵션 → 무선 디버깅 ON → 페어링 → 시작 (USB 디버깅·TCP 모드·권한은 첫 세션이 알아서) | ✅ **확인됨 (2026-09-14 21:53, S26U/One UI 8, 빌드 `af20df9`, 재설치 후)**: `페어링 성공` 21:53:58 → `mDNS _adb-tls-connect: …:37543` 21:54:09 → `restarting in TCP mode port: 43472` → `WRITE_SECURE_SETTINGS 부여됨` → `서버 기동 확인` 21:54:13. **페어링부터 서버까지 15초, 손댄 토글은 무선 디버깅 하나.** ⚠️ 그 직전 시도(21:46, 빌드 다름)에서는 토글이 켜진 채 `_adb-tls-connect` 가 15초 창 두 번 동안 안 잡혔다 — 재현 안 됨, 원인 미확정. 다시 나오면 무선 디버깅 화면의 'IP 주소 및 포트' 를 '포트 수동…' 에 넣어 mDNS(앱) 문제인지 adbd(폰) 문제인지 가른다 | B | README "폰에서 쓰는 순서", `ui/SetupSteps.kt` |

| — | ~~앱에서 핫스팟을 켜고 끌 수 있다~~ (`TetheringManager.startTethering`, shell uid) | ❌ **못 한다 (2026-09-13)**. 가상 폰(API 36)과 S26U/One UI 8 둘 다, 면제를 단 요청도 면제 없는 재시도도 `NO_CHANGE_TETHERING_PERMISSION(14)`. AOSP `TetheringService` 는 `exemptFromEntitlementCheck` 를 그대로 `onlyAllowPrivileged` 로 넘기므로 **면제를 달라는 요청 자체가 `TETHER_PRIVILEGED` 를 요구하고**, 그것이 없으면 WRITE_SETTINGS 경로에 닿지도 못한다. 면제를 빼고 다시 물어도 같은 답이었다. **→ 바꾸는 코드는 제거했다.** 핫스팟은 사용자가 설정에서 켜고, 앱은 상태만 읽는다 | A+ / B | §1 아래 주 |
| — | (위 항목의 근거) | Shell 패키지가 `TETHER_PRIVILEGED`·`WRITE_SETTINGS` 를 **선언**하지만(AOSP 매니페스트) 부여받지는 못한다 — 선언과 부여는 다르다는 것이 이 실측이 보여 준 것이다. Castla 는 같은 uid(Shizuku)에서 된다고 적어 두었으나 우리 두 기기에서는 재현되지 않았다(prior-art §4). `cmd wifi start-softap` 은 root 전용이라(WifiShellCommand) uid 2000 으로는 애초에 불가 | A+ / B | §1 아래 주 |
| — | ~~통신사 잠금 기기에서 entitlement 를 우회할 수 있다~~ | — **묻지 않게 됐다.** 우회는 `TETHER_PRIVILEGED` 가 있을 때만 쓸 수 있는데 그것이 없다(위). 켤 때마다 `tether_dun_required` 를 0 으로 바꾸던 것도 함께 제거했다 — 되지도 않는 기능 때문에 사용자 폰의 설정을 영구히 바꾸고 있었다 | — | — |
| — | 핫스팟 켜짐/꺼짐을 **읽을** 수 있다 (`getWifiApState` → `getTetheredIfaces` → 인터페이스 이름) | ✅ **가상 폰**: 첫 경로가 답했다(`via=getWifiApState=11`). 셋 다 실패하면 `known=false` 이고 그때는 "꺼짐"이라 하지 않는다. 서버가 없을 때는 앱이 직접 인터페이스를 본다(`HotspotState`). One UI 에서 어느 경로가 답하는지는 ⏳ | A+ / B | — |
| — | **차 브라우저(2026.26, Chrome 148)에서 워커가 OffscreenCanvas 에 WebGL2 로 직접 그릴 수 있다** — 메인 스레드는 터치·UI 만 | ✅ **실차 확인 (2026-09-17 09:26 UTC, Model Y 2026.26, 빌드 `f0e43e2`, report #59)**: `offscreen:true`, `lastError` 없음, **h264 30fps · lag 10~13ms · rtt 5~11ms**, 드롭 0 · 적체 0 · 복구 0, rAF 페이싱으로 건너뛴 그림 8/680 (1%). 같은 자리에서 잰 MSE(/diag #58)는 lag 142ms — 캔버스 경로가 10 배 이상 낮다 | C | car-tests/model-y §5 |
| — | **S26U 의 `c2.qti.avc.encoder` 가 Constrained Baseline + CBR 을 받는다** (`bitrate_mode=cbr` 기본값) | ✅ **확인됨 (2026-09-17, 빌드 `f0e43e2`)**: `encoderProfile: constrained-baseline+cbr 요청`(거부 아님), SPS `avc1.42C020`. GOP 10 초도 그대로: 5303 프레임에 키프레임 13 | B/C | `H264Encoder.open` |
| — | **13 바이트 터치(차의 시계)·MOVE 묶음(kind 5)·ping(kind 6)·키프레임 요청(kind 4) 이 One UI 8 에서 주입·응답된다** | ✅ **실차 (2026-09-17, report #59)**: `injected 18, injectFailed 0, controlErrors 0`, ping 왕복 5~11ms. 이 세션은 탭만 있었고 드래그가 없어 **묶음(batches)은 0** — 플링 세기가 고른지는 ⏳(다음 방문에서 스크롤·플링을 해 보고 리포트의 `touch.batches` 와 체감을 함께 적는다). 키프레임 요청은 드롭이 없어 한 번도 안 나갔다(`keyframeRequests 0`) | C | `InputInjector`, `StreamSession.onControl` |
| — | 워커 WebGL 이 안 되는 브라우저에서 메인 스레드로 물러나 그림이 나온다 (`fallBackToMainThread`) | ⏳ 차에서는 필요 없었다(위). 코드 경로만 있고 실측된 브라우저는 아직 없다 | — | `renderer/h264.ts` |
| — | **런타임 인코더 재설정** (`POST /api/encoder?width=&height=&fps=&bitrate=`): 앱은 그대로, 인코더만 교체, 크기가 바뀌면 VD 도 `resize` | ✅ **실차 확인 (2026-09-17 10:00 UTC, 빌드 `bb52071`, report #61)**: 720p60 → 900p30 → 900p60 → 1080p30 네 번 모두 앱(유튜브)을 유지한 채 이어짐, 1080p 에서도 CBR+Baseline 수락(`avc1.42C02A`). 영상이 도는 동안의 60fps·1080p 적체는 ⏳(측정 화면은 정지 화면이라 못 봤다). A+ `09-encoder` 는 CI 가 막혀 미실시 | C | car-tests/model-y §6 |
| — | **끝에서 끝까지 지연 측정** (차가 `LatencyProbeActivity` 를 띄우고 터치 → 가운데 밝기 뒤집힘까지) | ✅ **실차 (report #61)**: 720p30 **43ms**, 720p60 **37ms**, 900p30 46, 900p60 45, 1080p30 48 (각 10 회 중앙값). 60fps 가 6~9ms 를 벌고 해상도는 3~5ms 만 더한다. 측정 뒤 감시자가 "폰이 가져감"을 잘못 띄운 것은 다음 빌드에서 고침(`transient`) | C | car-tests/model-y §6 |

**설계에 반영된 결론:** 가정 1의 조건 때문에 HTTP/WS 서버는 앱이 아니라 shell 프로세스에서 돈다
([dev-plan.md 아키텍처 3항](dev-plan.md)). 앱은 tun 주소 유지·페어링·기동·UI만 맡는다.

---

## 2. A층: PC / CI에서 확인한 것

### 2.1 빌드 산출물
- `./gradlew :app:assembleDebug`가 이 원격 컨테이너(JDK 21, Gradle 8.14.3, SDK 36)와 GitHub Actions(ubuntu, JDK 17) 양쪽에서 통과.
- APK 내용 확인(unzip): `assets/web/{index.html,diag.html,style.css,main.js,diag.js}`, `assets/clips/test-720p30.cmp4`(1.5 MB, 240프레임),
  `com.carcast.server.Server`·`com.carcast.core.ServerMain` 클래스가 dex 안에 있음. 크기 약 16 MB.
- CI: `android.yml`(APK 아티팩트 `carcast-debug-apk`, 단위 테스트), `web.yml`(타입체크 + Playwright). 마지막 확인 시점(커밋 `75ff76a`) 둘 다 녹색. 경로 필터로 웹만 바꾸면 APK 빌드는 돌지 않음.
- 고정 debug keystore(`app/keystore/debug.keystore`)를 커밋. 이전에는 러너마다 키가 달라 두 번째 APK가 "앱이 설치되지 않음"으로 실패했음(§3.1).

### 2.2 단위 테스트
| 모듈 | 테스트 | 확인한 것 |
|---|---|---|
| `mux` | `AnnexBTest` | NAL 분리, `first_mb_in_slice` 기준 액세스 유닛 묶기, 키프레임 판정, AVCC 변환(SPS/PPS/AUD 제거) |
| `mux` | `SpsTest` | 테스트 클립 SPS → 1280x720, profile 66 level 31, codec 문자열 `avc1.42C01F` |
| `mux` | `Fmp4WriterTest` | init 세그먼트(ftyp/moov, duration 0, mehd 없음)와 프래그먼트(moof+mdat, tfhd default-base-is-moof, tfdt v1, trun data_offset) 박스 구조 |
| `core` | `JsonTest` | `/api/status` JSON 직렬화(이스케이프, 중첩) |
| `core` | `ServerMainTest` | 인자 파싱(`port=`, `apk=`), APK zip에서 assets 읽기, `..` 차단, `/`·`/api/status`·404 응답, extraStatus 병합, `POST /api/report` 저장·비JSON 거부·256KB 초과 413·`GET /api/reports`·status의 `lastReport` |
| `core` | `ControlMessageTest` | 웹 터치/키/텍스트 패킷 파싱(정규화 좌표, UTF-8), 잘린·미지 패킷 거부 |
| `core` | `EncodedH264SinkTest` | 인코더 출력(config 버퍼 + Annex-B AU, 원본 .h264에서 추출) → init 세그먼트 1개 + 프레임당 moof/mdat 1개, 첫 패킷 TYPE_KEY, pts 유지, SPS/PPS 인라인 키프레임만으로도 부트스트랩 |
| `shell-server` | `HotspotTest` | **아무도 답하지 않을 때 `known=false`** — "꺼짐"으로 단정하지 않는 것, `via` 로 무엇이 답했는지 남기는 것, 그리고 옛 빌드의 쓰기 요청을 이유와 함께 거절하는 것 |
| `app` | `BulkControlTest` | 일괄 끄기의 **순서**(서버 → 세션)를 가짜 loopback 서버로 확인 — 킬 스위치가 그 서버로 가는 요청이라 세션을 먼저 내리면 끌 방법이 사라진다. 핫스팟은 건드리지 않는다. 두 번 누르면 두 번째는 거절 |
| `app` | `CarCastWidgetTest` | 위젯 스위치: 끄기는 VPN 동의를 기다리지 않고, 켜기는 동의 없이 시작하지 않는다. 세션만 살고 서버가 죽은 상태를 "켜짐"으로 보이지 않는다 (상태를 가진 쪽이 바뀔 때마다 다시 그려 주는 것은 `StreamService`·`CarVpnService` 의 몫) |
| `core` | `ExtraApiTest` | 호스트가 더한 `/api` 경로가 요청을 받고 그 답이 서빙된다. null 을 주면 코어 경로로 넘어간다 — 호스트 경로가 `/api/status` 를 가려 버리면 차가 멈춘다 |
| `core` | `SelfSignedCertTest` | 손으로 쓴 X.509(DER)가 **진짜 파서에 통과하는가**: `CertificateFactory` 파싱, 자기 서명 검증, SAN 에 `100.99.9.9`·loopback·핫스팟 주소, extKeyUsage serverAuth, CA 아님. 그리고 재기동 후 같은 인증서(차가 매번 다시 경고를 넘지 않게), 깨진 키스토어는 교체(페이지를 잃지 않게), 마지막으로 **진짜 TLS 핸드셰이크로 `/api/status` 가 나오는가** |
| `core` | `StaleFrameTest` | 영상 소켓 앞의 큐가 **지연 예산**이라는 계약: 4장을 넘으면 P프레임을 버리고(다음 키프레임까지) 소스에 IDR 을 부탁한다, 키프레임은 언제나 보낸다(그것이 복구를 끝내는 것), 따라오는 손님은 한 장도 안 잃는다. 예전 계약(64장 = 2초를 쌓아 두었다가 전부 늦게 배달)이 리포트 #27 의 모양이었다 |
| `shell-server` | `H264LevelTest` | 크기·fps 에 맞는 H.264 레벨을 고르는가(Annex A Table A-1). 예전에는 Baseline 을 요청할 때 레벨이 **3.2 로 고정**돼 있었는데, 차가 고를 수 있는 여섯 칸 중 넷이 그것을 넘는다(900p·1080p). 넘으면 `configure` 가 거부되고 인코더는 **벤더 기본값(High)** 으로 물러나며, 평문 경로의 WASM 디코더는 High 를 못 읽는다 — 증상은 "화질을 올렸더니 화면이 멈춤" |
| `app` | `CertInstallTest` | 만료된 인증서를 **폰 위에서** 갈아끼우는 길(`CertInstall`): 두 파일로 주는 출처(local-ip.sh)와 한 덩어리로 주는 출처를 둘 다 다루고, PEM 이 아닌 것은 **서버에 닿기 전에** 멈추고, 개인키를 평문(http)으로 받지 않고, 서버의 거부 사유를 지어내지 않고 그대로 옮긴다. 마지막 한 건은 진짜 소켓으로 loopback 에 POST |
| `core` | `ReportStoreTest`, `JsonObjectCheckTest` | 보고서 메모리 보관(최대 50), 디렉터리 저장 후 재기동 시 복원·id 이어감, JSON 객체 구조 검사(중첩·문자열 속 괄호·꼬리 텍스트), 이스케이프 복원 |
- 먹서 산출물은 ffmpeg(static 7.0.2)로 디코드 검증: 240프레임 정상 디코드.

### 2.3 Playwright (테슬라 브라우저 프로필)
환경: Chrome for Testing **148.0.7778.178**(Playwright 번들 Chromium은 H.264가 없어 사용 불가), UA `… Chromium/148 … Tesla/2026.26`,
프로젝트 `tesla-dpr1`(1900x1040) / `tesla-dpr1.5`(1266x693), `--host-resolver-rules`로 `100.99.9.9→127.0.0.1` 매핑과 `192.168.*`·`10.*`·`172.16.*` DNS 실패(사설 IP 차단 흉내), http 오리진(secure context 아님).

| spec | 확인한 것 | 가짜 폰 | JVM shell 서버(APK assets) |
|---|---|---|---|
| `diag.spec` | UA 표시, `isSecureContext=false`, MediaSource·`avc1.42E01E` 지원, WS 20회 중 ≥18 성공, 5초 프로브에서 >30 프레임, 에러 없음, 사설 주소 대조군이 `reachable`이 아님, 결과가 `POST /api/report`로 저장되고 `/api/reports`·`/api/status.lastReport`에 나타남 | ✅ | ✅ (loopback 오리진은 secure-context 검사만 생략) |
| `stream.spec` | MSE 렌더러 fps ≥ 25, pts 대비 렌더 지연 < 300ms, 10초 무정지, `?renderer=mjpeg` 강제 | ✅ | ✅ |
| `input.spec` | 클릭 → 서버가 받은 정규화 좌표(레터박스 보정) 검증, 네비 바 → Android 키코드 | ✅ | skip(가짜 폰 전용 API) |
| `reconnect.spec` | 핸드셰이크 거부 34% + 150ms 지연 + 5초마다 소켓 절단 하에서 15초 내 영상 복구 | ✅ | skip |
| `quality.spec` | 화질 사다리가 **렌더러에 따라 갈리는가**: 평문(WASM)에서는 다섯 칸이고 `1080p60` 이 없다, 하드웨어(webcodecs)에서는 여섯 칸이다, 그리고 **하드웨어 전용 설정으로 도는 폰에 평문으로 들어오면 한 단계 내려 준다**(폰은 차가 고른 값을 파일로 기억하므로 실제로 생기는 조합이고, 그 증상은 멈춘 화면이라 자동 내리기가 손대지 못한다). 더해서 자동 내리기와 끝에서 끝까지 지연 측정 | ✅ | skip(가짜 폰 전용 API) |
| `secure-context.spec` (webcodecs) | secure context 에서 렌더러가 **webcodecs 로 골라지고**, 프레임이 나오고, 폰 인코더가 High 로 올라가는가. 하드웨어가 없는 자리(이 컨테이너)에서는 `stats.hardware=false` 로 소프트웨어 WebCodecs 로 내려간다 — 그래도 lag 5.7ms(WASM 은 10ms) | skip(TLS 없음) | ✅ |
| `secure-context.spec` | **자체서명 인증서를 넘긴 https 오리진이 secure context 인가**, 거기서 `VideoDecoder` 가 보이고 `isConfigSupported` 표가 채워져 리포트에 실리는가. 평문에서는 "못 물었다"를 말하고 https 주소를 안내하는가 | skip(TLS 없음) | ✅ (2026-09-17, Chrome 148) |

결과(2026-09-17, `npm run e2e`): 가짜 폰 대상 세 프로필 합쳐 **108 통과 / 36 skip**, JVM으로 띄운 shell
서버(`BASE_URL=http://127.0.0.1:3399`, APK의 assets 그대로) 대상 **9 통과 / 39 skip**(가짜 폰 전용 API 를
쓰는 것들이 빠진다; 대신 `secure-context.spec` 네 건은 여기서만 돈다).
같은 서버 코드가 폰의 shell 프로세스에서 돌기 때문에, 폰에서 남는 미검증 요소는 프로세스 환경과 네트워크뿐이다(§3.3에서 확인).

이 층에서 잡은 문제와 수정: 클립 루프 시 `tfdt`가 원래 pts로 남아 MSE 타임라인이 되감기던 10초 정지(재스탬프),
재연결 후 새 MediaSource가 일시정지 상태로 남던 문제(`wantPlay` + `play()`), AbortError 무시, 백오프 상한 2초.

### 2.4 A+층: 가상 폰 (Android 16 / API 36 에뮬레이터, GitHub 러너)

`tools/virtual-phone` 이 에뮬레이터에 APK 를 깔고 **폰에서와 같은 명령으로 같은 dex 를 shell uid 로** 띄운다.
첫 실행(빌드 `f2c9af1`, 2026-09-11)에서 확인된 것 — 지금까지 전부 B층(실기기)에서만 볼 수 있던 것들이다:

| | 결과 |
|---|---|
| 프로세스 | `process=shell`, `uid=2000`, `build=f2c9af1` |
| 가상 디스플레이 (M4) | `source=display`, `displayId=2`, 1280x720/160, 인코더 `c2.android.avc.encoder` |
| 앱 실행 | `start app com.android.settings/.Settings on display 2 (started, was on display null)` |
| **앱 충돌 (M4-b)** | 폰이 `am start --display 0` 으로 가져감 → 감시자가 `폰이 앱 … 을 가져감 (display 0)` 기록, `appOnPhone:true`. 차에서 다시 ▶ → `(restarted, was on display 0, restart=auto)`, `appOnPhone:false` |
| 입력 주입 (M5) | `injected:8`, `injectFailed:0` — `am stack list` 파서도 이 ROM 에서 동작 |
| 화면 전원 (M7) | `physical display power off: true` / `on: true` — SurfaceControl 경로가 에뮬레이터에서도 먹는다 |
| cgroup 탈출 | 실기기와 같이 전 경로 `EACCES` (§3.5 와 동일) |

**여기서 나온 새 사실 — 빈 가상 디스플레이는 한 장도 내지 않는다.**
앱을 하나도 띄우지 않은 가상 디스플레이에 붙으면 8초 동안 패킷이 **0개**였고(`frames=0, keyframes=0`),
앱을 띄운 직후부터 흐르기 시작했다(13초에 88프레임). 합성할 내용이 없으면 인코더에 들어갈 버퍼도 없고,
`REPEAT_PREVIOUS_FRAME_AFTER` 는 **직전 프레임이 있어야** 반복하기 때문이다. 결과적으로 차에서 페이지를 열면
▶ 를 누르기 전까지는 init 세그먼트조차 받지 못한다 — 차 화면의 "폰 무응답"이 이 상태다.

**A+ 는 처리량을 물을 수 있는 자리가 아니다 — 가상 디스플레이는 픽셀이 바뀔 때만 낸다.**
빌드 `fa2e6bd` 실행에서 정지 화면 7분 동안 총 129프레임(**0.3fps**)이었고, 앱이 떠 있어도 화면이 멈추면
8초에 한 조각까지 떨어졌다. 처음에 잰 프레임 간격(p50 83ms)은 **앱 실행 애니메이션이 측정 창에 걸쳐 있던
값**이라 바닥을 보여 주지 못했다 — `REPEAT_PREVIOUS_FRAME_AFTER`(100ms)가 이 인코더에서 바닥을 만들지
않는다는 뜻이다. 그래서 기기 검사는 이제 `/ws/control` 로 화면을 흔들면서 본다(`lib.mjs` 의 `wiggle`):
그래야 "가상 디스플레이 → 인코더 → fMP4 → 클라이언트" 경로가 결정적으로 확인된다.
차 클라이언트는 정지 화면 상태에서 `video 1f 0fps` 로 멈추므로 **디코드 처리량 검사는 A+ 에서 건너뛴다**
(`NO_THROUGHPUT=1`; 처리량은 A 의 가짜 폰과 B 의 실기기에서 본다). A+ 가 보는 것은 경로와 상태다.
실기기의 하드웨어 인코더가 정지 화면에서 바닥을 지키는지는 B 에서 `MAX_GAP_MS=500 npm run device` 로 확인할 것 —
2026-09-10 실차 멈춤의 원인이었던 항목이다.

**전원 버튼 장부 되돌리기 확인:** 같은 실행의 최종 상태에 `powerReconciled: 1`, `panelState: "ON"`,
`forcedOff: false` — 📵 로 끈 뒤 전원 버튼이 패널을 켠 것을 감시자가 잡아 장부를 버렸다는 뜻이다.

**곁가지로 확인한 것 — 늦게 접속해도 재생된다.** 라이브 인코더는 자기 시계로 pts 를 찍으므로 서버가 한참
돌고 난 뒤 차가 붙으면 타임라인이 0 이 아니라 그만큼 뒤에서 시작한다(실측 131초). 클립(pts≈0)으로는 절대
안 나오던 상황이라 새로 재현해 봤고(`tools/fake-phone --pts-base`), 차 클라이언트는 `lag 32ms`로 정상
재생했다. 회귀 검사로 `tests/e2e/tests/pts-base.spec.ts` 에 남겼다.

**여전히 A+ 에서 못 보는 것:** 가정 1(VpnService 주소 배달, 여기서는 `adb forward` 로 붙는다), 핫스팟,
무선 디버깅 페어링·TCP 모드, One UI 전용 동작(INJECT_EVENTS 정책, 패널 동작, 도즈 세부), 발열·배터리.
전체 목록: [tools/virtual-phone/README.md](../tools/virtual-phone/README.md).

---

---

## 3. B층: Galaxy S26 Ultra (SM-S948N, Android 16 / One UI 8) + 노트북

날짜 2026-09-04 ~ 09-05. 폰 핫스팟(swlan0 `10.136.114.168/24`, 상위망 rmnet_data2), 노트북 Windows(Wi-Fi `10.136.114.7`), 앱 tun0 `100.99.9.9/32`.
원본 표: [car-tests/s26u-one-ui-8.md](car-tests/s26u-one-ui-8.md).

### 3.1 설치·기동
| 확인 | 결과 |
|---|---|
| APK 설치, 시작 → VPN 동의 → `tun: UP` | ✅ (빌드 `0c5dc8b` 이후 모든 빌드) |
| 두 번째 APK 덮어 설치 | ❌ "앱이 설치되지 않음" → 원인: CI 러너마다 다른 debug 키. 고정 keystore 커밋(`73541fb`) 후 ✅ (한 번 삭제 후 재설치 필요했음) |
| 인터페이스 목록 | rmnet_data1 `192.0.0.2`, rmnet_data2 `192.0.0.4`(CLAT/IPv6 망), swlan0 `10.136.114.168`, tun0 `100.99.9.9` |
| 앱 내 자가 테스트(127.0.0.1, tun, 각 iface :3333, gstatic 204) | 전부 HTTP 200/204 ✅ — 폰 안에서는 어디로든 닿음 |
| 앱에서 `ip rule`·`/proc/sys/net` 읽기 | ❌ SELinux (`Cannot bind netlink socket: Permission denied`, `/proc/sys` FileNotFound). 라우팅 진단은 adb에서만 가능 |

### 3.2 가정 1: 앱 uid 서버로는 실패 (원인 규명 과정)
서버가 **앱 프로세스**(uid 10635)에 있던 빌드들(`~de168a9`)에서:

| # | 실험 | 결과 | 의미 |
|---|---|---|---|
| 1 | 폰 자신의 브라우저 → `http://100.99.9.9:3333` | ✅ 영상 재생 | 주소·서버 자체는 정상 (lo 경로) |
| 2 | 노트북 → `http://10.136.114.168:3333/api/status` | ✅ | 핫스팟 → 폰 서버 경로 정상 |
| 3 | 노트북 → `ping 100.99.9.9` / `tracert` | ✅ TTL 64, 1홉 | tun 주소로 오는 패킷은 폰 커널까지 도착하고 응답도 나감 |
| 4 | 노트북 → `http://100.99.9.9:3333` | ❌ `ERR_CONNECTION_TIMED_OUT`, 서버에 accept 로그 없음 | TCP 3-way 핸드셰이크가 완성되지 않음 |
| 5 | VPN 끄고 노트북 → `http://192.0.0.2:3333` | ✅ | "핫스팟 아닌 다른 인터페이스의 로컬 주소"도 되므로 인터페이스 불일치가 원인이 아님 |
| 6 | VPN 켜고 노트북 → `http://192.0.0.2:3333` | ✅ | VPN이 있어도 VPN 주소가 아닌 로컬 주소는 됨 → **VPN 주소로 향하는 TCP만** 막힘 |
| 7 | `allowBypass()` + `addDisallowedApplication(self)` (`9bed496`) | ❌ 변화 없음 | 정책 라우팅 규칙이 원인이 아님 |
| 8 | 리스너 소켓 `VpnService.protect()` (`147a45c`) | protect → true, ❌ 변화 없음 | 소켓 마크로도 안 됨 |
| 9 | 리스너를 `Network(LOCAL_NET_ID=99)`에 바인드 (`de168a9`) | ❌ 변화 없음 | ping 응답과 같은 마크를 줘도 안 됨 |
| 10 | `adb shell ip rule` / `ip route show table all` | 앱 uid 10635는 VPN uid 범위(`0-10634, 10636-…`)에서 제외, 핫스팟 서브넷은 테이블 1083, `fwmark 0x0/0x10000 lookup 1083` 규칙 존재, prohibit 없음 | 라우팅 상으로는 SYN-ACK가 swlan0으로 나가야 정상 |
| 11 | 접속 시도 중 `adb shell ss -tan` 15초 | `LISTEN`만, `SYN-RECV` 없음 | SYN이 TCP 리스너까지 오지 못함 |
| 12 | 접속 시도 전후 `/proc/net/netstat` | `ListenDrops` 526→526, `TCPSynRetrans` 불변, `IpExt InNoRoutes` 불변 | 리스너에서 버린 것도, 경로 실패도 아님 → **소켓 전달 직전 단계에서 소멸** |
| 13 | `adb shell dumpsys tethering` | 하드웨어 오프로드 disabled, BPF 오프로드 연결 0 | 테더링 오프로드가 가로챈 것 아님 |
| 14 | 노트북 `Get-NetRoute 100.*` / 어댑터 | 100.x 경로 없음, TunnelBear 어댑터는 끊김 | 노트북 쪽 원인 아님 |

**결론:** 위 조건(소켓 있는 프로토콜만, lo 제외, VPN 주소만, 앱 uid만)은 Android 14부터 netd가 거는
**VPN 주소 유입 차단 BPF 규칙**(ingress-discard: VPN 인터페이스 주소로 향하는 패킷이 VPN/lo 이외 인터페이스로 들어오면
소켓 전달 직전에 drop, 소켓 uid < 10000이면 검사 생략)과 정확히 일치한다. 앱 쪽에서 우회할 API는 없다.

### 3.3 가정 1: shell uid 서버로는 성공
| # | 실험 | 결과 |
|---|---|---|
| 15 | `adb shell "echo hello \| nc -l -p 3334"` (uid 2000) 후 노트북 `Test-NetConnection 100.99.9.9 -Port 3334` | ✅ `TcpTestSucceeded : True` — 규칙의 uid 면제 확인 |
| 16 | `adb shell 'CLASSPATH=$(pm path com.carcast \| cut -d: -f2) app_process / com.carcast.server.Server 75ff76a port=3333'` | ✅ `carcast-server uid=2000 build=75ff76a android=16`, 리스닝, 클립 송출. build id 불일치 거부 로직 동작 |
| 17 | 앱 화면 "서버:" | ✅ `응답 중 shell uid=2000` (앱이 127.0.0.1:3333 폴링) |
| 18 | 노트북 → `http://100.99.9.9:3333/api/status` | ✅ `accept 10.136.114.7:… → 100.99.9.9:3333`, `"process":"shell","uid":2000` |
| 19 | 노트북 Chrome → `http://100.99.9.9:3333/` | ✅ `video 클라이언트 접속 (1)`, `control 패킷 1개 (kind=1)` — 영상·터치 채널 모두 동작 |

**결론:** 가정 1은 "서버 소켓이 shell uid"라는 조건 아래 실기기에서 통과. 앱이 자기 APK를 `app_process`로 shell에서 띄우는 방식(가정 3의 전제)도 함께 확인됨.

### 3.4 M3: 앱 내장 ADB (빌드 `15ea085`, 2026-09-05)
| # | 확인 | 결과 |
|---|---|---|
| 1 | 집 Wi-Fi 연결 → 무선 디버깅 토글 활성 | ✅ (핫스팟만으로는 ❌ 비활성) |
| 2 | 앱 "무선 디버깅 페어링" → 알림 RemoteInput에 코드 입력 | ✅ `페어링 포트 발견: 39727` → `페어링 성공` (알림 띄운 뒤 약 10초) |
| 3 | 시작 → adb 접속 → 분리 실행 → `SERVER_UP` | ✅ 12:36:00 `adb 접속: uid=2000(shell) … context=u:r:shell:s0` → `launched pid=15372` → 1초 뒤 `/api/status` 응답 (`process=shell uid=2000 build=15ea085`). PC 없이 기동 확인 |
| 4 | Wi-Fi off + 핫스팟 on 후 서버 유지 | ✅ 전환 후 유지(이때는 USB 디버깅이 켜져 있었던 것으로 보임 — 조건은 §3.5). 핫스팟 노트북에서 `/diag` → `저장됨 #1` (앱이 띄운 서버로 가정 1 재확인 + 보고서 저장 경로 확인) |
| 5 | 화면 OFF 후 시간 경과 → 서버 유지 | ✅ 화면 끄고 시간이 지난 뒤 다시 열어도 `응답 중` (정확한 시간 미기록; 하룻밤은 ⏳) |
| 6 | "서버 종료" 킬 스위치, 재부팅 후 복구 | ⏳ |
| 7 | 재페어링 없이 새 빌드 덮어 설치 후 접속 | ✅ (삭제 후 재설치하면 앱 키가 지워져 재페어링 필요). 17:32에 같은 지문인데 adbd가 키를 거부한 사례 1회 — 재페어링으로 해결, 재발 시 추적 |

### 3.5 분리 실행 서버의 수명 (빌드 `306d41a`·`d98be88`, 2026-09-05)
| # | 확인 | 결과 |
|---|---|---|
| 1 | 앱의 분리 실행 명령이 실제로 도는가 | ❌ `c602d65`~`8af473a`: `pkill -f com.carcast.server.Server`가 그 명령을 실행하는 `sh -c` 자신(명령줄에 클래스명 포함)을 죽여 아무것도 실행되지 않음. 앱은 옛 `server.log`를 새 것처럼 읽음. ✅ `306d41a`: pid 파일 + `^app_process / …` 앵커 패턴으로 종료, 실행마다 `server-<epoch>.log` 새로 작성·이전 로그 삭제 → `launched pid=23227`, 모든 step 통과, 500ms 안에 응답 |
| 2 | Wi-Fi off + 핫스팟 on 후 서버 유지 (USB 디버깅 **꺼짐**) | ❌ 17:12:42 응답 → 17:13:07 `서버 응답 없음`, 17:13:22 무선 디버깅 꺼짐 확인. 로그에 크래시 없음(SIGKILL). 원인: USB·무선 디버깅이 모두 꺼지면 AdbService가 adbd를 `ctl.stop` → init `killProcessGroup` |
| 3 | 서버가 adbd cgroup에서 스스로 벗어날 수 있는가 | ❌ `d98be88` `step: cgroup`: `0::/system/uid_0/pid_1194`(adbd는 root) — `/sys/fs/cgroup/{,system/,system/uid_0/}cgroup.procs`, `/dev/cpuctl`, `/dev/blkio` 전부 EACCES. shell 권한으로 불가 → **USB 디버깅 토글 필수** (케이블 불필요, adbd를 살려 두는 용도) |
| 4 | USB 디버깅 켠 채 핫스팟 전환 → 노트북 `http://100.99.9.9:3333/` | ✅ 접속됨 (사용자 보고, 순서는 핫스팟 먼저/Wi-Fi 먼저 무관) |
| 5 | 이전 f37c6fd에서 uid 줄 직후 죽던 크래시 | 재현 안 됨 — 1번의 실행 버그로 이후 빌드가 한 번도 돌지 않아 생긴 착시였을 가능성. 방어 코드(step 마커·미처리 예외 기록)는 유지 |

### 3.6 M4·M5·M7: 가상 디스플레이 라이브 송출·터치·화면 끄기 (빌드 `d98be88`, 2026-09-05, 핫스팟 + 노트북)
| # | 확인 | 결과 |
|---|---|---|
| M4 | 노트북 브라우저에 폰 가상 화면 영상 | ✅ `source=display 1280x720 displayId=7 encoder=c2.qti.avc.encoder`, `frames=3716 keyframes=32`. ▶로 유튜브 실행 ✅ (`app=com.google.android.youtube/.app.honeycomb.Shell$HomeActivity`) |
| M5 | 클릭·스크롤·키보드 | ✅ `input=true injected=72 injectFailed=0 controlErrors=0` |
| M6 | 소리 | ⏳ 미구현 — 소리는 폰에서 남 (오디오 캡처는 M6에서) |
| M7 | 📵 폰 화면만 OFF | ❌ "폰 화면 전환 실패": `requestDisplayPower(0,false)`가 실패. scrcpy 4.1은 이 API를 `USE_ANDROID_15_DISPLAY_POWER=false`로 꺼 두고(#5530) `SurfaceControl.setDisplayPowerMode`를 쓴다 — M0에서 된 것은 그 경로. 같은 경로로 교체(다음 빌드), 폰 ⏳ |
| — | 노트북 `/diag` 보고 | `no-Tesla-UA, 1108x632@1.25, mse=O, ws 20/20 35ms, video 61f 0fps lag 3224ms` (진단 페이지 자체 측정; 본 화면은 영상 재생됨) |
| M4-b | 폰에서 쓰는 앱을 차에서 띄울 때 / 차에서 도는 앱을 폰에서 열 때 | ⏳ 사용자 보고(2026-09-10): 폰에서 앱을 쓰는 중이면 차 쪽이 "충돌", 폰에서 닫으면 정상. 코드 분석 결과 원인은 안드로이드의 task 재사용 — `am start --display N`이 폰의 task를 차로 **옮기고**, 폰 런처가 다시 폰으로 옮긴다(핑퐁). 대응: `/api/app`가 `am stack list`로 위치를 보고 다른 디스플레이면 `am start -S`로 재실행(기본 `restart=auto`), 워처가 `appDisplay`/`appOnPhone`을 상태에 실어 차 화면이 "폰이 가져감"을 표시. 폰 검증 절차: testing-guide.md §B "M4-b". 폰 ⏳ (특히 One UI 8의 `am stack list` 출력이 파서와 맞는지) **2026-09-17: 기본을 `never`(그대로 옮김)로 되돌림** — 강제 종료가 "보던 것을 잃고 다시 들어가야 하는" 불편이었고, 핑퐁의 진짜 문제(설명 없는 검은 화면)는 감시자·상태 패널이 이미 잡는다. `always`(새로 열기)는 길게 누르기·패널 버튼으로 남김. 가상 폰 e2e(app-conflict.spec)와 기기 검사(03-app)가 두 모드를 다 본다. 폰 ⏳: 옮겨진 유튜브의 재생 위치, 사용자 조작 없이 폰으로 돌아가는 경로가 있는지(testing-guide §B "M4-b" 1·2) |

### 3.7 아직 B층에서 안 한 것
### 3.8 TCP 모드: 무선 디버깅 없이 서버 재기동 (2026-09-05 10:12, 빌드 `add8b48`)

**Wi-Fi를 끈 상태**(그래서 무선 디버깅도 자동으로 꺼진 상태, 사용자 확인 2026-09-05)에서:

```
10:12:07 서버 종료 요청: {"ok":true}                      ← 서버를 일부러 종료
10:12:23 TCP 모드 포트 44161: 열려 있음                    ← 포트 유지됨
10:12:38 mDNS _adb-tls-connect 레코드 없음 — 무선 디버깅이 꺼져 있거나 아직 광고 전입니다
10:12:38 adb 접속(TCP 모드): uid=2000(shell) …             ← 무선 디버깅 없이 shell 획득
10:12:41 서버 기동 확인 (500ms)                            ← 재기동 성공
```

의미: **Wi-Fi도 무선 디버깅도 없는 상태에서 shell을 얻어 서버를 다시 띄울 수 있다.** 차 안에서 서버가 죽어도 복구된다는 뜻이고,
adbd가 계속 살아 있으므로 §3.5의 cgroup SIGKILL(=USB 디버깅 토글 필수 조건)도 성립하지 않는다 — **USB 디버깅 토글 요구는 재확인 후 삭제 예정**.
남은 제약은 콜드 부팅 1회뿐(`persist.adb.tcp.port` 거부).
아직 확인 안 된 것: 장시간·화면 OFF 후 포트 유지, USB 디버깅 토글을 꺼도 유지되는지(§3.5의 조건 삭제 근거), 핫스팟 클라이언트에서의 실사용.

- **2026-09-05 TCP 모드 전환 성공(빌드 `add8b48`, 09:56 및 10:02 두 차례):** 무선 디버깅으로 접속 → `tcpip:` → `restarting in TCP mode port: N` →
  1초 뒤 그 포트로 `uid=2000(shell)` 재접속 → 서버 분리 실행까지 정상(`New display: 1280x720/160`). "TCP 모드 끄기"의 `usb:`도 `restarting in USB mode`로 동작.
  `persist.adb.tcp.port`는 두 번 다 거부. **다음 확인: Wi-Fi를 끈 상태에서 그 포트가 유지되고, 그 상태로 서버를 재기동할 수 있는지.**
- **2026-09-05 TCP 모드는 아직 한 번도 실행되지 않음(빌드 `27d5b96`):** 사용자가 07:50:01에 opt-in을 켰지만 로그에 `adbd를 TCP 모드로 전환합니다`가 없다.
  원인은 루프 구조 — **서버가 살아 있으면 adb를 아예 건드리지 않으므로** 전환이 영원히 미뤄진다. 서버를 수동 종료한 뒤에는 Wi-Fi가 꺼져 무선 디버깅도 없어 접속 자체가 불가.
  다음 빌드에서 opt-in이 켜져 있으면 서버가 떠 있어도 adb에 접속해 전환한다(실패 2회로 상한).
  같은 로그에서 07:44:43 서버 사망 = **USB 디버깅 토글이 여전히 꺼져 있다**는 증거(Wi-Fi를 끄자 adbd가 멈추며 cgroup째 SIGKILL).
- **2026-09-05 복구 확인(빌드 `27d5b96`):** 수동 포트를 0으로 되돌리고 Wi-Fi에서 시작 → `mDNS _adb-tls-connect: 192.168.219.108:38731 (이 폰)` 한 건 발견 →
  `uid=2000(shell)` 접속 → `launched pid=22767` → 500ms 안에 응답. VD도 정상(`New display: 1280x720/160 (id=6)`, `input injector ready`, `encoder=c2.qti.avc.encoder`).
  아래 실패는 **포트 선택 버그였고 adbd/페어링 문제가 아니었음**이 확정. 같은 로그에 `TCP 모드 포트 36788: 닫힘` —
  **이전 `tcpip:` 전환은 재부팅을 넘기지 못했다**(전환 자체가 안 섰는지, 섰다가 재부팅에 사라졌는지는 여전히 미확정).
  `step: cgroup`은 여전히 탈출 실패 → USB 디버깅 토글 조건은 그대로 유효.
- **2026-09-05 실패 사례(빌드 `c1f29f9`):** 재부팅 뒤 `_adb-tls-connect`가 광고하는 포트(45517)와 사용자가 수동 입력한 포트(46303) 모두 `ECONNREFUSED`.
  같은 시간대에 **페어링은 두 번 성공**(포트 33633·32805) — adbd는 살아 있는데 connect 쪽만 거부. 그때 앱은 ① 발견한 첫 포트 하나만 시도하고
  ② 한 번 저장된 수동 포트가 mDNS를 영구히 가려서 스스로 회복하지 못했다. 다음 빌드에서 후보 포트를 전부 시도·기록하고, 수동 포트는 3회 실패 후 자동 삭제한다.
- **M8 TCP 모드(2026-09-05 구현):** 무선 디버깅으로 붙은 직후 `tcpip:<랜덤 고포트>` 전환 → loopback 재접속 → 서버 기동.
  확인할 것: ① adbd 응답 줄, ② 전환 후 `id`가 uid=2000인지, ③ **Wi-Fi를 끈 채** "서버 종료" 후 앱이 다시 띄우는지,
  ④ USB 디버깅 토글을 꺼도 서버가 유지되는지, ⑤ `persist.adb.tcp.port` 로그가 "설정됨"인지 "설정 불가"인지, ⑥ 재부팅 후 Wi-Fi 없이 붙는지.
- M7 📵 재검증 (`fdc2350`의 SurfaceControl 경로), M6 오디오(미구현).
- "서버 종료" 킬 스위치, 재부팅 후 Wi-Fi에서 "시작" 한 번으로 복구, 하룻밤 방치 후 유지.
- `BASE_URL=http://100.99.9.9:3333 npx playwright test`를 노트북에서 폰에 대고 실행(자동화된 fps·지연 수치).
- 5GHz 핫스팟에서 720p30 10분 연속(대역폭), 세로 고정 앱에서의 회전 동작.

---

### 3.9 실차 리포트 #26 (2026-09-11): 전원 버튼을 누르면 차가 무응답

주행 중 폰의 전원 버튼을 누르자 차 화면이 멈췄다. 리포트가 그대로 말해 준다:
`0fps lag 5ms frames 2763 packets 3194`, `idleMs 6940`, `appOnPhone false`, 소켓은 둘 다 열려 있고
`lastError` 는 비어 있다. 즉 **앱도 서버도 소켓도 멀쩡한데 프레임만 끊겼다.**

원인은 알려진 것이다: 전원 버튼은 기기를 통째로 재우고, 잠든 기기는 **모든** 디스플레이의 합성을 멈춘다 —
가상 디스플레이도 같이. 📵 가 SurfaceControl 로 물리 패널만 끄는 이유가 이것이다(§M0).

리포트에서 더 나쁜 사실 하나: `state: ""` — 차가 아무 설명도 하지 않았다. 상태 패널은 "앱이 없음"과
"폰이 가져감"만 알았지 "폰이 잠듦"은 몰랐다.

**고친 것(빌드 이후):**
- 차가 보고 있는 동안 폰이 잠들면 서버가 깨워서 **📵 상태로 바꾼다** — 운전자가 전원 버튼으로 원한 것이
  "어두운 폰"이지 "멈춘 차 화면"은 아니기 때문이다. 전원 버튼을 10초 안에 세 번 누르면 복구를 60초 멈추고
  폰을 켠 채로 돌려준다(폰을 정말 쓰려는 경우의 탈출구). `sleep_recovery=false` 로 끌 수 있고,
  `/api/status` 에 `sleepRecoveries`·`recoveryPausedMs` 가 실린다.
- 그래도 잠든 채로 남는 경우(탈출구를 쓴 뒤)를 위해 차에 `phone-asleep` 상태 패널을 넣었다 — 이유와
  "폰 깨우기" 버튼, 그리고 "전원 버튼 대신 📵 를 쓰라"는 안내.
- 가상 폰에서 확인: `폰을 재운 뒤 — interactive=true screenOn=false 되살린 횟수=2`, 그동안 차 화면은 끊기지 않음.

### 3.10 리포트 #27 과 상류 조사 (2026-09-11): 문제가 우리만의 것이 아니었다

#26 을 고친 뒤 받은 #27 은 `30fps lag 144ms idleMs 8 재접속 0 복구 0` 으로 **멀쩡해 보이는데** 사용자는
여전히 이상하다고 했다. 가릴 수 없었다 — 세션 리포트에 폰 쪽 상태가 한 줄도 없었기 때문이다. 기기가
잠든 것인지, 패널만 꺼진 것인지, 되살리기가 돌았는지가 원인을 가르는 정보인데 전부 빠져 있었다.
**→ 💾 가 `/api/status` 를 통째로 싣도록 고쳤고, 요약 줄에도
`폰 build=… 잠듦/깨어있음 화면ON/OFF 되살림N 활성유지N idleN` 이 들어간다.**

상류(scrcpy)를 뒤져 보니 **같은 문제가 열려 있다** — [scrcpy#6787](https://github.com/Genymobile/scrcpy/issues/6787):
물리 화면이 꺼지면 약 10초 뒤 가상 디스플레이 크기의 불투명한 검은 면이 덮이고, 그 아래에서 앱은 계속
그려진다. 회피책은 `stay_on_while_plugged_in`(충전 중에만, 우리도 이미 켬)과 주기적 `userActivity`(scrcpy 의
`--keep-active`) 둘뿐이다. 자세한 조사는 [prior-art.md](prior-art.md).

**→ 가져온 것:** 차가 보고 있는 동안 5초마다 **가상 디스플레이의 id 로** `userActivity` 를 보낸다
(`keep_active=false` 로 끔). 그 디스플레이는 자기 display group 을 가지므로 폰 본체를 깨우지 않는다.
`/api/status` 의 `keptActive` 로 센다.

**다음에 확인할 것 (실기기):** 전원 버튼을 눌러 차 화면이 멈춘 직후 💾 를 누르고 요약 줄을 본다.
`keptActive` 가 오르는데도 검어지면 이 경로가 One UI 8 에서 듣지 않는 것이고, 그때는 다른 길을 찾아야 한다.

**2026-09-12 정정 — 이 판정 기준은 반쪽이다.** AOSP `PowerManagerService.userActivity` 는 권한
(`DEVICE_POWER`/`USER_ACTIVITY`)이 없으면 **예외 없이 조용히 무시하고 logcat 경고만 남긴다.**
우리 `keptActive` 는 예외가 안 났을 때 올라가므로 **호출 횟수이지 효과의 증거가 아니다.** 판정은
logcat 의 `Ignoring call to PowerManager.userActivity()` 유무로 해야 하고, 검어지는 원인 후보에는
키가드(보조 디스플레이 가리기)도 넣어야 한다 —
[prior-art.md §"전원·화면 끄고 켜기 — 2차 조사"](prior-art.md#전원화면-끄고-켜기--2차-조사-2026-09-12).

### 3.11 전원 손잡이들을 재는 눈을 달고, 가상 폰에서 한 번에 확인 (2026-09-12, run #20 / `cb9c8f9`)

§3.10 이 남긴 숙제("`keptActive` 가 오르는데도 검어지면…")가 잘못된 판정 기준이었다는 것을 알고
([prior-art.md §2.1](prior-art.md#21-우리-keptactive-는-먹혔다-는-증거가-아니다--고칠-것)) 계측부터 고쳤다.
가상 폰(Android 16, `google_apis`) 15개 검사 **전부 통과, 건너뛴 것 0개**. 얻은 사실:

| 물음 | 답 (가상 폰) |
|---|---|
| 가상 디스플레이가 플래그를 **받았나** | `displayFlags=0x4f88` — PRESENTATION · TRUSTED · OWN_DISPLAY_GROUP · **ALWAYS_UNLOCKED** · TOUCH_FEEDBACK_DISABLED · OWN_FOCUS · ROTATES_WITH_CONTENT. 요청한 것이 다 붙었다 |
| `userActivity` 가 **먹히나** | `keepActiveEffective=true`. logcat 에 `Ignoring call to PowerManager.userActivity` 없음(서버 판정과 테스트의 바깥 확인이 일치). 셸에 `DEVICE_POWER` 가 있다는 뜻 |
| 화면을 끈 채 1분 | 20초 구간별 프레임 **267 / 246 / 251** — 끊기지 않았다. `panelOffMethod=power-mode`, 폴백은 쓰이지 않았다 |
| 폰이 자면 차 화면 그룹도 자나 | **아니다.** `lastSleepVdInteractive=true`, `vdWakes=0` — 그래서 그룹만 깨우는 새 경로는 **발동하지 않았고**, 기존 경로로 되살렸다(`sleepRecoveries 0→1`). AOSP 가 `goToSleep()` 을 기본 그룹에만 건다는 읽기와 일치한다 |
| 유휴 타이머 | 600000 으로 걸리고, 킬 스위치 뒤 원래 값(2147483647)으로 **돌아왔다** |
| 충전 중 `stay_on` | 가짜 충전(`dumpsys battery set ac 1`)에서 `mStayOn=true` — 이 손잡이가 처음으로 실제 검사됐다 |

**중요 — 이 통과가 증명하지 않는 것:** 1분 검사가 통과했다는 것은 **가상 폰이 scrcpy#6787 을 재현하지
못했다**는 뜻이다. 회귀 방지용 그물이지, 병을 고쳤다는 증거가 아니다. 실차 리포트 #26 의 증상은 여전히
실기기에서만 확인된다.

**그래서 다음 실험:** §4 가설이 말하는 유일한 방아쇠는 **잠금화면**인데, 지금까지 하네스는 그것을
꺼 왔다(`vphone.sh` 와 생애주기의 '폰을 깨운다' 가 `wm dismiss-keyguard` 를 부르고, 에뮬레이터에는
보안 잠금이 없다). 그래서 일부러 PIN 을 걸고 재운 뒤, **잠금화면이 실제로 떠 있는지 먼저 확인하고**
프레임을 세는 검사를 넣었다. 안 떠 있으면 실패로 센다 — 재현되지 않은 검사를 통과로 세는 것이
지금까지의 함정이었다.

#### 이어진 두 번 (run #21 `950b424`, run #22 `0031cf1`) — 18개 검사 전부 통과, 건너뛴 것 0개

| 물음 | 답 (가상 폰, Android 16) |
|---|---|
| **잠금화면이 떠 있으면 차 화면이 덮이나** | **아니다.** `dumpsys` 가 `isKeyguardShowing=true` 라고 말하는 상태에서 20초 구간별 **244 / 241 / 242** 조각. §4 가 말한 면제(`ALWAYS_UNLOCKED`)가 **실제로 듣는다** — 적어도 AOSP 에서는 |
| 패널을 끄는 세 길 중 무엇이 되나 | **쓸 수 있는 길은 둘**(run #26·#28, 양방향 측정): `power-mode` 끄기/켜기 모두 ✅, `brightness` 모두 ✅, **`cmd-display` 는 끄기만 되고 켜기가 255 로 실패**(run #25 에서도 같았다). 끄기만 보고 "된다"고 적으면 반쪽이고, 그런 길을 쓰면 폰이 꺼진 채로 남는다. 그래서 폴백 순서를 **되돌릴 수 있는 쪽(brightness) 먼저**로 바꿨다. scrcpy 가 Android 15 의 `requestDisplayPower` 를 꺼 두는 이유(#5530)와 같은 자리로 보인다 |
| 감시 주기를 250ms 로 줄인 대가 | 📵 상태에서 `dumpsys power` 를 **초당 네 번** 띄우고 있었다 — 기록용으로만 쓰는 값 때문에. 초당 한 번으로 묶었다(그 자체로 옳은 수정). **다만 차 쪽 e2e 가 1분→14분으로 느려진 것의 원인은 이것이 아니었다:** 묶은 뒤에도 run #26 은 1.1분, run #28 은 8.8분이었다. 같은 코드에서 1~14분을 오간다 — 2코어 러너에서 Chrome 과 에뮬레이터가 같이 도는 탓으로 보이고, **원인은 아직 모른다.** 느린 실행에서도 검사는 통과한다 |
| 폰이 잠든 것을 얼마나 빨리 아나 | **60ms**. 감시 주기를 250ms 로 줄인 결과(전에는 최대 1000ms). 셸은 `ACTION_SCREEN_OFF` 를 못 받으므로 이 간격이 곧 운전자가 멈춘 그림을 보는 시간이다 |

**아직 남은 것(하네스 밖):** 패널이 눈으로 보기에 진짜 어두운가, One UI 8 에서도 같은 답인가
(특히 셸의 `DEVICE_POWER` 와 AOD(dream)가 §4 의 스위치를 켜는지), 그리고 30분 연속·발열·배터리.
가상 디스플레이에 `FLAG_KEEP_SCREEN_ON` 창을 붙이는 후보(prior-art §7 #4)는 **일부러 넣지 않았다** —
블랭킹이 여기서 재현되지 않으므로 그 효과를 판정할 방법이 없고, 판정할 수 없는 코드를 넣는 것은
이 문서가 막으려는 바로 그것이다.

---

### 3.12 리포트 #41·#42 (2026-09-13): "가끔 서버가 죽는다" 의 정체

**증상.** 그림이 얼고(`0fps`, `폰 무응답 16.2s`) 두 소켓이 계속 끊겼다 붙었다 한다. 그런데 같은
순간에 `/api/status` 는 멀쩡히 답하고 있었다 — 리포트 자체가 그 응답을 싣고 있다. 프로세스는
살아 있는데 영상만 죽은 것이다.

| 리포트 | 무엇이 보였나 |
| --- | --- |
| #41 | `mse 0fps ... frames 33868 ws↻7/4 복구7`, `idle 16.2s`. 오래 쓰다 멎었다 |
| #42 | 60.7s 부터 `video ws closed` ×5, 그 뒤 81.8s 에 `open #2`. `videoWs {connects:2, failures:4}`, control 도 똑같이 2/4 |

**원인.** `MediaHub.onInit` 이 init 세그먼트를 **기다리면서** 보내고 있었다(`queue.put`). 차가 읽기를
멈추면(와이파이 한 번 끊김, 차 브라우저가 바쁨) 64칸 큐가 차고, 그 다음 init 세그먼트가
인코더 출력 스레드를 그 소켓 하나에 묶어 둔다. 그 스레드는 `EncodedH264Sink` 의 자물쇠를 쥔 채라
`onFrame` 까지 함께 멈춘다. TCP 가 포기할 때까지 — 분 단위다. **init 세그먼트는 앱을 띄울 때마다
나간다**(리포트 #42 의 `8.2s`·`88.1s` 줄이 그것이다). 즉 방아쇠는 평범한 사용이었다.

고친 계약: 보낼 자리가 250ms 안에 나지 않으면 그 연결을 **놓아준다**. 이미 64프레임 뒤처진 소켓은
기다려 줘 봐야 얻을 것이 없고, 차는 1초 안에 다시 붙어 `attach()` 에서 init + 최신 키프레임을
새로 받는다 — 기다려서 받았을 그림보다 낫다. 확정적 근거는 `core` 단위 테스트
`StalledClientTest` 다(안 읽는 소켓 하나를 만들어 놓고 `onInit` 이 돌아오는지 잰다). **옛 코드로
되돌리면 3개 중 2개가 10초 타임아웃으로 실패하는 것까지 확인했다.**

**같이 고친 것.** `HttpServer` 의 accept 루프가 `IOException` 하나에 `break` 하고 있었다. accept 는
지나가는 이유로도 던진다(한꺼번에 소켓이 많이 열렸을 때 등). 그러면 프로세스는 살아서 이미 붙은
소켓에는 계속 답하고, **새 연결만 영영 안 받는다** — 실차 리포트 #31–33 의 모양 그대로다. 이제
소켓이 닫혔을 때만 루프를 끝낸다.

**다음에 또 "죽었다"고 할 때 무엇을 볼 것인가.** `/api/status` 에 `accepts`·`acceptErrors`·
`accepting`·`lastAcceptAgoMs`·`videoDropped` 를 실었다. 끊긴 링크와 멎은 서버는 브라우저에서
똑같아 보이지만, **차의 연결이 폰까지 닿았는지**는 이 숫자들만 답할 수 있다. 웹 쪽은 닫힘 코드를
같이 남긴다(`video ws closed (1006)` = 인사도 없이 끊김 = 링크가 사라진 쪽).

**A+ 확인 (run #37 / `85592d8`).** 가상 폰에서 기기 검사(`07-stall` 포함)·차 클라이언트·생애주기·
킬 스위치가 모두 통과했고, 기기 검사 구간은 11.1분 — 이 변경 전의 건강한 실행(#35)과 같다.
그 앞의 run #36 이 두 배 넘게 걸린 것은 `lifecycle.spec.ts:119` 터치 검사가 300초 제한에 한 번
걸렸다가 재시도에서 통과한 것(`1 flaky`) 때문이었다. 같은 서버 코드로 #37 이 정상 시간에 끝났으므로
러너 쪽 흔들림으로 본다.

**아직 모르는 것:** #42 에서 20초 동안 재접속이 4번 실패한 것이 폰 때문인지 링크 때문인지는
그때의 기록으로는 가를 수 없다. 위 숫자들이 붙은 빌드의 다음 리포트가 답한다.

---

## 4. C층: 실차 (Model Y, 2026.26) — ✅ 2026-09-10 첫 방문에서 영상·터치 동작

**report #9 (04:14 UTC, 빌드 `1424f30`)**: `ws 20/20 24ms, video 120f 24fps lag 61ms, private-ip blocked=1 reachable=2`. 가정 2·5·6 실차 ✅.
사용자 확인: 본 화면에서 영상·터치 "전부 작동". #7·#8의 `packets=2`는 **폰 인코더가 정지 화면에서 프레임을 안 낸 것**으로 확정
(#7·#8이 같은 pts 180214.95의 캐시 키프레임 1개만 받음; #9의 pts로 역산하면 그 프레임은 03:59:50경 생성) — 차 브라우저 문제가 아니다.
렌더러가 그 한 장을 그리도록 고침(`388c3c5`), 정지 화면에서도 프레임을 유지하는 폰 쪽 개선은 후속.


1차 방문(빌드 `add8b48`): 차 브라우저가 `http://100.99.9.9:3333/diag`를 열었고(**100.64/10 대역 열림 확인**), MSE H.264 Baseline·High·H.265·AAC 전부 O,
WebCodecs X, secure context X, viewport 804x638 / screen 1306x816 / **DPR 1.96**, UA에 **`Tesla/` 토큰 없음**(`X11; Linux x86_64 … Chrome/148.0.0.0`).
WS 카운터는 20/20 도달. 그러나 **영상 프로브에서 페이지가 멈춰 report가 저장되지 않았다** — `video.play()` 대기에 타임아웃이 없었던 `/diag` 결함.
타임아웃·워치독·`video.state`를 넣어 고쳤다(`tests/e2e/tests/diag-stall.spec.ts`).

같은 날 2차(빌드 `1424f30`, report #7): `/diag`가 끝까지 가서 저장됨. **WS 20/20 25ms(가정 5 ✅)**, 차의 MSE가 init+프레임 1개 조각을
버퍼에 넣음(`buffered=180214.95-180214.98`, 가정 2 후반 거의 ✅), **핫스팟 주소 차단 확인 ✅** / 폰 CLAT `192.0.0.2`·`.4`는 열림(예비 경로 후보).
`packets=2`는 폰 인코더 idle(정지 화면)로 확정 — 위 참조.
상세: [car-tests/model-y-2026.26.md](car-tests/model-y-2026.26.md).

기록 틀: [car-tests/model-y-2026.26.md](car-tests/model-y-2026.26.md). `/diag`가 UA·viewport·DPR, MSE 코덱 지원,
WS 20회 성공률, 디코드 fps, lag, 사설 주소(핫스팟 `10.136.114.168` 등) 차단 여부를 측정해 폰 서버에 저장한다
(`POST /api/report` → `/data/local/tmp/carcast/`, 조회 `GET /api/reports` 또는 앱의 공유 버튼). 첫 터치 후 재생·전체화면은 손으로.
결과는 `car-tests/<펌웨어>.md`와 이 문서 §1의 가정 2·5·6에 반영.

선행 조건(B층): 분리 실행 서버가 핫스팟·화면 OFF 후 유지 ✅ (§3.5, **USB 디버깅 토글 ON 필수**), 핫스팟 너머에서 라이브 영상·터치 ✅ (§3.6) — **실차 갈 준비 완료.** 소리는 아직 폰에서 난다(M6).

---

## 5. 열린 질문 (다음 검증 대상)
0. ~~**핫스팟을 앱에서 켤 수 있는가:**~~ **답 나옴 — 못 켠다. 바꾸는 코드는 제거했고(2026-09-13) 읽기만 남겼다.** 가상 폰(run #41·#42)과 S26U/One UI 8
   (2026-09-13, `d00a495`) 둘 다 면제 요청과 면제 없는 재시도 모두 `NO_CHANGE_TETHERING_PERMISSION(14)`.
   shell 에게는 `TETHER_PRIVILEGED` 가 없고 WRITE_SETTINGS 경로도 닫혀 있다(삼성 빌드는 프로비저닝 앱이
   설정돼 있어 `isTetherProvisioningRequired()` 가 참일 것으로 보인다 — 미확인). `cmd wifi start-softap` 은
   root 전용이라 남는 길이 없다. **→ 핫스팟은 사용자가 설정에서 직접 켠다.** 앱은 한 번 거부당하면 다시
   시도하지 않고 그렇게 안내하며, 일괄 동작은 서버·VPN 만 다룬다(누를 곳이 셋에서 둘로 준다).
   다시 열어야 할 경우: 다른 제조사·다른 안드로이드에서 `3/3 핫스팟: 켰습니다` 가 나오면 그때 기록한다.
0b. **핫스팟을 켠 뒤 서버가 살아남는가 (B):** 설정에서 직접 켜더라도 남는 질문이다. TCP 모드가 켜진 폰에서는
   살아야 하고(§3.8), 아니면 §3.5 대로 adbd 와 함께 죽는다. 2026-09-13 실행에서 TCP 모드 포트 31432 는
   열려 있었고 그 경로로 서버를 다시 띄웠다 — 핫스팟을 켠 뒤에도 그런지가 남은 확인이다.
1. 차 브라우저에서 `100.64/10` 대역이 실제로 열리는지, MSE H.264 디코드 fps (가정 2).
2. ~~M0: One UI 8에서 shell의 VD 생성·`--start-app`·`display_ime_policy=local`·오디오 소스 선택 (가정 3·4).~~ 완료 → car-tests/s26u-one-ui-8.md
3. ~~M3: Kadb 2.1.1 `pair`/`connect`, NsdManager `_adb-tls-pairing`/`_adb-tls-connect`, 데몬화한 서버의 수명(Shizuku #1125류).~~ 완료 → §3.4, §3.5. 남은 것: 재부팅 후 포트 재발견, "서버 종료" 킬 스위치.
4. shell 서버의 수명: 하룻밤 방치, 앱이 죽었을 때 정리. (adbd 종료 시 죽는 문제는 USB 디버깅 토글로 해결, §3.5)
6. M7 📵: SurfaceControl 경로(`fdc2350`)가 S26U에서 되는지. M6: `output` 캡처를 서버에 넣고 차 스피커로.
7. M4-b 같은 앱을 폰과 차에서: 기본(`restart=never`)이 폰의 유튜브를 **재생 위치째** 차로 옮기는지(2026-09-17에 `auto`에서 되돌림), "새로 열기"(`always`)가 종료 후 새로 띄우는지, 폰 런처가 앱을 가져갈 때 `appOnPhone`이 5초 안에 true가 되는지, 사용자 조작 없이 앱이 폰으로 돌아가는 경로가 있는지,
   그리고 One UI 8의 `am stack list` 출력이 `TaskList` 파서와 맞는지(안 맞으면 `appDisplay`가 항상 null). 절차: testing-guide §B "M4-b".
10. **차 브라우저에 WebCodecs 가 있는가 (C) — HTTPS 착수 전의 유일한 질문:** 2026-09-17 에 재는 도구를
   달았다. 폰이 자기 주소(`100.99.9.9`·핫스팟·loopback)로 **자체서명 인증서**를 만들어 `:3443` 에
   같은 페이지를 TLS 로도 서빙하고(`/api/status.httpsPort`·`tlsFingerprint`), `/diag` 가 그 자리에서
   `isSecureContext`·`VideoDecoder`·`isConfigSupported`(Baseline/High × no-preference/prefer-hardware)를
   재서 리포트에 싣는다.
   - **여태의 `WebCodecs X` 는 답이 아니었다.** `VideoDecoder` 는 명세상 `[SecureContext]` 라 평문
     오리진에는 객체 자체가 없다 — "차에 없다"가 아니라 "물어볼 수 없었다"이다(prior-art §5.4 정정).
   - A 층에서 확인된 것(Chrome 148, 차와 같은 엔진): 인증서 오류를 넘긴 https 오리진은 **secure
     context 이고**, 거기서 `VideoDecoder` 가 보이며 Baseline·High 가 `no-preference` 로 `supported`,
     `prefer-hardware` 는 이 컨테이너(GPU 없음)에서 `unsupported` — 즉 **프로브가 하드웨어와
     소프트웨어를 실제로 가른다.**
   - **① 은 실차에서 답이 나왔다 — ❌ 넘길 수 없다 (2026-09-17, Model Y 2026.26).** 자체서명으로
     `https://100.99.9.9:3443` 을 열면 `NET::ERR_CERT_AUTHORITY_INVALID` 가 뜨는데 **"고급" 버튼이 없고**
     본문이 *"웹사이트가 Chromium이 처리할 수 없는 암호화된 사용자 인증 정보를 전송하였으므로 지금은
     100.99.9.9에 방문할 수 없습니다"* — 크로미엄이 **우회를 막았을 때**(`SSLErrorOverrideAllowed=false`
     정책으로 보인다) 쓰는 문구다. 즉 **이 차에서 자체서명은 영영 secure context 에 못 간다.**
     다만 같은 화면이 알려 주는 것이 하나 더 있다: 차가 생 IP 로 **https 를 열고 TLS 핸드셰이크를 해서
     우리 손으로 쓴 인증서를 파싱까지 했다**(불평한 것은 발급자뿐이다). 프로토콜 쪽은 멀쩡하다.
   - **그래서 공개 CA 로 갈아탔다(같은 날).** 도메인을 사지 않고도 된다: `local-ip.sh` 가 `*.local-ip.sh`
     의 Let's Encrypt 와일드카드 인증서와 키를 **공개**하고, 그 DNS 가 이름에 적힌 주소를 돌려준다
     (`100-99-9-9.local-ip.sh` → `100.99.9.9`). 그 쌍을 APK 에 실으면(`assets/tls/`, git 에는 없다 —
     tools/tls/README.md) 서버가 자체서명 대신 그것을 쓰고 `/api/status.tlsHost` 가 주소를 알려 준다.
     A 층 확인(Chrome 148, **인증서 오류를 무시하지 않는 브라우저**): `https://100-99-9-9.local-ip.sh:3444/diag.html`
     이 **경고 없이 200 으로 열리고** secure context, `VideoDecoder` 보임. curl 의 시스템 CA 검증도 통과
     (`ssl_verify_result=0`).
   - **② ③ ④ ⑤ 모두 답이 나왔다 — ✅ 전부 (2026-09-17, report #67).** 차가
     `https://100-99-9-9.local-ip.sh:3443/diag.html` 을 **경고 없이** 열었고(= 그 이름을 해석했다;
     이 통신사에서 DNS64/NAT64 사망은 없다), `isSecureContext true`, `VideoDecoder` 있음,
     **Baseline·High 4.0 둘 다 `prefer-hardware` 로 supported**. 즉 **이 차에는 하드웨어 H.264
     디코더가 있고 High 까지 읽는다.** 자세히: car-tests/model-y §7.
   - **그래서 바뀌는 것:** ① 차의 WASM 디코더(Baseline 전용) 때문에 인코더를 Baseline 에 묶어 둘 이유가
     없어졌다(`/api/encoder?profile=high`), ② 해상도·fps 의 천장이 차 CPU 가 아니게 된다,
     ③ **HTTPS 가 진단 도구가 아니라 제품 요구사항이 된다** — 평문에서는 그 디코더에 닿지 못한다.
     남은 일은 우리 도메인·우리 키(tools/tls/README.md 아래쪽, `tools/tls/issue.sh`).
   - 판정: `isSecureContext O` 인데 `VideoDecoder X` → **테슬라 빌드에 WebCodecs 가 없다. 진짜 인증서
     작업은 무의미하니 접고 WebRTC(평문에서도 되는 `RTCPeerConnection`, 실차 O)를 본다.** 둘 다 O 면
     그때 비로소 "경고 없이 열리게 하는 법"(공개 도메인 + 진짜 인증서)이 할 일이 된다.
   - 그 앞에 붙는 별도 확인: **공개 도메인 하나가 차에서 우리 주소로 해석되는가**(평문으로 먼저).
     안 되면 인증서를 사도 못 쓴다 — Castla issue #51 의 DNS64/NAT64 사망(prior-art §4).
8. **폰 안쪽 지연 예산이 실제로 어디로 가는가 (B → C):** 2026-09-17 에 계측을 달았다(`FrameTiming`,
   `/api/status.timing`). 지금까지 아는 것은 끝에서 끝 43ms(720p30, report #61), 컨트롤 왕복 5~11ms,
   차의 디코드→그리기 9.5~13ms 뿐이고, **그 사이 25ms 는 한 덩어리**였다. 새 두 수치가 그것을 가른다:
   - `timing.encodeMs` — 서피스가 그 그림을 만든 시각(`BufferInfo.presentationTimeUs`, CLOCK_MONOTONIC)에서
     인코더가 바이트를 돌려줄 때까지. **가정: 벤더 인코더가 출력에 pts 를 다시 찍지 않는다.** 아니면
     `skipped` 가 는다(조용히 틀리지 않게 만든 장치). 에뮬레이터에서 이 가정이 서는지는 `emulator` 워크플로의
     `04-input` 이 본다.
   - `timing.touchToFrameMs` — 터치를 주입한 순간부터 다음 프레임의 pts 까지. 안드로이드의 입력→앱→합성.
     화면이 안 바뀌는 탭은 다음 아무 프레임에나 붙으므로 500ms 넘는 표본은 버린다. 거친 수치다.
   읽는 법: 둘의 합이 25ms 를 설명하면 다음 손잡이는 인코더 쪽(또는 가상 디스플레이의 리프레시)이고,
   설명하지 못하면 남는 곳은 링크와 큐다. **다음 실기기(B)·실차(C) 방문에서 💾 리포트의 이 두 줄을 적는다.**
9. **송신 큐 정책(2026-09-17)이 실제 주행에서 무엇을 바꾸는가 (C):** 영상 소켓 앞에 4장까지만 쌓고 넘으면
   버린다(`MediaHub.MAX_QUEUED_FRAMES`, `/api/status.videoStaleDropped`). 예전에는 64장까지 쌓였고 —
   30fps 에서 2.1초 — 링크가 한 번 멈칫하면 **프레임을 잃는 대신 전부 늦게** 배달했다. 차는 그것을 볼 수
   없다(자기 적체는 0, `30fps dropped 0`). 리포트 #27(`lag 144ms`, 나머지는 멀쩡, 사용자는 불만)이 그 모양.
   확인할 것: 주행 중 `videoStaleDropped` 가 0 이상으로 오르는 구간이 있는지, 그때 화면이 **끊기는지(새 정책)**
   아니면 **밀리는지(옛 정책)**, 그리고 `keyframeRequests` 가 그만큼 따라 오르는지.
11. **1080p60 이 실제로 이 차에서 도는가 (C):** 2026-09-17 에 사다리를 하드웨어 경로에서만 한 칸 늘렸다
   (`1080p60`, 12Mbps, High). 근거는 report #67 의 `prefer-hardware: supported` 뿐이고, **supported 는
   "이 설정을 받아 준다"이지 "60fps 를 낸다"가 아니다.** A 층에서 확인된 것은 사다리가 렌더러에 따라
   갈린다는 것과 프리셋 값이 폰에 닿는다는 것까지다(`quality.spec`). 차에서 볼 것: 💾 리포트의
   `fps`·`lag`·`backlog` 추이(1분 이상), 자동 내리기가 돌았는지(`autoStepDowns`), 그리고 핫스팟 링크가
   12Mbps 를 견디는지(`rtt` 가 같이 오르면 링크 쪽이다). 함께 볼 것: 폰 쪽 `timing.encodeMs` —
   1080p60 은 인코더 예산(16ms)이 720p 의 10ms 보다 빡빡하다.
   폰 쪽 위험은 따로 잡아 뒀다: 큰 프리셋에서 Baseline 요청이 거부되지 않는가(`H264LevelTest`,
   그리고 실기기에서 `tests/device/09-encoder` 의 "큰 프리셋에서도 Baseline 요청이 살아남는다").
5. 이전 계획의 "shell→앱 유닉스 소켓 IPC"는 서버가 shell로 옮겨가며 불필요해짐. 앱↔서버는 HTTP/WS로 충분한지 M3에서 확정.

---

## 6. 근거 색인
| 커밋 | 내용 |
|---|---|
| `72c3d18` | M1+M2 스캐폴드, 앱 내 VPN/HTTP/WS 서버, 먹서, 웹, 가짜 폰, Playwright |
| `73541fb` | 고정 debug keystore (설치 실패 해결) |
| `298c8d3`, `0c5dc8b`, `89555bf` | 자가 테스트·인터페이스 표시·VPN 없이 시작·accept 로그 (진단용) |
| `9bed496`, `147a45c`, `de168a9` | 앱 uid에서의 우회 시도 3종 (모두 무효, 이후 제거) |
| `75ff76a` | 서버를 shell uid로: `core` 모듈, `com.carcast.server.Server`, 앱은 상태 폴링 |
| `05571fc` | `daemon=true`, 실기기 결과 기록 |
| `306d41a` | 분리 실행 셸의 자기 종료 버그 수정(pid 파일·앵커 패턴), 실행별 로그 파일 |
| `d98be88` | cgroup 탈출 시도(실패 확인용 `step: cgroup` 줄), USB 디버깅 필수 안내 |
| `fdc2350` | 📵를 scrcpy와 같은 SurfaceControl 경로로 (requestDisplayPower 실패) |

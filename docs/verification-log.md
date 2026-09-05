# 검증 기록 (2026-09-04 기준)

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
| 2 | 테슬라 2026.26 브라우저가 `http://100.99.9.9`를 열고 MSE H.264를 디코딩한다 | ⏳ 실차 미실시. PC의 Chrome 148(테슬라 프로필)에서는 ✅ | C | §2.3 |
| 3 | shell 권한으로 띄운 scrcpy 서버 포크가 갤럭시에서 VD 생성 + 타 앱 실행 + 터치 주입이 된다 | ⏳ M0 미실시. 단 "앱 자신의 APK를 `app_process`로 shell uid에서 실행"은 ✅ | B | §3.3 |
| 4 | 오디오 캡처(`output`/`playback`)가 One UI 8에서 된다 | ⏳ M0 미실시 | B | — |
| 5 | WS 간헐 실패가 재시도로 해결된다 | PC ✅ (거부 34%·절단 5초마다 → 15초 내 복구) / 실차 ⏳ | A → C | §2.3 |
| 6 | MSE 지연이 터치 조작에 견딜 수준(<300ms) | PC ✅ (fps ≥ 25, lag < 300ms) / 실기기·실차 ⏳ | A → B/C | §2.3 |
| — | 앱 하나(APK)에 shell 서버 dex를 넣고 `CLASSPATH=<base.apk> app_process`로 실행할 수 있다 | ✅ uid=2000, build id 검증 동작 | B | §3.3 |
| — | Kadb(순수 JVM ADB 페어링)로 NDK 없이 갈 수 있다 | ✅ POM 확인(okio, spake2-java, hiddenapibypass, BouncyCastle). 코드는 M3에서 | A | dev-plan |
| — | 매 CI 빌드의 APK를 덮어 설치할 수 있다 | ✅ 고정 debug keystore 커밋 후 | B | §3.1 |

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

결과: 가짜 폰 대상 **14/14**, JVM으로 띄운 shell 서버(`./gradlew :core:run`, APK의 assets 그대로) 대상 **8/14 통과, 6 skip**.
같은 서버 코드가 폰의 shell 프로세스에서 돌기 때문에, 폰에서 남는 미검증 요소는 프로세스 환경과 네트워크뿐이다(§3.3에서 확인).

이 층에서 잡은 문제와 수정: 클립 루프 시 `tfdt`가 원래 pts로 남아 MSE 타임라인이 되감기던 10초 정지(재스탬프),
재연결 후 새 MediaSource가 일시정지 상태로 남던 문제(`wantPlay` + `play()`), AbortError 무시, 백오프 상한 2초.

---

## 3. B층: Galaxy S26 Ultra (SM-S948N, Android 16 / One UI 8) + 노트북

날짜 2026-09-04. 폰 핫스팟(swlan0 `10.136.114.168/24`, 상위망 rmnet_data2), 노트북 Windows(Wi-Fi `10.136.114.7`), 앱 tun0 `100.99.9.9/32`.
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

### 3.4 아직 B층에서 안 한 것
- `BASE_URL=http://100.99.9.9:3333 npx playwright test`를 노트북에서 폰에 대고 실행(자동화된 fps·지연 수치).
- 5GHz 핫스팟에서 720p30 10분 연속(대역폭).
- M0 scrcpy 4.1 체크리스트(VD 생성, 앱 실행, 터치, IME, 오디오, 화면 OFF, DeX 노출) — [car-tests/s26u-one-ui-8.md](car-tests/s26u-one-ui-8.md) 표.
- `daemon=true`로 띄운 서버가 USB 분리·화면 OFF 후에도 유지되는지.

---

## 4. C층: 실차 (Model Y, 2026.26) — ⏳ 미실시

기록 틀: [car-tests/model-y-2026.26.md](car-tests/model-y-2026.26.md). `/diag`가 UA·viewport·DPR, MSE 코덱 지원,
WS 20회 성공률, 디코드 fps, lag, 사설 주소(핫스팟 `10.136.114.168` 등) 차단 여부를 측정해 폰 서버에 저장한다
(`POST /api/report` → `/data/local/tmp/carcast/`, 조회 `GET /api/reports` 또는 앱의 공유 버튼). 첫 터치 후 재생·전체화면은 손으로.
결과는 `car-tests/<펌웨어>.md`와 이 문서 §1의 가정 2·5·6에 반영.

선행 조건(B층, 미실시): `daemon=true` 서버가 USB 분리·화면 OFF 후 유지되는지 — §3.4.

---

## 5. 열린 질문 (다음 검증 대상)
1. 차 브라우저에서 `100.64/10` 대역이 실제로 열리는지, MSE H.264 디코드 fps (가정 2).
2. M0: One UI 8에서 shell의 VD 생성·`--start-app`·`display_ime_policy=local`·오디오 소스 선택 (가정 3·4).
3. M3: Kadb `pair`/`connect`가 One UI 8 무선 디버깅과 호환되는지, 재부팅 후 포트 재발견, `adb_wifi_enabled` 토글로 킬 스위치.
4. shell 서버의 수명: 화면 OFF/도즈 30분, 앱이 죽었을 때 정리.
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

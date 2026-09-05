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
| 2 | 테슬라 2026.26 브라우저가 `http://100.99.9.9`를 열고 MSE H.264를 디코딩한다 | ⏳ 실차 미실시. PC의 Chrome 148(테슬라 프로필)에서는 ✅ | C | §2.3 |
| 3 | shell 권한으로 띄운 scrcpy 서버 포크가 갤럭시에서 VD 생성 + 타 앱 실행 + 터치 주입이 된다 | ✅ M0 (stock scrcpy 4.1, 2026-09-05, 8/8 항목): VD 생성·앱 실행·터치·IME 로컬·UHID 한글·`--turn-screen-off --stay-awake`로 폰 화면만 끄기·서버 단독 기동 모두 됨. 전원 버튼 화면 OFF는 전체 정지. 단 "앱 자신의 APK를 `app_process`로 shell uid에서 실행"은 ✅, **앱이 내장 ADB로 직접 띄우는 것도 ✅** | B | §3.3, §3.4 |
| 4 | 오디오 캡처(`output`/`playback`)가 One UI 8에서 된다 | ✅ `output`: 원격 재생 + 폰 무음. `playback --audio-dup`: 양쪽 재생 (M0 2026-09-05) | B | car-tests/s26u |
| — | 폰 화면만 끄고 VD를 유지할 수 있다 (`--turn-screen-off --stay-awake`) | ✅ M0 4번 (충전 중). 앱 구현: `requestDisplayPower` 경로는 ❌ "전환 실패"(d98be88) → scrcpy와 같은 SurfaceControl 경로로 교체, 폰 ⏳ | B | car-tests/s26u, §3.6 |
| 5 | WS 간헐 실패가 재시도로 해결된다 | PC ✅ (거부 34%·절단 5초마다 → 15초 내 복구) / 실차 ⏳ | A → C | §2.3 |
| 6 | MSE 지연이 터치 조작에 견딜 수준(<300ms) | PC ✅ (fps ≥ 25, lag < 300ms) / 실기기·실차 ⏳ | A → B/C | §2.3 |
| — | 앱 하나(APK)에 shell 서버 dex를 넣고 `CLASSPATH=<base.apk> app_process`로 실행할 수 있다 | ✅ uid=2000, build id 검증 동작 | B | §3.3 |
| — | Kadb(순수 JVM ADB 페어링)로 NDK 없이 갈 수 있다 | ✅ POM 확인(okio, spake2-java, hiddenapibypass, BouncyCastle). 코드는 M3에서 | A | dev-plan |
| — | 매 CI 빌드의 APK를 덮어 설치할 수 있다 | ✅ 고정 debug keystore 커밋 후 | B | §3.1 |
| — | Kadb 페어링 + NsdManager `_adb-tls-pairing` 발견이 One UI 8(Android 16)에서 된다 | ✅ 2026-09-05 빌드 `15ea085`: 포트 39727 발견 → 페어링 성공 (수동 입력 불필요) | B | §3.4 |
| — | 무선 디버깅을 핫스팟 상태에서 켤 수 있다 | ❌ Wi-Fi 클라이언트 연결 중에만 토글 활성 (사용자 실측 2026-09-05) → 서버는 Wi-Fi에서 분리 실행, 차에서는 adb 불사용 | B | dev-plan M3 |
| — | 분리 실행(`daemon=true`) 서버가 adb 스트림·Wi-Fi·무선 디버깅 종료·화면 OFF 후에도 유지된다 | ⚠️ **조건부 통과 — USB 디버깅 토글이 켜져 있을 때만.** 꺼져 있으면 Wi-Fi가 끊길 때 adbd가 멈추고 init이 adbd의 cgroup(`/system/uid_0/pid_N`)을 통째로 SIGKILL → 서버 사망 (2026-09-05 `306d41a`). shell은 cgroup을 못 벗어남(`d98be88`에서 전 경로 EACCES). 켜 두면 핫스팟 전환 후 유지 + 노트북에서 `100.99.9.9:3333` 접속 ✅. 하룻밤·재부팅은 ⏳ | B | §3.4, §3.5 |

| — | adbd를 TCP 모드(`tcpip:<포트>`)로 돌리면 Wi-Fi 없이(핫스팟에서도) adb를 쓸 수 있다 | ⏳ **미검증 — 자동 전환은 철회하고 사용자 선택(버튼)으로 내림**(2026-09-05 사용자 실측: 재부팅 후 무선 디버깅 connect 포트가 계속 `ECONNREFUSED`, 페어링은 성공. TCP 포트도 닫혀 있어 전환 자체가 안 섰는지 adbd가 무선 디버깅을 되돌리지 못했는지 미확정) — 근거는 Shizuku #864(S21, Android 14)의 보고. One UI 8의 adbd가 받아 주는지, 전환 후 무선 디버깅이 꺼지는지, "USB 디버깅 허용" 다이얼로그가 뜨는지 모두 미확인 | B | [hotspot-only.md](hotspot-only.md) §2 |
| — | `persist.adb.tcp.port`를 shell이 설정할 수 있어 재부팅 후에도 adbd가 포트를 연다 | ⏳ 미실시 (앱이 전환 성공 시 자동 시도하고 결과를 로그에 남김). 최근 삼성에서 막혔다는 보고가 많아 기대치 낮음 | B | [hotspot-only.md](hotspot-only.md) §3 |
| — | 테소르(Tesor)가 Wi-Fi 없이 동작한다 | ❌ 테소르는 Shizuku 위에서 돈다(설치 안내 2단계). 비루팅 Shizuku는 재부팅 시 종료되고 무선 디버깅 = Wi-Fi로만 다시 시작된다 — 같은 제약 | 문헌 | [hotspot-only.md](hotspot-only.md) §1.4 |

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

### 3.7 아직 B층에서 안 한 것
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

## 4. C층: 실차 (Model Y, 2026.26) — ⏳ 미실시

기록 틀: [car-tests/model-y-2026.26.md](car-tests/model-y-2026.26.md). `/diag`가 UA·viewport·DPR, MSE 코덱 지원,
WS 20회 성공률, 디코드 fps, lag, 사설 주소(핫스팟 `10.136.114.168` 등) 차단 여부를 측정해 폰 서버에 저장한다
(`POST /api/report` → `/data/local/tmp/carcast/`, 조회 `GET /api/reports` 또는 앱의 공유 버튼). 첫 터치 후 재생·전체화면은 손으로.
결과는 `car-tests/<펌웨어>.md`와 이 문서 §1의 가정 2·5·6에 반영.

선행 조건(B층): 분리 실행 서버가 핫스팟·화면 OFF 후 유지 ✅ (§3.5, **USB 디버깅 토글 ON 필수**), 핫스팟 너머에서 라이브 영상·터치 ✅ (§3.6) — **실차 갈 준비 완료.** 소리는 아직 폰에서 난다(M6).

---

## 5. 열린 질문 (다음 검증 대상)
1. 차 브라우저에서 `100.64/10` 대역이 실제로 열리는지, MSE H.264 디코드 fps (가정 2).
2. ~~M0: One UI 8에서 shell의 VD 생성·`--start-app`·`display_ime_policy=local`·오디오 소스 선택 (가정 3·4).~~ 완료 → car-tests/s26u-one-ui-8.md
3. ~~M3: Kadb 2.1.1 `pair`/`connect`, NsdManager `_adb-tls-pairing`/`_adb-tls-connect`, 데몬화한 서버의 수명(Shizuku #1125류).~~ 완료 → §3.4, §3.5. 남은 것: 재부팅 후 포트 재발견, "서버 종료" 킬 스위치.
4. shell 서버의 수명: 하룻밤 방치, 앱이 죽었을 때 정리. (adbd 종료 시 죽는 문제는 USB 디버깅 토글로 해결, §3.5)
6. M7 📵: SurfaceControl 경로(`fdc2350`)가 S26U에서 되는지. M6: `output` 캡처를 서버에 넣고 차 스피커로.
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

# 구현 제안: 안드로이드 → 테슬라 브라우저 가상 디스플레이 스트리밍

기반 문서:
- `Tesor 구현 방식 역분석` (Tesor 5.x 구조 추정)
- `테슬라 브라우저 제약사항 정리` (2026-09-04, Model Y L / MCU3 기준)

이 문서는 위 두 문서의 사실·추정을 하나의 구현 계획으로 합치고, 실차 없이 어디까지 검증할 수 있는지 정리한다.
"확정"은 두 문서에서 근거가 있는 것, "가정"은 이 계획이 의존하지만 아직 검증 안 된 것.

---

## 0. 한 장 요약

```
[폰: 일반 앱 프로세스]                       [폰: shell UID 프로세스 (scrcpy-server 포크)]
 VpnService  ── tun0 100.99.9.9/32 (라우트 없음)      VirtualDisplay(TRUSTED) ─▶ MediaCodec H.264
 HTTP/WS 서버 0.0.0.0:3333  ◀── LocalSocket ──────    REMOTE_SUBMIX 오디오 ─▶ AAC
 웹 클라이언트 정적 파일                                InputManager.injectInputEvent(displayId)
 Shizuku / (후기) 내장 ADB 페어링으로 위 프로세스 기동    am start --display N, 화면 끄기, IME 정책
        ▲
        │ 폰 핫스팟 (5GHz)
        ▼
[테슬라 브라우저, Chromium 148]
 http://100.99.9.9:3333  →  WS(binary fMP4) → MSE <video>  |  터치 → WS → 폰
 /diag 페이지: UA·API 지원·WS 성공률·디코드 fps를 화면에 표시 (devtools 없음)
```

핵심 결정 4가지:

| 결정 | 선택 | 이유 |
|---|---|---|
| 권한 | 1차 Shizuku, 안정화 후 ADB 페어링 내장 | 타 앱을 가상 디스플레이에 띄우려면 shell UID 필수. MediaProjection만으로는 불가 (아래 2.1) |
| shell 쪽 코드 | scrcpy-server(Apache-2.0) 포크 | 가상 디스플레이 생성·앱 실행·터치 주입·오디오 캡처·화면 끄기·OS 버전별 quirk가 이미 구현되어 있음 |
| 전송/렌더 | WS 바이너리 fMP4 → MSE, WebCodecs·WebRTC는 교체 가능한 렌더러로 | http 오리진(비-secure context)에서 동작, 테슬라 네이티브 H.264 디코더 사용 |
| 네트워크 | VpnService tun에 100.99.9.9/32, 라우트 없음 | 루트 없이 폰에 비-RFC1918 주소를 붙이는 유일한 방법. 실사용 앱(TeslaMirror)이 검증 |

---

## 1. 목표와 범위

- 최종 목표: Tesor 5.x와 같은 구조. 폰 화면은 그대로 쓰고, 별도 가상 디스플레이의 앱을 차량 화면에서 조작.
- 1차 목표(MVP): 가상 디스플레이 1개 + 앱 1개를 720p/30~60fps로 차량에서 보고 터치할 수 있다. 오디오 포함.
- 범위 밖(초기): 분할 화면, 런처, Pie 제스처, Z Fold 대응, DRM 앱.

---

## 2. 계층별 설계

### 2.1 권한 계층: 왜 shell UID가 필요한가

MediaProjection만 쓰는 미러링(TeslaMirror 방식)으로는 목표를 못 이룬다.

- 앱이 직접 만든 VirtualDisplay에는 `android:allowEmbedded="true"`인 액티비티만 타 앱에서 띄울 수 있다. 일반 앱은 이 속성이 없다. 타 앱을 임의 디스플레이에 올리려면 TRUSTED 디스플레이(= `ADD_TRUSTED_DISPLAY`, shell/system 권한)가 필요하다.
- 터치 주입도 AccessibilityService `dispatchGesture`는 물리 디스플레이 기준이라 가상 디스플레이에 못 보낸다. `InputManager.injectInputEvent`에 displayId를 지정해야 한다 (`INJECT_EVENTS`, shell 보유).
- 물리 패널 끄기(`SurfaceControl`/`DisplayControl`), 시스템 오디오 캡처(`CAPTURE_AUDIO_OUTPUT`)도 shell.

따라서 구조는 두 프로세스로 나뉜다.

| 프로세스 | UID | 역할 |
|---|---|---|
| 앱 (`com.example.carcast`) | 앱 | UI, VpnService, HTTP/WS 서버, 웹 클라이언트 서빙, shell 프로세스 기동 |
| 서버 (`app_process` 로 실행되는 jar) | shell(2000) | VD 생성, 인코딩, 입력 주입, 오디오, 화면 제어, `am`/`settings` |

기동 방식은 단계적으로:

1. **Phase 1: Shizuku UserService.** 우리 jar를 Shizuku가 shell UID로 띄워준다. AIDL로 제어, 영상/오디오는 `LocalSocket`으로 전달 (Binder로 프레임을 보내면 안 됨).
2. **Phase 4: ADB 페어링 내장.** Shizuku의 `adb` 모듈(SPAKE2 페어링 + TLS 연결, `libadb.so`)을 떼어와 앱에 넣는다. 앱이 직접 `adb shell app_process ...`를 실행하는 것과 동일. 이후 `settings put global adb_enabled 0`으로 미사용 시 디버깅 OFF (Tesor 5.0 동작).
   - 제약: Android 11+. 재부팅 후 무선 디버깅 포트가 바뀌므로 `_adb-tls-connect._tcp` mDNS로 재발견. 기기에 따라 재부팅 후 무선 디버깅 토글이 꺼질 수 있어 완전 자동 재연결은 "가정".

### 2.2 캡처·인코딩 계층 (shell 프로세스)

scrcpy 3.x 서버가 이미 아래를 제공한다. 이 부분을 새로 쓰지 않고 포크한다.

| 필요 기능 | scrcpy 대응 |
|---|---|
| 가상 디스플레이 생성 | `--new-display=1280x720/160` (TRUSTED VD, 시스템 데코 옵션) |
| 앱을 VD에 실행 | `--start-app=<pkg>` (`am start --display`) |
| VD 터치/키 주입 | MotionEvent에 displayId 지정 후 `injectInputEvent` |
| 인코딩 | MediaCodec H.264/H.265, 비트레이트·fps·I-frame 간격 옵션 |
| 오디오 | `--audio-source=output` (REMOTE_SUBMIX) 또는 `playback` (Android 13+). 폰 스피커 무음 여부 선택 가능 (`--audio-dup`) |
| 화면 끄기 | `--turn-screen-off`, `--screen-off-timeout` |
| IME 위치 | `--display-ime-policy=local` (Android 15+에서 VD 안에 키보드 표시) |
| 키보드 | `--keyboard=uhid` (커널 HID 장치, 삼성 키보드 한글 입력 가능) |

바꿀 부분:
- scrcpy의 소켓 프로토콜(12바이트 프레임 헤더 + Annex-B)을 유지하되, 소비자를 우리 앱 프로세스로 바꾼다. 앱이 이 스트림을 받아 fMP4로 묶어 WS로 내보낸다.
- adb 대신 Shizuku/내장 ADB로 기동.

인코딩 기본값 (MCU3 권장값 기준):
- H.264 Baseline/Main, 1280x720, 30fps로 시작해 60fps 옵션. 비트레이트 4~6 Mbps, I-frame 1초.
- H.265는 코덱 문자열만 바꾸면 되도록 파이프라인을 코덱 중립으로 둔다 (`avc1.*` ↔ `hvc1.*`).

### 2.3 네트워크 계층 (앱 프로세스)

```
VpnService.Builder()
  .addAddress("100.99.9.9", 32)   // 라우트 추가 없음
  .setMtu(1500)
  .setBlocking(false)
  .establish()
```

- 라우트를 추가하지 않으므로 폰 자신의 트래픽은 tun을 타지 않는다. tun은 "폰에 이 IP를 붙이는 용도"일 뿐이다. 역분석 문서의 "핫스팟 트래픽이 VpnService에 잡히는지" 의문에 대한 답: 잡히지 않고 잡을 필요도 없다. 100.99.9.9는 커널 local 테이블에 들어가므로 핫스팟 클라이언트가 보낸 패킷은 FORWARD가 아니라 INPUT으로 가고, `0.0.0.0:3333`에 바인딩한 서버 소켓이 받는다.
- 전제: 차가 **폰 핫스팟**에 붙어 있어야 한다. 차와 폰이 같은 집 Wi-Fi에 있으면 차의 게이트웨이가 공유기라 100.99.9.9가 폰으로 오지 않는다.
- 주소는 설정 가능하게 둔다. 100.64/10이 향후 펌웨어에서 막히면 240/4나 3.x로 전환.
- HTTP 서버: 정적 파일 + `/ws/video`, `/ws/audio`, `/ws/control`. 순수 Kotlin(Ktor CIO 또는 NanoHTTPD + 자체 WS)으로. 바이너리 프레임 직송, 송신 큐가 N프레임 이상 밀리면 다음 I-frame까지 드롭 (역분석 문서 4장의 "백프레셔").
- HTTPS는 초기엔 안 쓴다. WebCodecs/getUserMedia가 필요해지면 그때 "공개 도메인 → 100.99.9.9 A 레코드 + DNS-01 인증서를 앱에 내장"(TSL6.com 패턴)을 검토. 개인키를 앱에 넣는 방식이라 보안상 별도 판단 필요.

### 2.4 웹 클라이언트 (테슬라 브라우저에서 실행)

- 프레임워크 없이 TypeScript 단일 번들. 테슬라 브라우저 버전 차이에 덜 민감하도록 얇게 유지.
- 렌더러 인터페이스 하나에 구현 3개, 시작 시 자동 선택 + 설정 화면에서 수동 전환:
  1. `MseRenderer`: `MediaSource` + `SourceBuffer('video/mp4; codecs="avc1.42E01E"')`, 프레임당 moof 1개. 버퍼가 300ms 넘게 쌓이면 `currentTime`을 버퍼 끝 근처로 당겨 지연 고정.
  2. `WebCodecsRenderer`: secure context에서만 가능하므로 HTTPS 도입 후.
  3. `MjpegRenderer`: 최종 폴백, 5~15fps.
- fMP4 먹싱 위치: 폰(앱 프로세스)에서 한다. 브라우저 JS(jmuxer류)로 하면 차량 CPU를 쓰고 디버깅이 어렵다. moov/moof 라이터는 300~500줄 수준.
- 오디오: 같은 `MediaSource`에 `audio/mp4; codecs="mp4a.40.2"` SourceBuffer 추가 → A/V 동기화가 `<video>` 하나로 끝난다. 첫 터치에서 `video.play()` (autoplay 차단 대응).
- 입력: Pointer Events(멀티터치) → `/ws/control`에 바이너리 `[type, pointerId, action, x, y, pressure]`. 좌표는 VD 해상도로 정규화. 하단에 얇은 바: 뒤로/홈/최근앱/키보드. Pie 제스처를 안드로이드 쪽에 만드는 것보다 웹 바가 훨씬 싸다.
- 재연결: 제약 문서의 "WS ~50% 간헐 실패"를 전제로 지수 백오프 재시도. 재연결 시 서버는 I-frame부터 다시 송신.
- **`/diag` 페이지를 가장 먼저 만든다.** UA, viewport, devicePixelRatio, `MediaSource`/`WebCodecs`/`AudioContext` 존재 여부, WS 20회 연결 성공률, 디코드 fps, 마지막 에러를 화면 div에 출력. 개발자 콘솔이 없는 실차에서 유일한 관측 수단.

### 2.5 입력·키보드

- 터치/백/홈: 2.4 → shell 프로세스 `injectInputEvent(displayId)`.
- 키보드 3단계:
  1. Android 15+: `--display-ime-policy=local`로 폰 IME를 VD 안에 표시 → 차량 화면에서 폰 키보드를 직접 누른다. 구현 비용 0.
  2. Android 11~14: UHID 키보드로 차량 브라우저의 keydown을 그대로 주입. 삼성 키보드가 HID 한글 키를 조합해 준다.
  3. 웹 입력창 + 자체 IME(`InputMethodService.commitText`) 프록시. 한글 조합 문제를 완전히 피하는 최종 수단. Phase 3 이후.

### 2.6 이후 기능 (Phase 3+)

- 런처: VD의 홈 역할을 하는 우리 Activity를 shell이 `am start --display N`으로 띄움. 즐겨찾기 앱 실행.
- 분할: `settings put global enable_freeform_support 1` + `am start --windowingMode 5 --display N`으로 단일 VD 안 freeform 2창 (Tesor 방식). 인코더 1개 유지.
- 종료 시 VD에 있던 태스크가 물리 화면으로 튀어나오는 문제: 종료 순서를 "앱 `am force-stop` 또는 태스크 제거 → VD 해제"로 고정.

---

## 3. 프로토콜 요약

| 채널 | 방향 | 형식 |
|---|---|---|
| `/ws/video` | 폰→차 | 바이너리. `[u8 type][u64 pts_us][fMP4 fragment]`. type: 0=init(moov), 1=frame |
| `/ws/audio` | 폰→차 | 동일 형식, AAC fMP4 |
| `/ws/control` | 양방향 | 차→폰: 바이너리 터치/키. 폰→차: JSON 상태(해상도, 코덱, 통계) |
| `GET /diag`, `GET /` | | 정적 |

video와 audio를 한 채널로 합칠 수도 있으나, 렌더러 교체와 디버깅 편의상 분리한다.

---

## 4. 검증 순서: 가정이 틀리면 설계가 바뀌는 것부터

| # | 가정 | 틀리면 | 검증 방법 | 비용 |
|---|---|---|---|---|
| 1 | 라우트 없는 VpnService tun의 100.99.9.9로 핫스팟 클라이언트가 폰 서버에 접속된다 (삼성 포함) | 네트워크 계층 전면 재설계 | 갤럭시 폰에 20줄짜리 VpnService + HTTP 앱, 노트북을 핫스팟에 붙여 `curl 100.99.9.9:3333` | 반나절, 차 불필요 |
| 2 | 테슬라 2026.26 브라우저가 `http://100.99.9.9`를 열고 MSE H.264를 디코딩한다 | 렌더러 우선순위 변경 | `/diag` 페이지를 실차에서 연다 | 1시간, 실차 |
| 3 | Shizuku(shell)로 띄운 scrcpy-server 포크가 갤럭시에서 VD 생성 + 타 앱 실행 + 터치 주입이 된다 | 권한 계층 재검토 | scrcpy 3.x를 그대로 `--new-display --start-app`으로 실행해 본다 (adb로 먼저, 이후 Shizuku) | 하루 |
| 4 | REMOTE_SUBMIX 오디오 캡처가 대상 갤럭시/One UI 버전에서 된다 | 오디오는 BT 폴백 | scrcpy `--audio-source=output`으로 확인 | 1시간 |
| 5 | WS 간헐 실패가 재시도로 해결된다 | 폴링/HTTP 청크 전송 대안 | `/diag` WS 20회 테스트 | 실차 |
| 6 | MSE 지연이 터치 조작에 견딜 수준(<300ms) | WebRTC 도입 검토 | 아래 E2E 테스트의 지연 측정 | 자동화 |

1, 3, 4는 차 없이 첫 주에 끝낼 수 있고, 2와 5만 실차가 필요하다.

---

## 5. 테스트 환경: 어디까지 가능한가

### 5.1 세 층으로 나눈다

| 층 | 장비 | 검증 대상 | 자동화 |
|---|---|---|---|
| A. 컨테이너/CI | 없음 | 웹 클라이언트 전체, 프로토콜, fMP4 먹서, ADB 페어링 암호 로직 | 완전 자동 |
| B. 실기기, 차 없음 | 갤럭시 폰 + 노트북(또는 갤럭시탭) | VpnService 로컬 배달, 핫스팟 처리량, VD/오디오/화면끄기/IME의 삼성 quirk | 반자동 (Playwright가 노트북에서 폰에 접속) |
| C. 실차 | Model Y L | RFC1918/100.64 차단 정책, 테슬라 Chromium quirk, 디코드 성능, 전체화면/autoplay | 수동, `/diag` 체크리스트 |

C에서만 확인되는 항목은 딱 3가지(사설 IP 정책, 브라우저 quirk, 성능)로 좁혀진다. 나머지는 전부 A·B로 내린다.

### 5.2 A층: 이 컨테이너와 GitHub Actions에서 되는 것

이 세션 환경 확인 결과:

| 항목 | 상태 |
|---|---|
| Chromium | 141 (Playwright 내장). 테슬라 2026.26은 148 |
| KVM | 없음 → 안드로이드 에뮬레이터 불가 |
| JDK 21, Node 22, Python 3.11 | 있음 |
| Android SDK, ffmpeg | 없음 |

가능한 것:

1. **가짜 폰 서버 (`tools/fake-phone/`, Node).** 리포에 커밋한 짧은 H.264 Annex-B 클립을 실시간 타이밍으로 fMP4로 묶어 WS로 송출. 터치 이벤트를 받아 로그. 옵션으로 "WS 핸드셰이크 50% 거부", "패킷 지연", "I-frame 누락" 같은 고장 주입.
2. **테슬라 브라우저 프로필 (Playwright).**
   - Chrome for Testing을 148.x로 고정 (`npx @puppeteer/browsers install chrome@148`). 이 컨테이너에선 141로 대체.
   - UA: `Mozilla/5.0 (X11; GNU/Linux) ... Chromium/148.0.0.0 ... Tesla/...`
   - viewport 1920x1200에서 브라우저 크롬을 뺀 크기, DPR 1.0과 1.5 두 가지 (2026.26 픽셀 밀도 변경 대응).
   - **사설 IP 차단 재현**: `--host-resolver-rules="MAP 192.168.* ~NOTFOUND, MAP 10.* ~NOTFOUND, MAP 172.16.* ~NOTFOUND ..."`. WS 연결까지 포함해 모든 연결에 적용되므로 `page.route()`보다 정확하다.
   - 비-secure context 강제: 서버를 `http://` 로만 띄우고 `MAP 100.99.9.9 127.0.0.1` 로 실제 URL과 같은 오리진을 쓴다.
   - 렌더러 폴백: `--disable-blink-features=WebCodecs`, `addInitScript`로 `window.MediaSource` 제거.
3. **E2E 검사 항목.**
   - 디코드 fps: `requestVideoFrameCallback` 콜백 수 / 초.
   - 지연: 서버가 프레임에 붙인 pts와 클라이언트 렌더 시각 차이. 회귀 기준선으로 사용.
   - 재연결: 고장 주입 켜고 30초 안에 영상 복귀.
   - 터치 왕복: 클릭 → 가짜 서버가 받은 좌표 == 기대 좌표.
   - `/diag` 스냅샷 비교.
4. **JVM 단위 테스트 (에뮬레이터 없이).** fMP4 라이터(출력을 mp4 파서로 검증), 프로토콜 인코딩/디코딩, 터치 좌표 변환, 백프레셔 큐, ADB 페어링 SPAKE2/TLS 핸드셰이크(AOSP 테스트 벡터 대조).
5. **GitHub Actions에서만 되는 것: 에뮬레이터 계측 테스트.** ubuntu 러너는 KVM을 제공하므로 `reactivecircus/android-emulator-runner`(API 34/35)로:
   - `adb shell app_process`로 shell 서버를 띄우고 VD 생성 → 인코딩 → `adb forward tcp:3333` → 러너의 Playwright가 접속. 실기기 없이 캡처부터 렌더까지 한 줄로 검증.
   - VpnService 설정 자체(tun 생성, 주소 할당)는 에뮬레이터에서 확인 가능. 단 "외부 클라이언트 → tun IP 로컬 배달"은 에뮬레이터 NAT 구조상 불가 → B층.
   - 삼성 quirk는 못 잡는다.

### 5.3 B층: 갤럭시 폰 + 노트북

- 폰: 대상 기종(One UI 6/7). 노트북: 폰 핫스팟에 접속, Chrome for Testing 148 + 위 Playwright 프로필 그대로 실행. 즉 **A층 테스트를 서버 주소만 바꿔 실기기에 돌린다.**
- 추가 확인: 5GHz 핫스팟에서 720p60 6Mbps 지속 전송, 폰 화면 OFF 상태에서 캡처 유지, 30분 발열/배터리, 전화 수신 시 VD 앱 동작.
- 갤럭시탭 Chrome은 실행 플래그를 못 주므로 "사람이 눈으로 보는" 용도로만. 자동화는 노트북.

### 5.4 C층: 실차 체크리스트 (`/diag`)

주차 상태에서 5분 안에 끝나는 순서:
1. 핫스팟 연결 확인, `http://100.99.9.9:3333/diag` 접속 → 열리면 가정 2 통과.
2. 화면에 찍힌 UA/viewport/DPR/API 지원표 사진 촬영 (이후 A층 프로필에 반영).
3. WS 20회 성공률, 디코드 fps, 지연 수치 확인.
4. 비교용으로 `http://192.168.43.1:3333/diag`도 열어 차단 확인 (정책 변화 추적).
5. 오디오 첫 터치 후 재생, 전체화면 버튼 동작.

펌웨어 업데이트마다 1~5를 반복. 결과는 `docs/car-tests/<firmware>.md`에 기록해 펌웨어별 quirk 이력을 남긴다.

### 5.5 결론: 테스트 환경 구축 가능 여부

- 가능하다. 실차 의존 항목을 3개로 줄였고, 그 3개도 `/diag`로 5분 수동 검증이 된다.
- 이 컨테이너에서 당장 만들 수 있는 것: 웹 클라이언트 + 가짜 폰 서버 + Playwright 테슬라 프로필 + JVM 단위 테스트. Chromium 141이라 148과 완전히 같진 않지만 MSE/WS 동작은 동일 세대.
- 이 컨테이너에서 안 되는 것: 에뮬레이터(KVM 없음), 실기기. 에뮬레이터는 CI로, 실기기는 B층으로 넘긴다.

---

## 6. 로드맵

| Phase | 내용 | 산출물 | 예상 |
|---|---|---|---|
| 0 | 가정 1·3·4 검증 + `/diag` 실차 확인 | 검증 기록 | 1주 |
| 1 | Shizuku + scrcpy-server 포크 + VpnService + WS/fMP4 + MSE 클라이언트. VD 1개, 앱 1개, 터치 | 실차에서 유튜브뮤직 조작 | 3~4주 |
| 2 | 오디오, 화면 끄기, 재연결, IME(정책 local/UHID), A층 CI 완성 | 일상 사용 가능 | 3주 |
| 3 | 런처, 즐겨찾기 자동 실행, 분할(freeform), 종료 시 태스크 정리 | Tesor 5.0 동급 | 4~6주 |
| 4 | ADB 페어링 내장, adb_enabled 자동 토글, 재부팅 후 재연결 | Shizuku 제거 | 2주 |
| 5 | H.265, HTTPS+WebCodecs, WebRTC 렌더러(지연 개선) | 선택 | 미정 |

Phase 1까지가 역분석 문서 7장의 "미러링+터치(1~2주)"와 "VD+런처(1~2개월)"의 중간이다. scrcpy를 포크하므로 후자의 절반 이상이 줄어든다.

---

## 7. 리포 구조 제안

```
android/
  app/            앱 프로세스: UI, VpnService, HTTP/WS, fMP4 먹서, Shizuku 클라이언트
  shell-server/   scrcpy-server 포크 (shell UID jar). Apache-2.0 고지 유지
  adb/            (Phase 4) Shizuku adb 모듈 포크
web/
  src/            클라이언트 TS: renderer/{mse,webcodecs,mjpeg}.ts, input.ts, diag.ts
tools/
  fake-phone/     Node 가짜 폰 서버 + 고장 주입
  clips/          테스트용 H.264 클립
tests/
  e2e/            Playwright, 테슬라 프로필, A·B층 공용
  unit/           JVM 단위 테스트
docs/
  implementation-proposal.md  (이 문서)
  car-tests/      펌웨어별 실차 결과
```

---

## 8. 열린 질문

1. 대상 폰 기종과 Android 버전. IME 전략(2.5)과 오디오 캡처 경로가 여기에 달렸다.
2. 폰 화면을 반드시 따로 써야 하는가. 아니라면 Phase 1을 MediaProjection 미러링으로 먼저 내고 shell 경로를 뒤로 미룰 수 있다.
3. 라이선스: scrcpy·Shizuku는 Apache-2.0. Castla는 두 문서에 언급되지만 라이선스와 현재 상태를 확인하지 못했으므로 코드 차용 전 확인 필요.
4. HTTPS 도입 시 인증서 개인키를 앱에 내장하는 것을 허용할지.

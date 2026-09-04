# 개발 계획: ADB 내장형 테슬라 가상 디스플레이 스트리머 (단일 APK)

## Context

`docs/implementation-proposal.md`의 설계를 실제 개발 단계로 쪼갠다. 사용자 결정:
- **ADB 페어링을 처음부터 앱에 내장** (Shizuku 앱 없이). 앱 하나가 무선 디버깅 페어링 → shell UID 서버 기동까지 담당.
- **대상 기기: Galaxy S26 Ultra (Android 16 / One UI 8).** `minSdk 34`로 잡고 구형 분기는 이식하지 않는다.
- **산출물은 APK 하나.** GitHub Actions가 푸시마다 debug APK를 아티팩트로 만든다. 웹 클라이언트는 APK assets에 번들. shell 서버 dex도 같은 APK 안에 들어간다 (별도 파일 push 없음).

### 검증으로 확정된 사실 (계획의 근거)
- **scrcpy 최신은 v4.1** (`server/build_without_gradle.sh`: PLATFORM 36). v3.3.2/3.3.3에서 Android 16 QPR 대응 수정, v4.0에서 `KEY_LATENCY` 최소화. 서버 소스: `server/src/main/java/com/genymobile/scrcpy/{video,audio,control,device,wrappers,util,model}` + `server/src/main/aidl/`.
- scrcpy 서버 실행 형식: `CLASSPATH=<jar> app_process / com.genymobile.scrcpy.Server <version> key=value ...`. 옵션 키: `new_display, vd_system_decorations, vd_destroy_content, display_ime_policy, audio_source, audio_dup, max_fps, video_bit_rate, screen_off_timeout, send_frame_meta, tunnel_forward ...`. 첫 인자가 `BuildConfig.VERSION_NAME`과 다르면 예외.
- `new_display`는 Android 10+, `display_ime_policy=local`도 Android 10+ (제안서의 "15+"는 정정). API 33+에서 VD에 `TRUSTED|OWN_DISPLAY_GROUP|ALWAYS_UNLOCKED`, 34+에서 `OWN_FOCUS` 플래그. 입력 주입은 displayId 지정 후 `IInputManager.injectInputEvent`. UHID 키보드는 API 35+에서 VD에 연결 가능(v3.3).
- Android 15 QPR2에서 shell의 VD 생성 권한이 빠졌다가 **Android 16에서 복원**(Google issuetracker 384752747, "테스트 목적이라 예고 없이 제거될 수 있음"). One UI 8 DeX는 네이티브 데스크톱 모드 기반이라 `--new-display`가 갤럭시에서 동작 확인됨(Android Authority).
- Shizuku `manager/src/main/java/moe/shizuku/manager/adb/` (Apache-2.0): `AdbKey`(RSA-2048+BouncyCastle 인증서), `AdbClient`(CNXN→STLS→TLS), `AdbPairingClient`(TLS + `exportKeyingMaterial` + **JNI SPAKE2, `libadb.so` = BoringSSL prefab `io.github.vvb2060.ndk:boringssl`**), `AdbMdns`(`_adb-tls-pairing._tcp`/`_adb-tls-connect._tcp`, NsdManager). 서버 기동은 `app_process -Djava.class.path=<base.apk> ...` — **앱 자신의 base.apk를 shell이 그대로 읽을 수 있어 `/data/local/tmp` 복사 불필요.**
- 순수 Kotlin 대안: `com.flyfishxu:kadb-android:2.1.3` (Maven Central, Apache-2.0, `Kadb.pair(host, port, code)`). POM 확인 결과 의존성은 okio, spake2-java(순수 Java SPAKE2), hiddenapibypass(플랫폼 Conscrypt `exportKeyingMaterial` 호출용), BouncyCastle → **NDK 불필요**. M3의 1순위.
- GitHub Actions ubuntu-24.04: `ANDROID_HOME` 있음, platforms 34~37, NDK 27.3 기본, `reactivecircus/android-emulator-runner@v2` + KVM udev 규칙으로 에뮬레이터 가능. Chrome for Testing 148 = `148.0.7778.178`, `npx @puppeteer/browsers install chrome@148`.
- MSE, WebSocket, AudioContext, `RTCPeerConnection`은 **http:// 오리진에서 동작**(secure-context 제한 목록에 없음). WebCodecs·getUserMedia만 제한. 저지연 MSE: duration 미지정 moov + 프레임당 moof 1개.
- `VpnService.establish()`는 `addAddress`만 필수, `addRoute` 없이 유효 (AOSP javadoc). TeslaMirror가 100.99.9.9로 2026.26에서 현역.
- **[S26U/One UI 8, 2026-09-04 실측] 앱 uid 소켓은 핫스팟에서 100.99.9.9로 오는 TCP를 받지 못한다.**
  ping과 폰 자신의 브라우저(lo)와 핫스팟 주소(10.136.114.168)는 되고, tun 주소로 오는 TCP만 SYN이 리스너에
  닿기 전에 사라진다(`ListenDrops` 불변, `ss`에 SYN-RECV 없음). `ip rule`/route는 정상(앱 uid 10635는 VPN
  범위에서 제외, 핫스팟 서브넷은 테이블 1083, prohibit 없음). `protect()`, `addDisallowedApplication`,
  `Network.bindSocket`, bypassable VPN 모두 무효. 원인은 Android 14+ netd BPF의 ingress-discard
  (VPN 주소로 향하는 패킷이 VPN 인터페이스/lo 이외로 들어오면 소켓 전달 직전에 drop; 소켓 uid < 10000이면 검사 생략).
  **`adb shell "echo hi | nc -l -p 3334"`(uid 2000)로 같은 주소·핫스팟에서 TCP 접속 성공** → 서버 소켓은 shell uid가 열어야 한다.
  앱은 `ip`·`/proc/sys` 읽기도 SELinux로 막혀 있어(netlink bind EACCES) 라우팅 진단은 adb에서만 가능.
- 이 원격 세션: `dl.google.com`·Gradle·Maven 접근 가능 → **여기서 Android SDK 받아 APK 빌드 검증 가능.** `raw.githubusercontent.com` 200(파일 단위 취득), `github.com`/`codeload` 403 → scrcpy/Shizuku는 파일별로 가져오거나 `add_repo`로 붙인다. KVM 없음(에뮬레이터는 CI 전용). Chromium 141 있음.

---

## 아키텍처 (제안서 대비 변경점)

1. **Shizuku 단계 삭제.** 앱이 직접 adbd에 페어링·접속 → `CLASSPATH=<own base.apk> app_process / com.carcast.server.Server <build-id> key=value…` 실행. 서버 dex는 `app` 모듈에 `implementation(project(":shell-server"))`로 포함.
2. **adb `shell:` 스트림을 세션 내내 열어둔다** (scrcpy 방식). 스트림이 닫히면 서버가 죽음 = 킬 스위치. Shizuku #1125류(데몬화한 자식이 죽는 문제) 회피.
3. **HTTP/WebSocket 서버는 shell 프로세스 안에 있다 (M1 실측으로 확정).** 차는 shell uid 소켓에만 닿는다(위 "검증된 사실").
   따라서 캡처·인코딩·fMP4·WS 송출·입력 주입이 모두 `com.carcast.server.Server` 한 프로세스에서 돈다. 앱은
   tun 주소 유지(VpnService), 페어링/기동/킬 스위치, UI만 맡고 `127.0.0.1:3333/api/status`를 폴링해 상태를 보여준다.
   앱↔서버 IPC는 상태 조회와 설정 전달 정도로 줄어들고(HTTP/WS 자체를 쓰면 됨), 예전 3항의 유닉스 소켓 설계는 불필요.
   순수 JVM 모듈 `core`(HTTP/WS, MediaHub, ClipSource, StreamSession, ServerMain)를 앱과 shell 서버가 공유하고,
   PC에서 `./gradlew :core:run`으로 같은 코드를 띄워 Playwright를 돌릴 수 있다.
4. **무선 디버깅 킬 스위치**: 세션 종료 시 마지막 명령으로 `settings put global adb_wifi_enabled 0`. 시작 시 `_adb-tls-connect._tcp`가 없으면 "무선 디버깅 켜기" 안내 타일(사용자 조작 필요).
5. adbd가 향후 localhost 바인딩을 막을 가능성(CVE-2026-0073 후속 논의) 대비: mDNS로 얻은 **wlan0 주소**로 접속, 127.0.0.1은 폴백.

---

## 리포 구조 / Gradle 모듈

```
settings.gradle.kts, build.gradle.kts, gradle/libs.versions.toml, gradlew
app/              com.android.application — 최종 APK. UI, VpnService(tun 주소), 페어링/기동(M3), 상태 폴링
  src/main/java/com/carcast/{ui,vpn/CarVpnService.kt,service/StreamService.kt,shell/(M3)}
  src/main/assets/web/   ← esbuild 산출물 (git-ignored)
core/             kotlin-jvm — HTTP/WS 서버, MediaHub, ClipSource, StreamSession, ServerMain. 앱·shell 서버 공유, PC에서 실행 가능
adb/              com.android.library — 페어링/접속. Kadb 의존 또는 Shizuku adb 포트(+JNI)
shell-server/     com.android.library — app_process 진입점 com.carcast.server.Server(+core) → M4에서 scrcpy v4.1 server 포크 합류
mux/              kotlin-jvm — fMP4 먹서(+CLI). M4에서 shell 서버가 사용
web/              vanilla TS + esbuild → app/src/main/assets/web/
  src/{main,diag,input,protocol}.ts, renderer/{mse,mjpeg}.ts, transport/ws.ts
tools/fake-phone/ Node: web/ 서빙 + 커밋된 H.264 클립을 fMP4/WS로 송출 + 고장 주입
tools/clips/      짧은 Annex-B H.264(+AAC) 클립
tests/e2e/        Playwright, 테슬라 프로필(Chrome for Testing 148, UA, 1920x1200, DPR, host-resolver-rules)
docs/             implementation-proposal.md, dev-plan.md(이 문서), car-tests/<firmware>.md, LICENSES/
.github/workflows/{android.yml, web.yml, emulator.yml}
```

- `app` `preBuild.dependsOn(buildWeb)` (Exec: `npm ci && npm run build` in `web/`).
- debug 빌드는 R8 off. minify 시 `-keep class com.carcast.server.** { *; }`.
- `shell-server`는 compileSdk 36 공개 스텁 + 리플렉션 + AIDL. Context·리소스 사용 없음.

---

## 개발 스텝 (위험 순)

각 스텝: 만들 것 / 가져올 것 / 끝났을 때 APK가 하는 일 / 검증. **[세션]** = 이 원격 세션에서 가능, **[폰]** = S26U 필요, **[차]** = 실차 필요.

### M0. 코드 없는 스파이크 [폰, 반나절]
- 노트북에서 stock scrcpy 4.1: `scrcpy --new-display=1280x720/160 --no-vd-system-decorations --start-app=<앱> --display-ime-policy=local --audio-source=output`, 이어서 `--audio-source=playback --audio-dup`, `--turn-screen-off`, `--screen-off-timeout=300`, `--keyboard=uhid`.
- `adb shell`에서 `CLASSPATH=/data/local/tmp/scrcpy-server.jar app_process / com.genymobile.scrcpy.Server 4.1 tunnel_forward=true ...`로 데스크톱 없이 서버 단독 기동 확인.
- 기록: `docs/car-tests/s26u-one-ui-8.md` — VD 생성/타 앱 실행/터치/IME 위치/폰 스피커 무음/화면 OFF 중 인코딩/DeX 런처 노출 여부.
- 결정 사항: One UI 8에서 `output` vs `playback` 오디오, `vd_system_decorations` 값.

### M1. 스캐폴드 + VPN/HTTP 스파이크 APK + CI 빌드 [세션 → 폰 → 차]
- 만들 것: Gradle 루트, `app`(Start 버튼 하나), `CarVpnService`(`addAddress("100.99.9.9",32)`, 라우트·DNS 없음, `setBlocking(false)`), `HttpServer`(`0.0.0.0:3333`, 정적 `/diag` 임시 HTML), 포그라운드 서비스, `.github/workflows/android.yml`(setup-node 22 → setup-java 17 → setup-gradle → `assembleDebug test` → `app-debug.apk` 아티팩트).
- 이 세션에서 SDK 설치 후 `assembleDebug` 통과까지 확인.
- 검증: [폰] 핫스팟에 붙은 노트북에서 `curl http://100.99.9.9:3333/diag` 200 (2.4/5GHz). [차] 2026.26에서 같은 URL 열림, `http://192.168.x.x:3333`은 차단 확인. **실패 시 주소/전송 재설계 후 진행.**

### M2. 웹 클라이언트 + 가짜 폰 + Playwright [세션, M1과 병행]
- `web/`: `diag.ts`(UA, viewport, DPR, `isTypeSupported('video/mp4; codecs="avc1.42E01E"')`, WebCodecs/AudioContext/RTCPeerConnection 유무, WS 20회 성공률, `requestVideoFrameCallback` fps, 마지막 에러 → DOM), `renderer/mse.ts`(트랙별 SourceBuffer, duration 미지정, buffered end − currentTime > 300ms면 따라잡기), `transport/ws.ts`(`[u8 type][u64 pts_us][payload]`, 지수 백오프), `input.ts`(Pointer Events → 정규화 좌표), 하단 바(뒤로/홈/최근/키보드).
- `tools/fake-phone/`: 커밋된 클립을 실시간 송출, `--ws-reject=0.5 --drop-idr --delay-ms` 고장 주입. fMP4 조각은 M4의 Kotlin 먹서 단위 테스트가 생성한 파일을 재생(JS 먹서 중복 구현 안 함).
- `tests/e2e/`: Chrome for Testing 148, `--host-resolver-rules="MAP 100.99.9.9 127.0.0.1, MAP 192.168.* ~NOTFOUND, MAP 10.* ~NOTFOUND, MAP 172.16.* ~NOTFOUND"`, 검사: diag 스냅샷, fps ≥ 25, pts 대비 렌더 지연 < 300ms, 고장 주입 하 30초 내 복구, 터치 왕복 좌표. `.github/workflows/web.yml`.
- 검증: CI 녹색. M1 APK가 서빙하는 `/diag`를 실제 것으로 교체 → [차] 수치 기록.

### M3. ADB 페어링 + 접속 + `app_process`로 서버 기동 [세션(코드) → 폰]
- **M1 후속으로 이미 된 것:** `com.carcast.server.Server`가 `core`의 서버(HTTP/WS + 테스트 클립)를 shell uid로 띄운다.
  당장은 PC에서 `adb shell 'CLASSPATH=$(pm path com.carcast | cut -d: -f2) app_process / com.carcast.server.Server <git-sha> port=3333'`
  로 기동(앱 화면에 명령 표시). M3의 목표는 이 명령을 앱이 내장 ADB로 직접 실행하는 것.
- `adb/` 결정 순서: ① Maven의 Kadb로 `pair`/`connect`/`shell` 시도 (NDK 불필요). ② 안 되면 Shizuku `adb/` 포트: `AdbKey, AdbKeyStore, AdbProtocol, AdbMessage, AdbClient, AdbMdns, AdbPairingClient, AdbException` + `jni/{adb_pairing.cpp,misc.cpp,CMakeLists.txt}`(BoringSSL prefab), 숨은 API `com.android.org.conscrypt`는 `org.conscrypt:conscrypt-android`의 공개 `exportKeyingMaterial`로 교체, 인증서는 BouncyCastle 유지.
- 앱 UI: 페어링 = 포그라운드 서비스 알림의 `RemoteInput`으로 6자리 코드 입력(Shizuku `AdbPairingService` 패턴) + 무선 디버깅 설정 딥링크, `_adb-tls-pairing` mDNS로 포트 발견. 접속 = `_adb-tls-connect` → `shellCommand("id")`.
- `shell-server` 최소 `Server.main`: uid 출력, `/dev/uhid` 열기, TRUSTED VD 생성/파괴 (`wrappers/{ServiceManager,DisplayManager}`, `FakeContext`, `Workarounds` 이식). 실행: `CLASSPATH=<sourceDir> app_process / com.carcast.server.Server <build-id>`.
- 단위 테스트: ADB 메시지 프레이밍/CRC, `adbkey.pub` 인코딩(실제 adb 키와 대조), mDNS 로컬 판정.
- 검증: [폰] 페어링 → `uid=2000(shell)` → "VD created id=N" 표시. shell 스트림 끊으면 서버 종료. 무선 디버깅 off/on, 재부팅 후 포트 재발견. 유닉스 소켓 vs TCP 폴백 판정.

### M4. scrcpy 포크: VD 영상을 앱으로 → 브라우저로 [세션 → 폰 → 차]
- 이식(`raw.githubusercontent.com`에서 v4.1 태그 파일별로): `Server, Options(축소), AndroidVersions, CleanUp, Workarounds, video/{SurfaceCapture,NewDisplayCapture,SurfaceEncoder,DisplaySizeMonitor,VideoCodec,…}, device/{Device,Streamer,…}, wrappers/*, util/*, model/NewDisplay, control/*`, **aidl 전부**. 패키지 → `com.carcast.server`. 버전 문자열 검사 → build-id. `DesktopConnection` → 앱 소켓+토큰 접속.
- 앱: `ServerLink`가 scrcpy 스트림(코덱 메타 12B, 패킷 헤더 12B: pts+config/keyframe 플래그+size, Annex-B) 파싱. `Fmp4Writer.kt`(순수 JVM): `ftyp+moov`(mvhd duration 0, `mehd` 없음, `avcC`), 프레임당 `moof(tfhd default-base-is-moof, tfdt, trun 1 sample)+mdat(AVCC)`. 단위 테스트: 박스 파서 + CI의 ffprobe로 검증, 산출물을 M2 Playwright에 공급. `VideoPump`: 클라이언트별 큐, N프레임 초과 시 다음 IDR까지 드롭, 신규 접속 시 moov+마지막 GOP 재전송.
- 설정 연결: `new_display=1280x720/160, vd_system_decorations=<M0 결과>, vd_destroy_content=true, max_fps, video_bit_rate, video_codec=h264, display_ime_policy=local`, 앱 실행은 scrcpy 컨트롤 메시지 `TYPE_START_APP`.
- 검증: [폰] 핫스팟 노트북에서 `BASE_URL=http://100.99.9.9:3333`로 Playwright 실행. [차] `/diag` fps·지연.

### M5. 입력 [세션 → 폰]
- `ControlBridge`: WS 컨트롤 프레임 → scrcpy `ControlMessage` 와이어 포맷 그대로 (이식한 `ControlMessageReader` 무수정). 멀티터치, 백/홈/최근앱 키, UHID 키보드(`UhidManager`, API 35+ VD 연결).
- 검증: Playwright 좌표 왕복. [폰] 스크롤/롱프레스/핀치, VD 안 삼성 키보드로 한글 입력.

### M6. 오디오 [세션 → 폰 → 차]
- 이식 `audio/{AudioCapture,AudioDirectCapture,AudioPlaybackCapture,AudioEncoder,AudioCodec,AudioSource}`. M0 결과로 `output` vs `playback+audio_dup` 선택. AAC-LC 48kHz, `Fmp4Writer` 오디오 트랙(`mp4a+esds`), 웹은 같은 MediaSource에 오디오 SourceBuffer, 첫 터치 `video.play()`.
- 검증: Playwright 오디오 버퍼 진행. [차] A/V 동기, 폰 스피커 무음 설정.

### M7. 라이프사이클/화면 끄기/재연결/킬스위치 [세션 → 폰]
- `Device.setDisplayPower`(API 35 `requestDisplayPower`), `screen_off_timeout`, 재연결 시 I-frame 재송신, 재부팅 후 포트 재발견, 종료 순서 `am force-stop`/태스크 제거 → VD 파괴 → 스트림 닫기 → `adb_wifi_enabled 0`.
- 검증: [폰] 화면 OFF 30분 연속(발열/배터리 `/diag` 로그), 통화 수신, 재부팅 후 한 번 탭으로 재시작.

### M8. 에뮬레이터 CI (선택) [세션]
- `emulator.yml`(nightly/manual): KVM udev → `android-emulator-runner@v2` api 35 google_apis x86_64 → APK 설치 → `adb shell "CLASSPATH=$(pm path pkg|cut -d: -f2) app_process / com.carcast.server.Server <id> selftest=true"` → `adb forward tcp:3333` → Playwright. VD 생성·인코딩·AIDL 깨짐(scrcpy #6362류)을 조기 포착.

### M9. 이후
런처 Activity, 즐겨찾기, freeform 분할, H.265(`hvc1`), HTTPS+WebCodecs, WebRTC 수신 렌더러(http에서 가능 확인됨).

---

## 이 세션에서 바로 착수할 범위
M1(스캐폴드·VPN/HTTP·CI·이 컨테이너에서 APK 빌드 통과) + M2(웹 클라이언트·가짜 폰·Playwright) + M3/M4의 코드 이식 준비(scrcpy v4.1 파일 목록 확보). M0은 사용자가 폰으로 수행하고 결과를 `docs/car-tests/`에 기록해 주면 M4 설정값을 확정한다.

## 라이선스
`docs/LICENSES/`에 scrcpy(Genymobile)·Shizuku(RikkaApps)·Kadb Apache-2.0 NOTICE, BoringSSL/BouncyCastle/Conscrypt 고지.

## M0/M3에서 판정할 미확인 항목
1. Shizuku #1125(Android 16 QPR1) 근본 원인 — shell 스트림 유지로 우회.
2. shell → untrusted_app 추상 유닉스 소켓 SELinux 허용 여부 — TCP 폴백.
3. One UI 8의 `REMOTE_SUBMIX` vs `playback` 동작.
4. VD 안 DeX 런처 노출과 `vd_system_decorations=false` 효과.

## 검증 요약
- 세션/CI: `./gradlew assembleDebug test` 통과, Playwright(Chrome 148 프로필) 녹색, `Fmp4Writer` 산출물 ffprobe 검증.
- 폰: 핫스팟 노트북 `curl`, Playwright를 폰 주소로 실행, 페어링→uid 2000→VD 생성 표시.
- 차: 펌웨어별 `/diag` 체크리스트(URL 열림, 192.168 차단, WS 성공률, fps, 지연, 오디오 재생) → `docs/car-tests/<firmware>.md`.
- **지금까지의 결과(가정별 ✅/⏳)는 [verification-log.md](verification-log.md)에 모은다.** 이 문서는 계획, 그쪽은 결과.

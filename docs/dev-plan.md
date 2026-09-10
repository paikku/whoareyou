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
2. **서버는 adb 스트림과 분리해(`setsid nohup … daemon=true`) 띄우고 재부팅 전까지 둔다.** 무선 디버깅이 Wi-Fi 전용이라
   차에서는 adb가 없기 때문(처음 계획했던 "스트림 유지 = 킬 스위치"는 폐기). 단 adbd 자체가 멈추면 init이 adbd의 cgroup을
   통째로 SIGKILL하므로(실측, shell은 탈출 불가) **USB 디버깅 토글을 켜 둬 adbd를 살려 두는 것이 운용 조건**이다.
3. **HTTP/WebSocket 서버는 shell 프로세스 안에 있다 (M1 실측으로 확정).** 차는 shell uid 소켓에만 닿는다(위 "검증된 사실").
   따라서 캡처·인코딩·fMP4·WS 송출·입력 주입이 모두 `com.carcast.server.Server` 한 프로세스에서 돈다. 앱은
   tun 주소 유지(VpnService), 페어링/기동/킬 스위치, UI만 맡고 `127.0.0.1:3333/api/status`를 폴링해 상태를 보여준다.
   앱↔서버 IPC는 상태 조회와 설정 전달 정도로 줄어들고(HTTP/WS 자체를 쓰면 됨), 예전 3항의 유닉스 소켓 설계는 불필요.
   순수 JVM 모듈 `core`(HTTP/WS, MediaHub, ClipSource, StreamSession, ServerMain)를 앱과 shell 서버가 공유하고,
   PC에서 `./gradlew :core:run`으로 같은 코드를 띄워 Playwright를 돌릴 수 있다.
4. **킬 스위치는 loopback 전용 `POST /api/stop`**(앱 "서버 종료"). 무선 디버깅이 꺼져 있으면 앱이 개발자 옵션 딥링크로 안내한다.
5. adb 접속은 항상 `127.0.0.1:<포트>`(adbd는 loopback에도 리슨). mDNS `_adb-tls-connect`는 포트를 얻는 데만 쓰고, 못 찾으면 수동 포트 입력.

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
- **구현됨, 폰 검증 완료(2026-09-05):** `adb/`는 Kadb **2.1.1**(`com.flyfishxu:kadb-android`; 2.1.2+는 compileSdk 37 요구, SPAKE2 의존성은 JitPack)로
  `AdbIdentity`(앱 키 `files/adb/adbkey.pem`), `AdbMdns`(NsdManager, 자기 주소로 resolve되는 레코드만), `AdbLink`(127.0.0.1:포트 접속·`id`·shell v2 스트림),
  `ServerCommand`(`CLASSPATH='<sourceDir>' exec app_process / com.carcast.server.Server <sha> port=3333`), `ServerOutput`(서버 stdout 파싱).
  앱: `AdbPairingService`(알림 RemoteInput으로 6자리 코드, `_adb-tls-pairing` 발견, 수동 포트 폴백), `ShellServerLink`(`/api/status`가 죽어 있고 Wi-Fi일 때만
  adb로 **분리 실행** `setsid nohup … daemon=true`, 미페어링이면 대기), 개발자 옵션 무선 디버깅 딥링크(`:settings:fragment_args_key=toggle_adb_wireless`).
- **M8 TCP 모드(구현됨, 폰 검증 대기):** 무선 디버깅으로 처음 붙은 직후 앱이 `tcpip:<랜덤 고포트>`를 보내 adbd를 TCP 모드로 돌린다
  (`AdbLink.tcpip`, `ShellServerLink.openLink`/`switchToTcpMode`). 이 포트는 Wi-Fi 게이트가 없어 핫스팟에서도 열려 있으므로
  이후 접속은 loopback으로 하고, **차 안에서도 서버를 다시 띄울 수 있다.** adbd가 죽지 않으니 아래 cgroup SIGKILL도 일어나지 않는다.
  전환은 adbd를 재시작시켜 그 라운드의 링크를 잃으므로 서버 기동 **전에만** 하고, 2회 실패하면 포기하고 무선 디버깅을 쓴다.
  덤으로 `persist.adb.tcp.port`를 설정해 보고(재부팅 지속 실험, [hotspot-only.md](hotspot-only.md) §3) 결과를 로그에 남긴다. 앱에 "TCP 모드 끄기" 버튼.
- **배경과 남은 계획: [hotspot-only.md](hotspot-only.md)** (테소르도 Shizuku라 같은 제약을 갖는다는 확인, 미러 모드 하위 티어 등).
- **제약(실측, 2026-09-05): 무선 디버깅은 Wi-Fi 클라이언트 연결 중에만 켜지고 Wi-Fi가 끊기면 자동으로 꺼진다.** 차(모바일 데이터+핫스팟)에서는 adb가 없다.
  따라서 서버는 집 Wi-Fi에서 분리 실행해 재부팅 전까지 유지하고, 킬 스위치는 adb 스트림이 아니라 **loopback 전용 `POST /api/stop`**(앱 "서버 종료")이다.
  서버 로그는 실행마다 새 파일 `/data/local/tmp/carcast/server-<epoch>.log`(이전 로그는 실행 전에 삭제)와 `GET /api/log`. 이전 계획의 "shell 스트림 유지 = 킬 스위치"와 "`adb_wifi_enabled` 토글"은 폐기.
- **수명(실측, 2026-09-05):** ① 실행 명령 안의 `pkill -f <클래스명>`은 그 명령을 도는 `sh -c` 자신을 죽인다(명령줄에 클래스명이 있음) — 이전 서버는 pid 파일과
  `^app_process / …` 앵커 패턴으로만 끝낸다. ② 무선 디버깅이 꺼질 때 USB 디버깅도 꺼져 있으면 adbd가 멈추고 init이 adbd의 cgroup(`/system/uid_0/pid_N`)을
  SIGKILL한다. shell은 cgroup을 못 벗어나므로(`step: cgroup` 줄, 전 경로 EACCES) **USB 디버깅 토글 ON이 운용 조건**. 켜 두면 핫스팟 전환 후 유지·노트북 접속 ✅.
- `adb/` 결정 순서: ① Maven의 Kadb로 `pair`/`connect`/`shell` 시도 (NDK 불필요). ② 안 되면 Shizuku `adb/` 포트: `AdbKey, AdbKeyStore, AdbProtocol, AdbMessage, AdbClient, AdbMdns, AdbPairingClient, AdbException` + `jni/{adb_pairing.cpp,misc.cpp,CMakeLists.txt}`(BoringSSL prefab), 숨은 API `com.android.org.conscrypt`는 `org.conscrypt:conscrypt-android`의 공개 `exportKeyingMaterial`로 교체, 인증서는 BouncyCastle 유지.
- 앱 UI: 페어링 = 포그라운드 서비스 알림의 `RemoteInput`으로 6자리 코드 입력(Shizuku `AdbPairingService` 패턴) + 무선 디버깅 설정 딥링크, `_adb-tls-pairing` mDNS로 포트 발견. 접속 = `_adb-tls-connect` → `shellCommand("id")`.
- `shell-server` 최소 `Server.main`: uid 출력, `/dev/uhid` 열기, TRUSTED VD 생성/파괴 (`wrappers/{ServiceManager,DisplayManager}`, `FakeContext`, `Workarounds` 이식). 실행: `CLASSPATH=<sourceDir> app_process / com.carcast.server.Server <build-id>`.
- 단위 테스트: ADB 메시지 프레이밍/CRC, `adbkey.pub` 인코딩(실제 adb 키와 대조), mDNS 로컬 판정.
- 검증: [폰] 페어링 → `uid=2000(shell)` → "VD created id=N" 표시. shell 스트림 끊으면 서버 종료. 무선 디버깅 off/on, 재부팅 후 포트 재발견. 유닉스 소켓 vs TCP 폴백 판정.

### M4. scrcpy 포크: VD 영상을 앱으로 → 브라우저로 [세션 → 폰 → 차]
- **구현됨, 폰 검증 완료(2026-09-05, 핫스팟 노트북에서 라이브 영상·유튜브 실행):** `shell-server`에 scrcpy v4.1의 `Workarounds, FakeContext, AndroidVersions, wrappers/*(ServiceManager, DisplayManager, WindowManager, ActivityManager, InputManager …), util/{Ln,Command,IO,Settings}, model/Size, display/DisplayInfo, video/VideoConstraints`와
  aidl `IDisplayWindowListener`, `IOnPrimaryClipChangedListener`, 스텁 `android.content.IContentProvider`를 **원본 패키지 그대로** 복사(Apache-2.0, `docs/LICENSES/scrcpy-LICENSE.txt`).
  자체 코드: `DisplayCapture`(NewDisplayCapture의 플래그 그대로 TRUSTED VD 생성, IME 로컬), `H264Encoder`(SurfaceEncoder 설정: LATENCY 1, REPEAT 100ms, GOP 2s, 프로파일 미지정),
  `DisplayVideoSource`(core `VideoSource` 구현, `am start --display N`으로 앱 실행), core `EncodedH264Sink`(Annex-B → `Fmp4Writer` → `MediaHub`, 클립으로 단위 테스트).
  서버 옵션 `display=1280x720/160 bitrate=4000000 fps=30 decorations=false app=<pkg> source=clip`. `POST /api/app?name=`으로 실행 중 앱 전환, 웹 하단 바 ▶ 버튼.
  VD 생성 실패 시 자동으로 테스트 클립으로 대체(로그 `라이브 소스 실패`). 회전·크롭·리사이즈(OpenGL 경로)는 미이식 — 가로 고정 VD 전제.
- 이식(`raw.githubusercontent.com`에서 v4.1 태그 파일별로): `Server, Options(축소), AndroidVersions, CleanUp, Workarounds, video/{SurfaceCapture,NewDisplayCapture,SurfaceEncoder,DisplaySizeMonitor,VideoCodec,…}, device/{Device,Streamer,…}, wrappers/*, util/*, model/NewDisplay, control/*`, **aidl 전부**. 패키지 → `com.carcast.server`. 버전 문자열 검사 → build-id. `DesktopConnection` → 앱 소켓+토큰 접속.
- 앱: `ServerLink`가 scrcpy 스트림(코덱 메타 12B, 패킷 헤더 12B: pts+config/keyframe 플래그+size, Annex-B) 파싱. `Fmp4Writer.kt`(순수 JVM): `ftyp+moov`(mvhd duration 0, `mehd` 없음, `avcC`), 프레임당 `moof(tfhd default-base-is-moof, tfdt, trun 1 sample)+mdat(AVCC)`. 단위 테스트: 박스 파서 + CI의 ffprobe로 검증, 산출물을 M2 Playwright에 공급. `VideoPump`: 클라이언트별 큐, N프레임 초과 시 다음 IDR까지 드롭, 신규 접속 시 moov+마지막 GOP 재전송.
- 설정 연결: `new_display=1280x720/160, vd_system_decorations=<M0 결과>, vd_destroy_content=true, max_fps, video_bit_rate, video_codec=h264, display_ime_policy=local`, 앱 실행은 scrcpy 컨트롤 메시지 `TYPE_START_APP`.
- 검증: [폰] 핫스팟 노트북에서 `BASE_URL=http://100.99.9.9:3333`로 Playwright 실행. [차] `/diag` fps·지연.

### M5. 입력 [세션 → 폰]
- **구현됨, 폰 검증 완료(2026-09-05, `injected=72 injectFailed=0`):** 웹 컨트롤 패킷(터치/키/텍스트) → core `ControlMessage` 파서(단위 테스트) → shell `InputInjector`:
  멀티터치 MotionEvent(finger, SOURCE_TOUCHSCREEN, `setDisplayId`), KeyEvent, 텍스트는 VIRTUAL_KEYBOARD `getEvents`로 되는 문자만 키 이벤트, 나머지(한글)는 클립보드 + `KEYCODE_PASTE`.
  scrcpy `ControlMessage` 와이어 포맷은 쓰지 않고(우리 웹 포맷이 이미 있음) 주입 로직만 Controller에서 옮김. `/api/status.injected/injectFailed`. UHID 키보드는 미이식.
- `ControlBridge`: WS 컨트롤 프레임 → scrcpy `ControlMessage` 와이어 포맷 그대로 (이식한 `ControlMessageReader` 무수정). 멀티터치, 백/홈/최근앱 키, UHID 키보드(`UhidManager`, API 35+ VD 연결).
- 검증: Playwright 좌표 왕복. [폰] 스크롤/롱프레스/핀치, VD 안 삼성 키보드로 한글 입력.

### M6. 오디오 [세션 → 폰 → 차]
- 이식 `audio/{AudioCapture,AudioDirectCapture,AudioPlaybackCapture,AudioEncoder,AudioCodec,AudioSource}`. M0 결과로 `output` vs `playback+audio_dup` 선택. AAC-LC 48kHz, `Fmp4Writer` 오디오 트랙(`mp4a+esds`), 웹은 같은 MediaSource에 오디오 SourceBuffer, 첫 터치 `video.play()`.
- 검증: Playwright 오디오 버퍼 진행. [차] A/V 동기, 폰 스피커 무음 설정.

### M7. 라이프사이클/화면 끄기/재연결/킬스위치 [세션 → 폰]
- **일부 구현(2026-09-05):** `ScreenPower` — scrcpy `Device.setDisplayPower` 그대로: 물리 디스플레이 전부 `SurfaceControl.setDisplayPowerMode`(Android 14+는 `DisplayControl` 토큰). Android 15의 `requestDisplayPower`는 scrcpy도 꺼 둔 경로(#5530)이고 S26U에서 실패 확인(d98be88). `stay_on_while_plugged_in=7`(서버 종료 시 복원, 강제로 끈 화면도 복원).
  서버 옵션 `stay_awake=true`(기본) `screen_off=true`, `GET/POST /api/screen?on=0|1`, 웹 📵 버튼. 킬 스위치는 M3의 `POST /api/stop`. 전원 버튼은 전체 정지이므로 쓰지 않는다.
- 남은 것: 📵 폰 검증(SurfaceControl 경로, `fdc2350`), `screen_off_timeout`, 재연결 시 I-frame 재송신, 재부팅 후 포트 재발견, 종료 순서 `am force-stop`/태스크 제거 → VD 파괴 → 스트림 닫기.
- 검증: [폰] 화면 OFF 30분 연속(발열/배터리 `/diag` 로그), 통화 수신, 재부팅 후 한 번 탭으로 재시작.

### M8. 에뮬레이터 CI (선택) [세션]
- `emulator.yml`(nightly/manual): KVM udev → `android-emulator-runner@v2` api 35 google_apis x86_64 → APK 설치 → `adb shell "CLASSPATH=$(pm path pkg|cut -d: -f2) app_process / com.carcast.server.Server <id> selftest=true"` → `adb forward tcp:3333` → Playwright. VD 생성·인코딩·AIDL 깨짐(scrcpy #6362류)을 조기 포착.

### M9. 이후
런처 Activity, 즐겨찾기, freeform 분할, H.265(`hvc1`), HTTPS+WebCodecs, WebRTC 수신 렌더러(http에서 가능 확인됨).

---

## 진행 상황과 다음 착수 (2026-09-05)
- 완료·폰 검증: M0, M1, M2, M3, M4, M5. 실차(C층) 전제 조건(핫스팟에서 shell 서버 접속)도 통과.
- 다음: **M6 오디오**(`output` 캡처 → AAC → fMP4 오디오 트랙 → 웹 SourceBuffer; M0에서 One UI 8 캡처 동작 확인됨),
  **M7** 📵 재검증(`fdc2350`), 하룻밤·재부팅 후 복구, "서버 종료" 킬 스위치 확인, 핫스팟 Playwright(`BASE_URL`) 수치.
- 차가 오면 C층 `/diag` 체크리스트.

## 라이선스
`docs/LICENSES/`에 scrcpy(Genymobile)·Shizuku(RikkaApps)·Kadb Apache-2.0 NOTICE, BoringSSL/BouncyCastle/Conscrypt 고지.

## M0/M3에서 판정한 항목 (결과)
1. Shizuku #1125류(데몬화한 자식이 죽는 문제) — 원인은 adbd 종료 시 init의 cgroup SIGKILL. USB 디버깅 토글로 adbd를 살려 두면 해결(위 M3 "수명").
2. shell → 앱 유닉스 소켓 IPC — 서버가 shell로 옮겨가며 불필요. 앱↔서버는 loopback HTTP/WS.
3. One UI 8 오디오: `output`은 원격 재생 + 폰 무음, `playback --audio-dup`은 양쪽 재생 (M0). M6에서 `output` 기본.
4. `vd_system_decorations=false`면 VD에 아무것도 안 뜨고(앱만), 켜면 DeX식 바탕화면·작업표시줄 노출 (M0 7번).

## 검증 요약
- 세션/CI: `./gradlew assembleDebug test` 통과, Playwright(Chrome 148 프로필) 녹색, `Fmp4Writer` 산출물 ffprobe 검증.
- 폰: 핫스팟 노트북 `curl`, Playwright를 폰 주소로 실행, 페어링→uid 2000→VD 생성 표시.
- 차: 펌웨어별 `/diag` 체크리스트(URL 열림, 192.168 차단, WS 성공률, fps, 지연, 오디오 재생) → `docs/car-tests/<firmware>.md`.
- **지금까지의 결과(가정별 ✅/⏳)는 [verification-log.md](verification-log.md)에 모은다.** 이 문서는 계획, 그쪽은 결과.

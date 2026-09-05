# CarCast

안드로이드 폰의 가상 디스플레이를 테슬라 차량 브라우저로 스트리밍하는 프로젝트. 산출물은 APK 하나.

- 설계 제안: [docs/implementation-proposal.md](docs/implementation-proposal.md)
- 개발 계획(마일스톤 M0~M9): [docs/dev-plan.md](docs/dev-plan.md)
- **테스트 가이드(어디서 무엇을): [docs/testing-guide.md](docs/testing-guide.md)**
- **검증 기록(무엇을 어떤 테스트로 확인했나, 가정별 상태): [docs/verification-log.md](docs/verification-log.md)**
- 실차/실기기 원본 표: [docs/car-tests/](docs/car-tests/)

## 현재 상태 (2026-09-05: M1~M5 폰에서 검증 완료, M7 수정 후 검증 대기, M6 오디오 미착수)

앱은 VpnService로 `100.99.9.9`를 폰에 붙인다. `http://100.99.9.9:3333`의 웹 클라이언트와 스트림은
**shell uid 프로세스**(`com.carcast.server.Server`, `app_process`로 기동)가 서빙한다. Android 14+는 VPN 주소로
오는 패킷을 앱 uid 소켓에는 전달하지 않기 때문이다(실측: docs/dev-plan.md). 서버는 scrcpy에서 가져온 방식으로
**가상 디스플레이**(1280x720)를 만들어 H.264로 인코딩해 fMP4/WebSocket으로 송출하고(`POST /api/app`으로 그 화면에 앱 실행),
VD를 못 만들면 번들된 테스트 클립으로 대체한다. 브라우저 터치·키·텍스트는 그 VD에 주입되고(`/ws/control`),
📵 버튼은 폰 화면만 끈다(`/api/screen`). 차에서 `/diag`를 열면 브라우저 환경·API
지원·WS 성공률·디코드 fps·사설 주소 차단 여부를 측정해 폰 서버에 저장한다(`/api/reports`, 앱의 공유 버튼). M3부터 앱이 폰 자신의 무선 디버깅에 페어링(Kadb, 알림에 코드 입력)해 이 서버를 **분리 실행**한다. 무선 디버깅은
Wi-Fi 연결 중에만 켜지므로 집 Wi-Fi에서 띄우고, 서버는 재부팅 전까지(차에서도) 유지된다. 끄기는 앱의 "서버 종료".

**폰에서 쓰는 순서 (실측으로 확정):**
1. 집 Wi-Fi 연결, 개발자 옵션에서 **무선 디버깅**과 **USB 디버깅** 토글을 모두 켠다. USB 디버깅은 케이블용이 아니라
   Wi-Fi가 끊겨 무선 디버깅이 꺼질 때 시스템이 adbd를 멈추지 않게 하는 용도다 — adbd가 멈추면 init이 adbd가 띄운
   프로세스(우리 서버)를 cgroup째 SIGKILL하고, shell 권한으로는 이 cgroup을 벗어날 수 없다(S26U 실측).
2. 앱 **시작** → `서버 기동 확인`. (최초 1회는 "무선 디버깅 페어링"으로 알림에 코드 입력.)
3. Wi-Fi를 끄고 핫스팟을 켠다(순서 무관). 서버는 그대로 남고, 핫스팟 클라이언트(노트북/차)가 `http://100.99.9.9:3333/`을 연다.
4. 재부팅했을 때만 1~2를 다시. 서버 로그는 실행마다 `/data/local/tmp/carcast/server-<epoch>.log`(앱이 `/api/log`로 보여줌).

PC 폴백:

```powershell
adb shell 'CLASSPATH=$(pm path com.carcast | cut -d: -f2) app_process / com.carcast.server.Server <빌드 sha> port=3333'
```

## 빌드

```bash
npm ci                      # 웹 툴체인 (esbuild, Playwright, ws)
./gradlew :app:assembleDebug   # 웹 클라이언트를 빌드해 assets/web에 넣고 APK 생성
# → app/build/outputs/apk/debug/app-debug.apk
```

GitHub Actions(`android.yml`)가 푸시마다 같은 APK를 아티팩트 `carcast-debug-apk`로 올린다.

## 테스트

```bash
./gradlew :core:test :mux:test            # 서버 코어·fMP4 먹서 단위 테스트
npm run build --workspace web             # 웹 클라이언트 번들
cd tests/e2e && npm run install-chrome    # Chrome for Testing 148 (테슬라 2026.26과 동일 엔진, H.264 포함)
CHROME_PATH=$(find .cache -name chrome -type f | head -1) npx playwright test
```

Playwright는 가짜 폰(`tools/fake-phone`)을 자동으로 띄우고 테슬라 브라우저 프로필(UA, 1920x1200, DPR 1/1.5,
사설 IP DNS 차단, http 오리진)로 진단 페이지·디코드 fps·지연·터치 왕복·재연결을 검사한다.
실기기에 붙이려면 `BASE_URL=http://100.99.9.9:3333 npx playwright test` (노트북을 폰 핫스팟에 연결).

폰에서 도는 것과 같은 서버 코드를 PC에서 띄워 검사할 수도 있다(APK의 assets를 그대로 읽는다):
```bash
./gradlew :app:assembleDebug :core:installDist
core/build/install/core/bin/core dev port=3399 apk=app/build/outputs/apk/debug/app-debug.apk   # stdin EOF로 종료
BASE_URL=http://127.0.0.1:3399 CHROME_PATH=... npx playwright test
```

테스트 클립 재생성: `./gradlew :mux:installDist && mux/build/install/mux/bin/mux tools/clips/test-720p30.h264 tools/clips/assets/clips/test-720p30.cmp4 30`

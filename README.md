# CarCast

안드로이드 폰의 가상 디스플레이를 테슬라 차량 브라우저로 스트리밍하는 프로젝트. 산출물은 APK 하나.

- 설계 제안: [docs/implementation-proposal.md](docs/implementation-proposal.md)
- 개발 계획(마일스톤 M0~M9): [docs/dev-plan.md](docs/dev-plan.md)
- **핫스팟 전용으로 가는 길(Wi-Fi 요구 제거 계획): [docs/hotspot-only.md](docs/hotspot-only.md)**
- **남들은 어떻게 하나(Tesor·TeslaMirror·TeslaDisplay·Castla 조사): [docs/prior-art.md](docs/prior-art.md)**
- **테스트 가이드(어디서 무엇을): [docs/testing-guide.md](docs/testing-guide.md)**
- **가상 폰(폰 없이 폰 쪽 코드 돌려보기): [tools/virtual-phone/README.md](tools/virtual-phone/README.md)**
- **작업 지침(이런 요청이 오면 어디서 어떻게): [docs/agent-runbook.md](docs/agent-runbook.md)**
- **검증 기록(무엇을 어떤 테스트로 확인했나, 가정별 상태): [docs/verification-log.md](docs/verification-log.md)**
- 실차/실기기 원본 표: [docs/car-tests/](docs/car-tests/)

## 현재 상태 (2026-09-11: M1~M5 폰에서 검증 완료, M7 전원/화면 경로 수정 후 실기기 검증 대기, M6 오디오 미착수)

앱은 VpnService로 `100.99.9.9`를 폰에 붙인다. `http://100.99.9.9:3333`의 웹 클라이언트와 스트림은
**shell uid 프로세스**(`com.carcast.server.Server`, `app_process`로 기동)가 서빙한다. Android 14+는 VPN 주소로
오는 패킷을 앱 uid 소켓에는 전달하지 않기 때문이다(실측: docs/dev-plan.md). 서버는 scrcpy에서 가져온 방식으로
**가상 디스플레이**(1280x720)를 만들어 H.264로 인코딩해 fMP4/WebSocket으로 송출하고(`POST /api/app`으로 그 화면에 앱 실행),
VD를 못 만들면 번들된 테스트 클립으로 대체한다. 안드로이드는 앱마다 task가 하나라 폰에서 쓰던 앱을 차에서 띄우면 **옮겨지지 복사되지 않으므로**,
그 옮김이 곧 "폰에서 보던 것을 그대로 차로 가져오기"라 `/api/app`의 기본은 옮기는 것이고(`restart=never`, 2026-09-17까지는 `auto`로 강제 종료 후 새로 띄웠다),
새로 띄우기는 차 화면에서 칸을 길게 누르거나 상태 패널의 "새로 열기"로 따로 부른다(`restart=always`). 폰이 앱을 도로 가져가면
`/api/status`의 `appOnPhone`과 차 화면 상태줄에 표시하고, "차로 가져오기" 한 번이면 그 상태 그대로 돌아온다(docs/testing-guide.md "M4-b"). 브라우저 터치·키·텍스트는 그 VD에 주입되고(`/ws/control`),
📵 버튼은 폰 화면만 끈다(`/api/screen`). 폰이 잠들거나 화면이 꺼지면 안드로이드가 가상 디스플레이까지 유휴로 보고
덮어 버리므로(상류에도 열려 있는 문제: [docs/prior-art.md](docs/prior-art.md)), 서버는 차가 보는 동안
가상 디스플레이에 주기적으로 사용자 활동을 알리고(`keep_active`) 잠들면 깨워서 📵 상태로 되돌린다(`sleep_recovery`).
앱의 **"일괄 켜기 / 일괄 끄기"** 버튼과 **바탕화면 위젯(스위치)** 이 서버와 VPN 주소를 한 번에 올리고 내린다.
끄기는 **서버 → 세션** 순이다(킬 스위치가 그 서버로 보내는 HTTP 요청이라, 세션을 먼저 내리면 끌 방법이 사라진다).
**핫스팟은 사용자가 직접 켠다** — 폰이 uid 2000 에게 테더링 변경을 주지 않는다(S26U·에뮬레이터 모두
`NO_CHANGE_TETHERING_PERMISSION`, 2026-09-13 실측: docs/verification-log.md 열린 질문 0). 앱 화면이 그 상태를
**보여 주기만** 한다(위젯에는 싣지 않는다 — 스위치가 다루지 않는 것을 스위치 밑에 적을 이유가 없다):
`GET /api/hotspot` 과 `/api/status.hotspot`, 서버가 없을 때는 앱이 직접 인터페이스를 본다.
`known=false` 는 "꺼짐"이 아니라 **"폰이 답해 주지 않았다"** 이다.
차에서 `/diag`를 열면 브라우저 환경·API
지원·WS 성공률·디코드 fps·사설 주소 차단 여부를 측정해 폰 서버에 저장한다(`/api/reports`, 앱의 공유 버튼). M3부터 앱이 폰 자신의 무선 디버깅에 페어링(Kadb, 알림에 코드 입력)해 이 서버를 **분리 실행**한다. 무선 디버깅은
Wi-Fi 연결 중에만 켜지므로 집 Wi-Fi에서 띄우고, 서버는 재부팅 전까지(차에서도) 유지된다. 끄기는 앱의 "서버 종료".

**폰에서 쓰는 순서:**
1. 집 Wi-Fi 연결, 개발자 옵션에서 **무선 디버깅**과 **USB 디버깅** 토글을 켠다.
2. 앱 **시작** → `서버 기동 확인`. (최초 1회는 "무선 디버깅 페어링"으로 알림에 코드 입력.)
   여기서 앱의 **"TCP 모드"** 버튼을 켜면 adbd를 TCP 모드로 전환한다(랜덤 고포트, `adb tcpip`와 같은 요청).
   **실측(2026-09-05, S26U/One UI 8): 전환 성공, 그리고 무선 디버깅이 꺼진 상태에서 그 포트로 서버 재기동까지 확인**(docs/verification-log.md §3.8).
   이후로는 Wi-Fi도 무선 디버깅 토글도 필요 없고, 차 안에서 서버가 죽어도 앱이 다시 띄운다.
3. Wi-Fi를 끄고 핫스팟을 켠다(순서 무관). 핫스팟 클라이언트(노트북/차)가 `http://100.99.9.9:3333/`을 연다.
4. **재부팅하면** TCP 모드가 풀릴 수 있다. 앱이 `persist.adb.tcp.port` 설정을 시도하므로 폰이 받아들였다면 그대로 이어지고,
   아니면 Wi-Fi에서 **시작**을 한 번 누르면 다시 켜진다(로그에 어느 쪽인지 남는다). 배경과 계획: [docs/hotspot-only.md](docs/hotspot-only.md).
   서버 로그는 실행마다 `/data/local/tmp/carcast/server-<epoch>.log`(앱이 `/api/log`로 보여줌).

**TCP 모드가 켜진 뒤 매일 쓰는 순서는 둘이다:** 핫스팟을 켜고(설정이나 빠른 설정 타일에서 — 앱은 못 켠다),
홈 화면의 **CarCast 스위치**를 켠다. 내릴 때도 그 스위치 하나면 서버와 VPN 이 함께 내려간다.

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

폰이 없을 때는 **가상 폰**(에뮬레이터)에 같은 서버를 띄워 같은 테스트를 돌린다. 가상 디스플레이·앱 실행·앱 충돌·
터치 주입·킬 스위치가 여기서 걸린다 (VPN 주소 배달·핫스팟·무선 디버깅 페어링은 그대로 실기기 몫):

```bash
tools/virtual-phone/vphone.sh sdk    # 최초 1회
./gradlew :app:assembleDebug && tools/virtual-phone/vphone.sh up
npm run device                       # 기기 검사
tools/virtual-phone/vphone.sh down
```

GitHub Actions(`emulator.yml`)가 푸시마다 같은 순서를 돈다. 자세히: [tools/virtual-phone/README.md](tools/virtual-phone/README.md)

# 테스트 가이드: 어디서, 무엇을, 언제

세 곳에서 테스트한다. 위쪽일수록 빠르고 싸다. **아래 단계는 위 단계가 통과한 뒤, 그리고 정말 필요할 때만** 내려간다.

| 장소 | 장비 | 걸리는 시간 | 여기서 잡는 것 |
|---|---|---|---|
| **A. 개발 PC / CI** | PC 하나 (또는 GitHub Actions) | 초 ~ 2분 | 웹 클라이언트 전부, fMP4 먹서, 프로토콜, 재연결 로직 |
| **B. 폰 + 노트북** | S26U + 노트북(폰 핫스팟 접속) | 10~30분 | tun 주소 배달, 핫스팟 속도, 삼성 전용 동작(VD·오디오·화면 OFF·키보드) |
| **C. 실차** | Model Y L | 5분 | 테슬라 브라우저 정책(사설 IP 차단), 진짜 디코드 성능, WS 실패율 |

원칙: **C에서 처음 발견되는 버그가 없어야 한다.** C는 확인만 하는 자리다.

---

지금까지 실제로 돌린 결과와 가정별 상태는 [verification-log.md](verification-log.md)에 있다. 이 문서는 절차만.

## 1. 테스트 사다리 (가벼운 것부터)

```
① 타입체크          3초    npm run typecheck
② 먹서 단위 테스트   5초    ./gradlew :mux:test
③ e2e 빠른 세트     40초   npm run e2e:quick        ← 웹/프로토콜 고칠 때 기본
④ e2e 전체         2분    npm run e2e              ← 커밋 전
⑤ APK 빌드         2~5분  ./gradlew :app:assembleDebug   ← 폰에 설치할 때만
⑥ 폰 검증          10분+  APK 설치 → 노트북에서 curl / Playwright
⑦ 실차 검증        5분    /diag 체크리스트
```

- ①~④는 **폰이 없어도** 된다. 코드 대부분(웹, 먹서, 서버 프로토콜)은 여기서 끝난다.
- ⑤는 실제로 폰에 올릴 때만. 웹만 고쳤다면 APK를 다시 빌드할 필요 없이 ③④로 충분하다.
- CI도 같은 원칙: `web.yml`은 웹/테스트 파일이 바뀔 때, `android.yml`은 안드로이드/Gradle 파일이 바뀔 때만 돈다. 웹만 고쳤는데 APK가 필요하면 Actions 탭에서 `android` 워크플로를 **수동 실행(Run workflow)** 한다.

### 최초 1회 준비 (개발 PC)

```bash
npm ci                                   # esbuild, Playwright, ws
cd tests/e2e && npm run install-chrome   # Chrome for Testing 148 (테슬라와 같은 엔진, H.264 포함)
```

Playwright에 딸려오는 Chromium은 **H.264를 못 푼다.** 반드시 Chrome for Testing을 써야 영상 테스트가 의미 있다.
`CHROME_PATH`를 매번 치기 싫으면 셸 프로필에 넣어둔다:

```bash
export CHROME_PATH=$(find ~/경로/Tesla/tests/e2e/.cache -name chrome -type f | head -1)
```

---

## 2. 기능별로 어디서 어떻게 테스트하나

| 기능 | A. PC/CI | B. 폰+노트북 | C. 실차 |
|---|---|---|---|
| **웹 클라이언트 (화면, 버튼, 진단 페이지)** | `npm run e2e:quick`. 가짜 폰이 클립을 쏘고 Chrome 148이 받는다 | `BASE_URL=http://100.99.9.9:3333 npx playwright test` 로 같은 테스트를 폰에 대고 실행 | `/diag` 열고 `저장됨` 확인 (결과는 폰에 저장) |
| **영상 디코드 / 지연** | `stream.spec.ts`: fps ≥ 25, 지연 < 300ms, 10초 무정지 | 노트북 Chrome에서 `http://100.99.9.9:3333/` 눈으로 확인 + 위 Playwright | `/diag` 의 fps·lag 수치 |
| **터치 / 키 입력** | `input.spec.ts`: 클릭 → 가짜 폰이 받은 정규화 좌표 검증 | (M5 이후) 폰 화면에서 실제 앱이 반응하는지 | 차 화면 터치로 앱 조작 |
| **재연결 (WS 끊김, 거부)** | `reconnect.spec.ts`: 고장 주입(거부 34%, 지연 150ms, 5초마다 절단) | 폰 화면 끄기/핫스팟 재접속 후 복구 확인 | 후진 기어 전환 후 복구 확인 |
| **fMP4 먹서** | `./gradlew :mux:test` + (선택) ffmpeg로 디코드 | 해당 없음 | 해당 없음 |
| **VpnService 가짜 IP (가정 1)** | 불가 | **핵심.** 노트북을 폰 핫스팟에 붙이고 `curl http://100.99.9.9:3333/api/status` | 차에서 `/diag` 열림 여부 |
| **사설 IP 차단 (가정 2)** | Playwright가 192.168.*/10.* 접속을 DNS 실패로 흉내; `/diag`가 서버의 주소 목록을 자동 프로브 | 불가 | `/diag`의 "사설 주소 차단 확인"이 핫스팟 주소를 `차단됨`으로 보고하는지 |
| **핫스팟 대역폭** | 불가 | 5GHz 핫스팟에서 720p30이 끊김 없이 10분 | 동일 |
| **ADB 페어링·shell 서버 (M3)** | 프로토콜 단위 테스트만 | **핵심.** 앱에서 페어링 → `uid=2000` 표시 | 해당 없음 |
| **가상 디스플레이·앱 실행 (M4)** | CI 에뮬레이터(선택) | **핵심.** 삼성 One UI 동작은 여기서만 | 차에서 앱 조작 |
| **오디오 (M6)** | 오디오 SourceBuffer 진행 여부 | 폰 스피커 무음/출력 선택 확인 | 차 스피커로 재생, 첫 터치 후 소리 |
| **화면 OFF·발열·배터리 (M7)** | 불가 | 화면 끄고 30분 연속 스트리밍 | 동일 |

---

## 3. 장소별 상세

### A. 개발 PC (또는 CI)

무엇을 고쳤든 여기서 먼저 돌린다.

```bash
# 웹 코드를 고쳤을 때
npm run typecheck && npm run e2e:quick

# 먹서/프로토콜을 고쳤을 때
./gradlew :mux:test

# 커밋 전
npm run e2e
```

`npm run e2e:quick`은 DPR 1 프로필로 diag·stream·input 세 파일만 돈다(약 40초).
`npm run e2e`는 DPR 1과 1.5 두 프로필로 재연결까지 전부 돈다(약 2분).

가짜 폰을 직접 띄워 눈으로 보고 싶으면:

```bash
npm run build                   # 웹 번들
npm run fake-phone              # http://localhost:3333/
```

고장 주입 옵션: `node tools/fake-phone/server.mjs --ws-reject 0.5 --delay-ms 200 --ws-drop-every 5`

실패했을 때 보는 곳: `tests/e2e/test-results/` 안의 trace.zip을 `npx playwright show-trace <파일>` 로 열면 화면 녹화와 콘솔이 나온다.

### B. 폰(S26U) + 노트북

APK를 새로 올릴 필요가 있을 때만 온다. 서버는 **shell uid**로 돌아야 차(핫스팟 클라이언트)가 100.99.9.9에
닿는다(이유: dev-plan "검증된 사실"). M3부터는 앱이 폰 자신의 무선 디버깅에 접속해 서버를 **분리(daemon) 실행**한다.

> **제약: 무선 디버깅은 폰이 Wi-Fi에 (클라이언트로) 연결된 동안만 켜진다.** 핫스팟은 해당 없고, Wi-Fi가 끊기면 시스템이
> 무선 디버깅을 자동으로 끈다. 그래서 페어링과 서버 기동은 **집 Wi-Fi에서** 하고, 분리 실행된 서버는 Wi-Fi를 끄고
> 핫스팟을 켜도(차에서도) 재부팅 전까지 살아 있다. 끄는 방법은 앱의 **"서버 종료"**(loopback 전용 `POST /api/stop`).
> 앱의 "중지"는 세션(VPN)만 내리고 서버는 그대로 둔다.

순서:

1. APK 받기. Actions 탭 → `android` → 아티팩트 `carcast-debug-apk`. 또는 로컬 `./gradlew :app:assembleDebug`.
2. 폰에 설치. **집 Wi-Fi에 연결**한 뒤 설정 → 개발자 옵션 → **무선 디버깅** 켜기 (Wi-Fi가 아니면 토글이 비활성).
3. **최초 1회 페어링:** 앱에서 **"무선 디버깅 페어링"** 버튼 → 개발자 옵션 화면이 열린다 → 무선 디버깅 →
   **"페어링 코드로 기기 페어링"** → 알림창을 내려 CarCast 알림의 **"코드 입력"**에 6자리 코드를 입력.
   앱 로그에 `페어링 포트 발견` → `페어링 성공`이 찍히고, 무선 디버깅 화면의 "페어링된 기기"에 `CarCast`가 생긴다.
   mDNS로 포트를 못 찾으면(로그 `페어링 포트를 찾지 못함`) **"포트 수동 입력…"**에서 대화상자의 포트와 코드를 넣는다.
4. 앱에서 **시작** → VPN 동의 → `tun: UP`. 화면의 `adb:` 줄이 `FINDING_PORT → CONNECTING → STARTING → SERVER_UP`으로
   바뀌고 "서버:" 줄이 `응답 중 shell uid=2000`이 된다. `NEEDS_PAIRING`이면 3번을 다시. `NO_WIFI`면 Wi-Fi에 연결.
   접속 포트를 못 찾으면 무선 디버깅 화면의 "IP 주소 및 포트"의 포트를 **"포트 수동 입력…"** 접속 포트에 넣는다(재부팅·토글마다 바뀐다).
   **PC 폴백:** 앱이 못 띄우면 화면에 보이는 adb 명령을 PC에서 실행한다(아래 "PC에서 띄울 때").
5. **Wi-Fi를 끄고** 폰 핫스팟을 켠다(5GHz 권장). "서버:"가 `응답 중`으로 남아야 한다 — 차에서의 조건과 같다. 노트북을 핫스팟에 붙인다.
6. 노트북에서:
   ```bash
   curl http://100.99.9.9:3333/api/status          # {"running":true,"process":"shell","uid":2000,...} 나오면 가정 1 통과
   BASE_URL=http://100.99.9.9:3333 npx playwright test   # PC용 테스트를 그대로 폰에 대고 실행
   ```
   (`BASE_URL`을 주면 가짜 폰을 띄우지 않고, 가짜 폰이 필요한 터치·재연결 테스트는 자동 skip)
7. 노트북 Chrome에서 `http://100.99.9.9:3333/` 열어 눈으로 확인. 이때 노트북도 Chrome 148이면 차와 거의 같은 조건이다.
8. **킬 스위치 확인:** 앱에서 **"서버 종료"** → 몇 초 안에 "서버:"가 `응답 없음`, 노트북 curl이 실패해야 한다.
   (앱의 "중지"는 서버를 남긴다. 다시 띄우려면 Wi-Fi에서 "시작".)
9. 결과를 `docs/car-tests/` 에 기록.

**M4 확인 (진짜 화면):** 서버가 뜨면 기본으로 가상 디스플레이를 만든다. 노트북 Chrome에서 `http://100.99.9.9:3333/`을 열고
하단 바의 **▶** 버튼에 `com.google.android.youtube`를 넣으면 폰의 가상 화면에 유튜브가 뜨고 노트북에 보여야 한다
(또는 `curl -X POST "http://100.99.9.9:3333/api/app?name=com.google.android.youtube"`). `/api/status`의 `source`가 `display`면 라이브,
`clip`이면 VD 생성에 실패해 클립으로 대체된 것 — 앱 로그의 `라이브 소스 실패 …` 줄과 `/api/log`(또는 `/data/local/tmp/carcast/server.log`)를 보고 알려 준다.
**M5 확인 (터치):** 노트북 브라우저에서 유튜브 영상을 클릭·스크롤하면 폰의 가상 화면 앱이 반응해야 한다. 안 되면 `/api/status`의
`injectFailed`가 늘어나는지 보고, `/api/log`에 `INJECT_EVENTS permission`이 있으면 개발자 옵션의 **"USB 디버깅(보안 설정)"** 을 켜고 재부팅한다(삼성).
키보드 ⌨ 버튼으로 영문·한글 입력(한글은 클립보드 붙여넣기 방식).
**M7 확인 (폰 화면 끄기):** 폰을 충전기에 꽂고 하단 바 📵 버튼 → 폰 화면만 꺼지고 노트북 영상·터치는 계속되어야 한다. 다시 누르면 켜진다.
`/api/status.screenOn`으로도 확인. 전원 버튼은 누르지 않는다(전체 정지).

**M3 폰 검증 체크리스트** (verification-log §3.4에 결과 기록):
- 페어링 성공 / mDNS 페어링 포트 발견 여부 / 수동 포트로도 되는지
- 시작 → `SERVER_UP` 까지 걸린 시간, 접속 포트 mDNS 발견 여부
- **Wi-Fi 끄고 핫스팟 켠 뒤 서버 유지** (무선 디버깅이 꺼져도 분리 실행된 서버가 남는지) — 이 설계의 성립 조건
- 화면 OFF 30분·하룻밤 뒤 서버 유지 (도즈), 앱 강제 종료 후 서버 유지
- "서버 종료" → 종료(킬 스위치); 폰 재부팅 후 Wi-Fi에서 "시작" 한 번으로 복구

**PC에서 띄울 때 (앱의 adb 링크가 안 될 때의 폴백):** 서버를 adb 창과 분리해 띄워 두고 USB를 뽑는다. 종료는 `pkill`.
`<빌드 sha>`는 앱 화면의 `(빌드 xxxxxxx)` 값으로 **꺾쇠 없이** 바꿔 넣는다 (예: `... com.carcast.server.Server 4eef57e port=3333 ...`).
```powershell
adb shell 'CLASSPATH=$(pm path com.carcast | cut -d: -f2) setsid nohup app_process / com.carcast.server.Server <빌드 sha> port=3333 daemon=true >/dev/null 2>&1 &'
adb shell 'pkill -f com.carcast.server.Server'      # 끝낼 때
```
앱 화면의 "서버:" 줄이 `응답 중 shell uid=2000`이면 살아 있는 것이다. 폰을 재부팅하면 사라진다.

**안 될 때:** 서버 로그(adb 창)에 `accept 10.136.x.x:port → 100.99.9.9:3333`이 찍히는지 본다. 안 찍히면 SYN이
안 온 것이고, 아래로 폰 쪽을 본다. 앱은 `ip`/`/proc/sys` 읽기가 SELinux로 막혀 있어 adb에서만 볼 수 있다.
```bash
adb shell ip rule                      # VPN uid 범위, prohibit/unreachable 규칙
adb shell ip route show table all      # 핫스팟 서브넷이 어느 테이블에 있는지
adb shell ss -tan | grep 3333          # 접속 시도 중 SYN-RECV가 보이면 SYN은 왔고 응답이 사라진 것
adb shell "grep ^TcpExt /proc/net/netstat"   # 전/후 비교: ListenDrops가 늘면 리스너에서 버린 것
adb shell "echo hi | nc -l -p 3334"    # shell uid로 직접 listen: 이게 되고 3333이 안 되면 서버 쪽 문제
```
"앱 프로세스에서 서버 실행" 체크박스는 핫스팟 주소(10.x)로만 접속되는 실험용이며 100.99.9.9로는 절대 안 된다.

**M0 (코드 없이, 가장 먼저 한 번):** 노트북에 stock scrcpy 4.1을 깔고 `docs/car-tests/s26u-one-ui-8.md` 의 표를 채운다. 이게 M4 설정값을 결정한다.

### C. 실차

주차 상태에서 5분. 새 펌웨어가 올 때마다 반복. 기록 틀: [car-tests/model-y-2026.26.md](car-tests/model-y-2026.26.md).

**집에서 먼저 (차 가기 전날):**
- Wi-Fi에서 앱 **시작**(또는 PC 명령)으로 서버를 띄운 뒤 **Wi-Fi를 끄고 핫스팟을 켠 채** 앱 화면이 `응답 중 shell uid=2000`을 유지하는지 본다.
  화면을 끄고 10분 뒤에도 유지되면 차에 가도 된다. 죽으면 차에서는 아무것도 안 된다. 차에서는 서버를 다시 띄울 수 없다(무선 디버깅은 Wi-Fi 전용).
- 노트북을 핫스팟에 붙여 `http://100.99.9.9:3333/diag`를 한 번 열어 `저장됨 #1`이 뜨는지 본다 (저장 경로 확인).

**차에서:**
1. 폰 앱이 실행 중(`tun: UP`, 서버 응답 중)인지 보고, 차를 폰 핫스팟에 연결.
2. 차 브라우저에서 `http://100.99.9.9:3333/diag` → 열리면 가정 2 통과.
3. 상단에 `저장됨 #n`이 뜰 때까지 기다린다(약 40초: WS 20회 + 영상 5초 + 사설 주소 확인).
   페이지가 UA·viewport·DPR·API 표·WS 성공률·fps·lag·**사설 주소 차단 여부**를 모두 측정해 폰에 POST한다.
   `저장 실패`가 뜨면 그때만 화면을 사진으로 찍는다.
4. `http://100.99.9.9:3333/` 에서 첫 터치 후 영상 재생, 전체화면 버튼, 후진 기어 전환 후 복구.
5. (선택) 사설 주소 대조군은 3번이 자동으로 한다. 손으로 보고 싶으면 앱 화면 "인터페이스"의 `swlan0` 주소로
   `http://<swlan0 주소>:3333/diag`를 열어 본다 — **막혀야 정상**(2026-09-04 실측 핫스팟 주소는 `10.136.114.168`; 펌웨어 정책 변화 추적용).

**집에 와서:**
```bash
curl http://100.99.9.9:3333/api/reports | python -m json.tool     # 노트북을 핫스팟에 붙여서. 최신이 앞
```
또는 앱의 **"차에서 보낸 진단 결과 공유"** 버튼으로 JSON을 메모/채팅에 보낸다. 보고서는 shell 서버가
`/data/local/tmp/carcast/report-*.json`에 남기므로 서버를 다시 띄워도 남아 있다(폰 초기화 전까지).
수치를 `car-tests/<펌웨어>.md`와 [verification-log.md](verification-log.md) §1(가정 2·5·6)에 옮긴다.

차에서 문제가 나오면 **차에서 디버깅하지 않는다.** 저장된 report의 UA, DPR, viewport를 PC의 Playwright 프로필에 반영해서 A에서 재현한다.

## 4. 언제 무엇을 돌리나 (요약)

| 상황 | 돌릴 것 |
|---|---|
| 웹 파일 저장할 때마다 | `npm run typecheck` |
| 웹 기능 하나 완성 | `npm run e2e:quick` |
| 먹서/프로토콜 수정 | `./gradlew :mux:test` |
| 커밋/푸시 | `npm run e2e` (CI가 `web.yml`로 다시 돈다) |
| 안드로이드 코드 수정 | 푸시하면 CI가 APK 빌드. 로컬 빌드는 폰에 올릴 때만 |
| 폰에 올릴 때 | APK 아티팩트 다운로드 → B 절차 |
| 펌웨어 업데이트 / 큰 마일스톤 | C 절차 |

# 테스트 가이드: 어디서, 무엇을, 언제

세 곳에서 테스트한다. 위쪽일수록 빠르고 싸다. **아래 단계는 위 단계가 통과한 뒤, 그리고 정말 필요할 때만** 내려간다.

| 장소 | 장비 | 걸리는 시간 | 여기서 잡는 것 |
|---|---|---|---|
| **A. 개발 PC / CI** | PC 하나 (또는 GitHub Actions) | 초 ~ 2분 | 웹 클라이언트 전부, fMP4 먹서, 프로토콜, 재연결 로직 |
| **B. 폰 + 노트북** | S26U + 노트북(폰 핫스팟 접속) | 10~30분 | tun 주소 배달, 핫스팟 속도, 삼성 전용 동작(VD·오디오·화면 OFF·키보드) |
| **C. 실차** | Model Y L | 5분 | 테슬라 브라우저 정책(사설 IP 차단), 진짜 디코드 성능, WS 실패율 |

원칙: **C에서 처음 발견되는 버그가 없어야 한다.** C는 확인만 하는 자리다.

---

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
| **웹 클라이언트 (화면, 버튼, 진단 페이지)** | `npm run e2e:quick`. 가짜 폰이 클립을 쏘고 Chrome 148이 받는다 | `BASE_URL=http://100.99.9.9:3333 npx playwright test` 로 같은 테스트를 폰에 대고 실행 | `/diag` 열어 사진 촬영 |
| **영상 디코드 / 지연** | `stream.spec.ts`: fps ≥ 25, 지연 < 300ms, 10초 무정지 | 노트북 Chrome에서 `http://100.99.9.9:3333/` 눈으로 확인 + 위 Playwright | `/diag` 의 fps·lag 수치 |
| **터치 / 키 입력** | `input.spec.ts`: 클릭 → 가짜 폰이 받은 정규화 좌표 검증 | (M5 이후) 폰 화면에서 실제 앱이 반응하는지 | 차 화면 터치로 앱 조작 |
| **재연결 (WS 끊김, 거부)** | `reconnect.spec.ts`: 고장 주입(거부 34%, 지연 150ms, 5초마다 절단) | 폰 화면 끄기/핫스팟 재접속 후 복구 확인 | 후진 기어 전환 후 복구 확인 |
| **fMP4 먹서** | `./gradlew :mux:test` + (선택) ffmpeg로 디코드 | 해당 없음 | 해당 없음 |
| **VpnService 가짜 IP (가정 1)** | 불가 | **핵심.** 노트북을 폰 핫스팟에 붙이고 `curl http://100.99.9.9:3333/api/status` | 차에서 `/diag` 열림 여부 |
| **사설 IP 차단 (가정 2)** | Playwright가 192.168.* 접속을 DNS 실패로 흉내 | 불가 | `http://192.168.x.x:3333/diag` 가 막히는지 |
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
닿는다(이유: dev-plan "검증된 사실"). M3 전까지는 그 서버를 PC의 adb로 띄운다. 순서:

1. APK 받기. Actions 탭 → `android` → 아티팩트 `carcast-debug-apk`. 또는 로컬 `./gradlew :app:assembleDebug`.
2. 폰에 설치, 앱에서 **시작** → VPN 동의 → `tun: UP`. 화면에 `(빌드 xxxxxxx)`와 아래 adb 명령이 보인다.
3. 폰 USB 연결(USB 디버깅 켜기) 후 PC에서 서버 기동. 창을 닫거나 Ctrl-C 하면 서버도 죽는다(킬 스위치):
   ```powershell
   adb shell 'CLASSPATH=$(pm path com.carcast | cut -d: -f2) app_process / com.carcast.server.Server <빌드 sha> port=3333'
   # → carcast-server uid=2000 build=<sha> ... / carcast-server ready
   ```
   앱 화면의 "서버:" 줄이 `응답 중 shell uid=2000`으로 바뀐다.
4. 폰 핫스팟 켜고(5GHz 권장) 노트북을 붙인다.
5. 노트북에서:
   ```bash
   curl http://100.99.9.9:3333/api/status          # {"running":true,"process":"shell","uid":2000,...} 나오면 가정 1 통과
   BASE_URL=http://100.99.9.9:3333 npx playwright test   # PC용 테스트를 그대로 폰에 대고 실행
   ```
   (`BASE_URL`을 주면 가짜 폰을 띄우지 않고, 가짜 폰이 필요한 터치·재연결 테스트는 자동 skip)
6. 노트북 Chrome에서 `http://100.99.9.9:3333/` 열어 눈으로 확인. 이때 노트북도 Chrome 148이면 차와 거의 같은 조건이다.
7. 결과를 `docs/car-tests/` 에 기록.

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

주차 상태에서 5분. 새 펌웨어가 올 때마다 반복.

1. 폰 앱 시작, 차를 폰 핫스팟에 연결.
2. 차 브라우저에서 `http://100.99.9.9:3333/diag` → 열리면 가정 2 통과.
3. 화면의 UA / viewport / DPR / API 표 사진.
4. WS 20회 성공률, 디코드 fps, lag 확인.
5. `http://192.168.43.1:3333/diag` 도 열어 본다 (막혀야 정상. 정책 변화 추적용).
6. `http://100.99.9.9:3333/` 에서 첫 터치 후 영상 재생, 전체화면 버튼.
7. 사진과 수치를 `docs/car-tests/<펌웨어>.md` 에 기록.

차에서 문제가 나오면 **차에서 디버깅하지 않는다.** `/diag` 값을 PC의 Playwright 프로필(UA, DPR, viewport)에 반영해서 A에서 재현한다.

---

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

# 작업 지침: 이런 요청이 오면 어디서 어떻게

"앱 전환이 이상하다", "전원 버튼 누르면 차가 얼어붙는다", "폰과 웹 사이가 매끄럽지 않다" —
이런 요청을 받았을 때 **실기기·실차 없이** 어디까지 스스로 확인하고 고칠 수 있는지, 그 절차만 적는다.
무엇을 어디서 테스트하는지의 전체 지도는 [testing-guide.md](testing-guide.md), 지금까지 확인된 사실은
[verification-log.md](verification-log.md)에 있다.

## 0. 전제: 실차는 마지막 확인처다

이 프로젝트의 원칙은 **"C(실차)에서 처음 발견되는 버그가 없어야 한다"** 이다. 그래서 작업 순서는 언제나
위에서 아래로 내려간다. 아래로 내려갈수록 느리고 비싸며, 아래 단계에서만 잡히는 것은 따로 정해져 있다.

| | 자리 | 무엇을 |
|---|---|---|
| A | 개발 PC / CI | 웹 클라이언트, 먹서, 프로토콜, 재연결 — 가짜 폰(`tools/fake-phone`) 상대 |
| **A+** | **가상 폰(에뮬레이터)** | **폰 쪽 코드 전부: 가상 디스플레이·앱 실행·앱 충돌·입력 주입·화면 전원·생애주기** |
| B | 실기기 + 노트북 | VPN 주소 배달, 핫스팟, 무선 디버깅 페어링, One UI 전용 동작 |
| C | 실차 | 테슬라 브라우저 정책, 차 Wi-Fi, 후진 기어·탭 폐기 |

**A+ 가 이 문서의 핵심이다.** 앱 전환과 전원 버튼은 예전에는 B/C 에서만 볼 수 있었지만, 지금은 여기서 본다.

## 1. 환경 세우기

```bash
npm ci
cd tests/e2e && npm run install-chrome && cd ../..   # 차와 같은 엔진(Chrome for Testing 148)
tools/virtual-phone/vphone.sh sdk                    # 최초 1회, 약 2GB
./gradlew :app:assembleDebug
tools/virtual-phone/vphone.sh up                     # → http://127.0.0.1:3333

export BASE_URL=http://127.0.0.1:3333
export CHROME_PATH=$(find tests/e2e/.cache -name chrome -type f | head -1)
```

리눅스 + `/dev/kvm` 이 필요하다. 없으면(맥/윈도, KVM 없는 컨테이너) **GitHub Actions 의 `emulator` 워크플로가
같은 순서를 돈다** — 푸시하고 로그를 읽는 것이 유일한 길이다. 이 저장소의 원격 세션이 그 경우다.

## 2. 무엇부터 돌리나

```bash
npm run device        # 기기 검사: source=display, 앱 실행, M4-b 충돌, 입력 주입, 화면 전원, 킬 스위치
npm run lifecycle     # 폰↔웹 생애주기 시나리오 (앱 전환 / 웹 / 폰 / 전원 버튼 × 📵)
EXPLORE_STEPS=40 npm run explore   # 무작위 순서로 스스로 돌아다니기
npm run e2e           # 가짜 폰 상대 웹 회귀 (BASE_URL 없이)
```

보고서는 `out/lifecycle/report.md`·`explore.md`. 표의 열이 곧 "쾌적한가"의 정의다:
**차 화면 복구 시간 / 재접속 횟수 / 앱 위치 / 폰 화면 / fps / 어긋남**.

## 3. 이 두 가지를 고칠 때 보는 곳

### 앱 전환 (폰과 차가 같은 앱을 두고 다툴 때)

안드로이드는 앱마다 task 를 하나만 둔다. `am start --display N` 은 **복사가 아니라 이동**이다. 그래서
폰에서 쓰던 앱을 차에서 띄우면 폰에서 사라지고, 폰 런처에서 다시 누르면 차 화면이 빈다.

- 서버: `DisplayVideoSource.startApp`(restart=auto 로 강제 종료 후 새로), `TaskList`(`am stack list` 파서),
  앱 감시자(빈 화면이면 1초, 아니면 5초 간격)
- 차: `/api/status.appOnPhone` → 상태 패널 `app-on-phone` → **"차로 가져오기"** 한 번
- 검사: `tests/device/tests/03-app.test.mjs`, `tests/e2e/tests/app-conflict.spec.ts`,
  `npm run lifecycle` 의 "앱 전환" 시나리오

**빈 가상 디스플레이는 인코더가 한 장도 내지 않는다**(합성할 내용이 없다). 그래서 "앱이 없다"와
"폰이 가져갔다"는 화면상 똑같이 멈춘 그림이다. 그 둘을 말로 갈라 주는 것이 상태 패널이다.

### 전원 버튼 (폰 화면과 차 화면이 서로를 끌고 갈 때)

폰의 전원 버튼과 차의 📵 는 **같은 패널**을 움직인다. 차는 그 버튼을 가로챌 수 없으므로, 서버는
자기 장부(`forcedOff`)를 진실로 믿으면 안 된다.

- 서버: `ScreenPower` — `SurfaceControl.setDisplayPowerMode`(scrcpy 방식, `requestDisplayPower` 아님),
  1초 감시자가 `dumpsys display` 의 `mScreenState` 와 대조해 어긋나면 **기기 쪽을 믿고 장부를 버린다**
- 상태: `/api/status` 의 `screenOn` `interactive` `forcedOff` `panelState` `powerReconciled` `lastPowerEvent`
- 검사: `tests/device/tests/05-screen.test.mjs`, `npm run lifecycle` 의 "전원 버튼 × 📵" 시나리오

되돌리기만 하고 **되눌러 주지 않는다.** 사용자가 끈 화면을 서버가 도로 켜면 그때부터는 싸움이다.

## 4. 새 상황을 추가하는 법

`tests/e2e/tests/lifecycle/actions.ts` 에 동작 하나를 더하면 시나리오와 무작위 탐색 양쪽에 자동으로 들어간다.

```ts
{
  id: 'phone.airplane-mode',
  side: 'phone',
  title: '비행기 모드를 켠다',
  mayStopVideo: true,                    // 여기서 영상이 끊기는 것은 실패가 아니다
  async run() { adbShell('cmd connectivity airplane-mode enable'); await sleep(2000); },
}
```

불변 조건을 더하고 싶으면 `lifecycle/probe.ts` 의 `disagreements()` 에 한 줄 넣는다 —
그 줄이 모든 시나리오와 탐색 보고서의 "어긋남" 칸에 뜬다.

## 5. 확인 없이 끝내지 않기

- **기기마다 갈리는 것은 단언하지 않는다.** 전원·패널·인코더 속도는 에뮬레이터(AOSP)와 S26U(One UI)가 다르다.
  기록만 하고, 어디서나 성립해야 하는 것(서버 생존, 앱 위치, 되돌아올 수 있는가)만 단언한다.
- **탐색에서 나온 문제는 씨앗으로 재현한다.** 보고서 맨 위의 `EXPLORE_SEED` 를 그대로 넣으면 같은 순서가 나온다.
- **고친 뒤에는 A+ 를 다시 돌린다.** CI 라면 푸시하고 `emulator` 워크플로가 녹색이 될 때까지 따라간다.
  로그에서 볼 것: `uid=2000`, `source=display`, `injectFailed:0`, 시나리오 표의 "어긋남" 칸.
- **A+ 가 통과했다고 B 를 건너뛰지 않는다.** 순서가 바뀔 뿐이다 — 폰에 올리기 전에 여기서 먼저 깨진다.
  A+ 에서 못 보는 것의 전체 목록: [tools/virtual-phone/README.md](../tools/virtual-phone/README.md).

## 6. 막혔을 때

| 증상 | 볼 곳 |
|---|---|
| `source=clip` | 가상 디스플레이 생성 실패. `vphone.sh logs` 의 `라이브 소스 실패` |
| 에뮬레이터가 바로 죽음 | 디스크. `FATAL | Not enough space to create userdata partition` — CI 는 선설치 툴체인을 지우고 userdata 를 4GB 로 줄여 둔다 |
| `adb shell "... &"` 가 안 돌아옴 | stdin 까지 `/dev/null` 로 떼야 한다(`vphone.sh start_server`) |
| 차 화면 버튼이 안 눌림 | 스테이지 위 UI 는 `data-ui` 를 달아야 한다. 안 그러면 `setPointerCapture` 가 클릭을 삼킨다 |
| 실차에서만 나는 증상 | 차에서 디버깅하지 않는다. 💾 로 세션 리포트를 남기고, 그 `events` 를 집에서 재현한다 |

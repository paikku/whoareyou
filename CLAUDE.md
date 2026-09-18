# CarCast 작업 지침

안드로이드 폰의 가상 디스플레이를 테슬라 차량 브라우저로 스트리밍한다. 산출물은 APK 하나.
구조와 현재 상태는 [README.md](README.md).

## 먼저 읽을 것

- **[docs/agent-runbook.md](docs/agent-runbook.md)** — 어디서 무엇부터 돌리는지. 앱 전환·전원 버튼·
  터치·생애주기처럼 "폰과 차 사이"를 건드리는 요청이면 **여기부터** 본다.
- [docs/testing-guide.md](docs/testing-guide.md) — 네 자리(A / A+ / B / C)의 전체 지도
- [docs/verification-log.md](docs/verification-log.md) — 지금까지 무엇이 어떤 테스트로 확인됐나

## 실기기 없이 어디까지 되나

폰이 없어도 **가상 폰**(에뮬레이터)에서 폰 쪽 코드를 그대로 돌린다. 가상 디스플레이, 앱 실행,
앱 충돌(M4-b), 터치 주입, 화면 전원, 킬 스위치, 그리고 폰↔웹 생애주기가 전부 여기서 걸린다.

```bash
tools/virtual-phone/vphone.sh sdk    # 최초 1회 (KVM 있는 리눅스 필요)
./gradlew :app:assembleDebug && tools/virtual-phone/vphone.sh up

export BASE_URL=http://127.0.0.1:3333
export CHROME_PATH=$(find tests/e2e/.cache -name chrome -type f | head -1)
npm run device       # 기기 검사 (브라우저 없음)
npm run lifecycle    # 앱 전환 / 웹 / 폰 / 전원 버튼 × 📵 / 터치 상호작용
EXPLORE_STEPS=40 npm run explore   # 무작위 순서로 스스로 돌아다니기
```

KVM 이 없는 자리(원격 컨테이너, 맥/윈도)에서는 **푸시하고 `emulator` 워크플로 로그를 읽는 것이
유일한 길**이다. CI 자체가 막혀 있으면(러너 없이 2 초 만에 실패) 컨테이너에 SDK 를 깔아 APK 와 JVM 단위
테스트까지는 직접 만들 수 있다 — docs/agent-runbook.md §6 "컨테이너에서 APK 만들기". 탐색은 `tools/virtual-phone/explore.steps` 를 0 이 아닌 수로 바꿔 푸시하면 그 실행에서
돌고, 보고서(아티팩트 `virtual-phone-logs` 의 `out/lifecycle/explore.md`)를 읽은 뒤 0 으로 되돌린다.

## 원칙

- **실차(C)에서 처음 발견되는 버그가 없어야 한다.** C 는 확인만 하는 자리다.
- **A+ 에서 처리량(fps·지연)을 묻지 않는다.** 에뮬레이터의 소프트웨어 인코더는 정지 화면에서 0.3fps 다.
  경로와 상태를 보는 자리이고, 숫자는 A(가짜 폰)와 B(실기기)에서 얻는다.
- **기기마다 갈리는 것은 단언하지 않고 기록한다.** 전원·패널·인코더 속도는 AOSP 와 One UI 가 다르다.
- **A+ 가 통과해도 B 를 건너뛰지 않는다.** 순서가 바뀔 뿐이다.

## 웹만 고쳤을 때

APK 를 다시 빌드할 필요 없다. `npm run typecheck && npm run e2e:quick` 이면 충분하고, 커밋 전에
`npm run e2e`. 안드로이드 코드를 고쳤으면 푸시해서 CI 가 APK 를 만들게 한다.

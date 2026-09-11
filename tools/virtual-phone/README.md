# 가상 폰 (Android 에뮬레이터)

폰 없이 **폰 쪽 코드**를 돌려 보는 자리. 에뮬레이터에 APK 를 깔고, 앱이 하는 것과 똑같은 명령으로
같은 dex 를 **shell uid** 로 띄운 뒤, `adb forward` 로 노트북에서 접속한다.

```bash
tools/virtual-phone/vphone.sh sdk    # 최초 1회: cmdline-tools + emulator + 시스템 이미지 (약 2GB)
./gradlew :app:assembleDebug
tools/virtual-phone/vphone.sh up     # 부팅 → 설치 → 서버 기동 → http://127.0.0.1:3333

npm run device                                    # 기기 검사 (M4 / M4-b / M5 / M7)
cd tests/e2e && CHROME_PATH=$(find .cache -name chrome -type f | head -1) \
  BASE_URL=http://127.0.0.1:3333 npx playwright test --project=model-y-2026.26   # 차 쪽 클라이언트

tools/virtual-phone/vphone.sh logs   # 서버 로그
tools/virtual-phone/vphone.sh down   # 킬 스위치 → 에뮬레이터 종료
```

`/dev/kvm` 이 있어야 쓸 만하다(리눅스). 없으면 부팅이 몇 분에서 무한대로 늘어난다.
CI(`.github/workflows/emulator.yml`)는 공개 러너의 중첩 가상화를 쓰며, 위 순서를 `ci.sh` 가 그대로 돈다.

## 여기서 잡히는 것

| | 지금까지 | 이제 |
|---|---|---|
| 가상 디스플레이 생성 (M4) | 폰에서만 | `source=display`, `displayId>0` 를 기기 검사가 확인 |
| 앱 실행·앱 충돌 (M4-b) | 폰에서만 | `am start --display 0` 으로 "폰이 가져감"을 만들고 `restart=auto` 를 확인 |
| 터치·키 주입 (M5) | 폰에서만 | `injected` 증가 / `injectFailed` 0. 브라우저 클릭부터 끝까지도 (`tests/e2e/device-input.spec.ts`) |
| 화면 OFF (M7) | 폰에서만 | `/api/screen` 요청·상태. 실제 소등은 기기 나름 |
| 인코더 → fMP4 → 차 브라우저 | 클립으로 흉내 | 진짜 인코더 출력이 Chrome 148 에서 디코드된다 |
| 킬 스위치 | 폰에서만 | `POST /api/stop` 후 사라지는지 |

## 여기서 **안** 잡히는 것 (그대로 실기기 몫)

- **가정 1 — VpnService 로 붙인 `100.99.9.9` 가 핫스팟 클라이언트에게 배달되는가.** 여기서는 `adb forward`
  로 붙는다. 에뮬레이터에는 핫스팟에 붙은 다른 기기가 없으므로 이 경로 자체를 만들 수 없다.
- **무선 디버깅 페어링·TCP 모드 (M3).** 에뮬레이터의 adbd 는 그 경로로 오지 않는다.
- **삼성 One UI 전용 동작.** INJECT_EVENTS 정책, 화면 OFF 시 패널 동작, 도즈, cgroup/SELinux —
  에뮬레이터(AOSP)에서 되는 것이 S26U 에서 된다는 보장이 없다. 반대도 마찬가지다.
- **핫스팟 대역폭, 발열, 배터리, 차 Wi-Fi 경로.**

즉 가상 폰이 통과했다고 폰 검증을 건너뛸 수는 없다. 순서가 바뀔 뿐이다:
**폰에 올리기 전에 여기서 먼저 깨지는 것을 본다.**

## 자주 나는 문제

| 증상 | 원인 |
|---|---|
| `source=clip` 경고 | 가상 디스플레이 생성 실패. `vphone.sh logs` 의 `라이브 소스 실패` 줄을 본다 |
| `uid` 가 2000 이 아님 | `app_process` 가 shell 로 뜨지 않았다 — adb 연결 상태를 본다 |
| 부팅 타임아웃 | `/dev/kvm` 이 없거나 권한이 없다. `ls -l /dev/kvm` |
| `build id mismatch` | APK 와 인자가 다른 빌드. `vphone.sh` 가 APK 에서 직접 읽으므로 보통은 안 난다 |

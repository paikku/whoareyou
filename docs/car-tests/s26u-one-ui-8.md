# M0 스파이크 기록: Galaxy S26 Ultra / One UI 8

날짜: 2026-09-05 (1차, 사용자 구두 보고)
Android 빌드 번호: (미기록)
scrcpy 버전: 4.1 (Windows)

## 실행 명령과 결과

| # | 명령 | 결과 (O/X + 메모) |
|---|---|---|
| 1 | `scrcpy --new-display=1280x720/160 --no-vd-system-decorations --start-app=<앱> --display-ime-policy=local` | VD 생성: **O** / 앱 실행: **O** (폰 화면에는 안 뜸 = 정상) / 터치: **O** (노트북 클릭에 앱 반응) / IME 위치: (미확인) |
| 2 | 1번에 `--audio-source=output` 추가 | 소리 전달: **O** (노트북에서만) / 폰 스피커 무음: **O** |
| 3 | 1번에 `--audio-source=playback --audio-dup` 추가 | (미실시) |
| 4 | 1번에 `--turn-screen-off --stay-awake` 추가 | (미실시 — **전원 버튼으로 화면을 끄면 전부 멈춤**은 확인. 폰 전체가 잠드는 것이므로 서버가 메인 화면만 끄는 방식이 필요) |
| 5 | 1번에 `--screen-off-timeout=300` 추가 | |
| 6 | 1번에 `--keyboard=uhid` 추가 후 삼성 키보드로 한글 입력 | |
| 7 | `--new-display` 만 (system decorations 켠 채) | DeX 런처 노출 여부: |
| 8 | `adb shell "CLASSPATH=/data/local/tmp/scrcpy-server.jar app_process / com.genymobile.scrcpy.Server 4.1 tunnel_forward=true new_display=1280x720/160 audio=false"` | 단독 기동: / 에러 로그: |

## 네트워크 (가정 1) — 2026-09-04, 빌드 75ff76a

| 항목 | 결과 |
|---|---|
| 앱 uid 소켓, 핫스팟 노트북 → 100.99.9.9:3333 | ✗ SYN이 리스너 전에 소멸 (ListenDrops 불변, SYN-RECV 없음). ping·lo·핫스팟 주소는 ✓ |
| shell uid(2000) 소켓, 같은 조건 | ✓ `nc -l` 접속, `com.carcast.server.Server`로 /api/status·영상·control 모두 ✓ |
| 원인 | Android 14+ netd ingress-discard (VPN 주소 + 비VPN 인터페이스 + 앱 uid). `ip rule`은 정상 |
| 차(2026.26)에서 /diag | (미실시) |

## 결정
- 오디오 소스 (`output` / `playback`): **`output`** (폰 무음 + 원격 재생 확인; `playback`은 미실시)
- `vd_system_decorations` 값: **false** (7번 미실시이나 false로 앱 실행·터치가 되므로 유지)
- 기타 quirk: 전원 버튼으로 화면 OFF 시 VD 포함 전부 정지 → M7에서 `requestDisplayPower(main, off)` + stay-awake/wake lock으로 대체하고 4번으로 재확인

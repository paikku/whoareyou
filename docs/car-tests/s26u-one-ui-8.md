# M0 스파이크 기록: Galaxy S26 Ultra / One UI 8

날짜: 2026-09-05 (앱: com.google.android.youtube, 사용자 보고)
Android 빌드 번호: (미기록)
scrcpy 버전: 4.1 (Windows)

## 실행 명령과 결과

| # | 명령 | 결과 (O/X + 메모) |
|---|---|---|
| 1 | `scrcpy --new-display=1280x720/160 --no-vd-system-decorations --start-app=<앱> --display-ime-policy=local` | VD 생성: **O** / 앱 실행: **O** (폰 화면에는 안 뜸 = 정상) / 터치: **O** (노트북 클릭에 앱 반응) / IME 위치: **O** 가상 화면(노트북) 안에 뜸 |
| 2 | 1번에 `--audio-source=output` 추가 | 소리 전달: **O** (노트북에서만) / 폰 스피커 무음: **O** |
| 3 | 1번에 `--audio-source=playback --audio-dup` 추가 | 소리 전달: **O** / 폰 스피커 유지: **O** (양쪽에서 남) |
| 4 | 1번에 `--turn-screen-off --stay-awake` 추가 | **O** — 전원 버튼 없이 폰 화면만 꺼지고 노트북 영상·조작 유지. (전원 버튼으로 끄면 전부 정지하는 것과 대비) |
| 5 | 1번에 `--screen-off-timeout=300 --stay-awake` 추가 | 폰 화면이 자동으로 꺼지지 않음 (`--stay-awake`가 충전 중 화면을 유지하므로 예상된 결과; 4번 방식으로 대체) |
| 6 | 1번에 `--keyboard=uhid` 추가 후 삼성 키보드로 한글 입력 | **O** 한글 입력됨 |
| 7 | `--new-display` 만 (system decorations 켠 채) | **DeX식 바탕화면·작업표시줄 보임** (런처 대용으로 쓸 여지 있음) |
| 8 | `adb shell "CLASSPATH=/data/local/tmp/scrcpy-server.jar app_process / com.genymobile.scrcpy.Server 4.1 tunnel_forward=true new_display=1280x720/160 audio=false"` | 단독 기동: **O** (`[server] INFO: Device: [samsung] samsung SM-S948N (Android 16)` 후 대기) / 에러 로그: 없음 |

## 네트워크 (가정 1) — 2026-09-04, 빌드 75ff76a

| 항목 | 결과 |
|---|---|
| 앱 uid 소켓, 핫스팟 노트북 → 100.99.9.9:3333 | ✗ SYN이 리스너 전에 소멸 (ListenDrops 불변, SYN-RECV 없음). ping·lo·핫스팟 주소는 ✓ |
| shell uid(2000) 소켓, 같은 조건 | ✓ `nc -l` 접속, `com.carcast.server.Server`로 /api/status·영상·control 모두 ✓ |
| 원인 | Android 14+ netd ingress-discard (VPN 주소 + 비VPN 인터페이스 + 앱 uid). `ip rule`은 정상 |
| 차(2026.26)에서 /diag | (미실시) |

## 결정
- 오디오 소스 (`output` / `playback`): **`output`** (차 스피커로만, 폰 무음). `playback+dup`도 되므로 "폰에서도 소리" 옵션으로 남겨둘 수 있음
- `vd_system_decorations` 값: **false** 기본. true면 DeX식 데스크톱이 떠서 M9 런처 대안으로 검토
- 화면 끄기 (M7): scrcpy `--turn-screen-off` 방식(메인 디스플레이만 `requestDisplayPower(0,false)`) + `--stay-awake`(`stay_on_while_plugged_in`)로 확정. 전원 버튼은 전체 정지이므로 금지
- 키보드: IME 로컬 정책으로 삼성 키보드가 VD 안에 뜸. UHID 한글 입력도 됨 (차 브라우저 키보드 텍스트 주입은 M5에서 별도)

# M0 스파이크 기록: Galaxy S26 Ultra / One UI 8

날짜:
Android 빌드 번호:
scrcpy 버전: 4.1

## 실행 명령과 결과

| # | 명령 | 결과 (O/X + 메모) |
|---|---|---|
| 1 | `scrcpy --new-display=1280x720/160 --no-vd-system-decorations --start-app=<앱> --display-ime-policy=local` | VD 생성: / 앱 실행: / 터치: / IME 위치: |
| 2 | 1번에 `--audio-source=output` 추가 | 소리 전달: / 폰 스피커 무음: |
| 3 | 1번에 `--audio-source=playback --audio-dup` 추가 | 소리 전달: / 폰 스피커 유지: |
| 4 | 1번에 `--turn-screen-off` 추가 | 화면 OFF 중 영상 유지: |
| 5 | 1번에 `--screen-off-timeout=300` 추가 | |
| 6 | 1번에 `--keyboard=uhid` 추가 후 삼성 키보드로 한글 입력 | |
| 7 | `--new-display` 만 (system decorations 켠 채) | DeX 런처 노출 여부: |
| 8 | `adb shell "CLASSPATH=/data/local/tmp/scrcpy-server.jar app_process / com.genymobile.scrcpy.Server 4.1 tunnel_forward=true new_display=1280x720/160 audio=false"` | 단독 기동: / 에러 로그: |

## 결정
- 오디오 소스 (`output` / `playback`):
- `vd_system_decorations` 값:
- 기타 quirk:

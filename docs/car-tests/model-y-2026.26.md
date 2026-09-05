# 실차 기록: Model Y / 펌웨어 2026.26

날짜:
폰 빌드(앱 화면의 `빌드 xxxxxxx`):
서버 기동 방식: `daemon=true`로 PC 없이 / PC adb 창
핫스팟 대역: 2.4GHz / 5GHz

주차 상태에서 5분. 절차는 [testing-guide.md §3.C](../testing-guide.md). 차에서는 **사진을 찍지 않아도 된다** —
`/diag`가 끝나면 결과를 폰 서버에 저장하고, 집에서 노트북(`curl http://100.99.9.9:3333/api/reports`)이나
앱의 "차에서 보낸 진단 결과 공유" 버튼으로 꺼내 이 표를 채운다. 저장이 실패했을 때만 화면을 찍는다.

## 1. 접속 (가정 2의 전반)

| # | 확인 | 결과 (O/X + 메모) |
|---|---|---|
| 1 | 차가 폰 핫스팟에 연결됨 | |
| 2 | `http://100.99.9.9:3333/diag` 열림 | |
| 3 | 페이지 상단에 `저장됨 #n` 표시 | |
| 4 | 앱 화면 "차에서 보낸 진단:" 줄에 같은 번호가 보임 | |

2번이 X면 여기서 멈춘다. 차에서 디버깅하지 않는다. 앱 화면의 "서버:" 줄이 `응답 중 shell uid=2000`인지만 확인해 두고 온다.

## 2. `/diag` 수치 (저장된 report에서 옮겨 적기)

| 항목 | 값 | report 필드 |
|---|---|---|
| UA | | `env.UA` |
| viewport / screen / DPR | | `env.viewport`, `env.dpr` |
| secure context | | `env["secure context"]` (http이므로 false여야 정상) |
| MSE avc1.42E01E | | `api["MSE video/mp4; codecs=\"avc1.42E01E\""]` |
| MSE High(avc1.640028) / H.265 / AAC | | `api[...]` |
| WebCodecs / AudioContext / RTCPeerConnection | | `api[...]` |
| WS 20회 성공 / 평균 ms | | `ws.ok`, `ws.avg` |
| 디코드 프레임 / fps / lag ms / 에러 | | `video.frames`, `video.fps`, `video.latencyMs`, `video.error` |
| 사설 주소 대조군 (핫스팟 주소 → 차단되어야 정상) | | `addresses` |

## 3. 재생 (`http://100.99.9.9:3333/`)

| # | 확인 | 결과 |
|---|---|---|
| 1 | 첫 터치 후 영상 재생 시작 | |
| 2 | 전체화면 버튼 동작 | |
| 3 | 화면 터치 시 앱 로그에 `control 패킷` 증가 (집에서 확인) | |
| 4 | 후진 기어 → 다시 D 후 영상 복구 시간 | |
| 5 | 5분 연속 재생 중 끊김 횟수 | |

## 4. 판정 → verification-log.md §1에 반영

- 가정 2 (브라우저가 100.99.9.9를 열고 MSE H.264 디코드):
- 가정 5 (WS 실패율이 재시도로 감당되는 수준):
- 가정 6 (지연 < 300ms):
- 새 quirk / 다음 펌웨어에서 다시 볼 것:

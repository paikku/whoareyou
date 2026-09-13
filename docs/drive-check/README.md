# 드라이브 점검 페이지

기어를 **D** 에 넣었을 때 차 브라우저가 무엇을 멈추는지 눈으로 보는 페이지.
[`web/public/drive-check.html`](../../web/public/drive-check.html) 한 장이고 빌드도 외부 요청도 없다.

APK 에 실려 폰이 서빙하므로 **호스팅 없이 `http://100.99.9.9:3333/drive-check.html` 로 열면 된다.**
평문에서 못 재는 것은 WebCodecs 하나뿐이고(아래 "왜 https 여야 하는가"), 그것만 https 가 필요하다.

## 왜 필요한가

조사해 보니 우리가 겪는 "D 로 바꾸면 멈춤"은 **이미 알려진 두 가지**다.

1. **테슬라는 D 에서 `<video>` 를 정지시킨다.** OS 레벨에서 `video.pause()` 를 부르는 방식이라
   `drawImage(video, canvas)` 로 옮겨 그려도 검은 프레임이 나온다. 오디오와 JS·WebSocket 은 계속 산다.
   **속도가 아니라 기어 기준**이므로 정차 상태에서 D 로 옮기기만 해도 재현된다.
   업계의 우회책은 하나로 수렴해 있다 — **`<video>` 를 아예 쓰지 않고, 프레임을 WebSocket 으로 보내
   canvas 에 그린다.**
2. **테슬라는 D/R 로 옮기면 Wi-Fi 를 기본으로 끊는다.** 네트워크별 `Remain Connected in Drive`
   (2021.24 도입, 기본 꺼짐)를 켜야 유지된다. 차는 폰 핫스팟의 Wi-Fi 클라이언트이므로 정확히 여기 걸린다.
   **차 Wi-Fi 설정에서 먼저 켜고 시작할 것.**

그래서 남는 질문은 "우리 차에서 무엇을 쓸 수 있는가"이고, 이 페이지가 그것만 잰다.

## 왜 https 가 (한 항목에만) 필요한가

`VideoDecoder`(WebCodecs)는 스펙상 secure context 전용이다. 폰이 주는 `http://100.99.9.9` 에서는
기능이 있어도 항상 `undefined` 로 보이므로, 우리 진단 #7·#13 의 `WebCodecs VideoDecoder: false` 가
**기능이 없어서인지 평문이라 가려진 것인지** 지금까지 가릴 수 없었다. https 에서 한 번 보면 갈린다.

이 페이지는 폰과 통신하지 않는다(https 페이지는 `ws://` 를 못 연다 — mixed content). 순수한 브라우저
능력 측정이고, 폰 쪽은 `/api/status` 로 따로 본다.

> 이 제약 자체가 결정에 들어간다: WebCodecs 를 쓰려면 https 만으로는 부족하고 **폰이 wss 까지
> 종단해야 한다.**

## 두 갈래

| | 주소 | 답하는 것 |
|---|---|---|
| **1차 (호스팅 불필요)** | `http://100.99.9.9:3333/drive-check.html` | canvas·타이머·`<video>`·JPEG — 렌더러 결정에 필요한 대부분 |
| **2차 (https 필요)** | 아래 참조 | WebCodecs 하나. 안 (a) 의 생사 |

2차용 https 는 이 저장소로는 못 준다 — **private 저장소라 GitHub Pages 를 쓸 수 없다**(무료 계정 기준).
`web/public/drive-check.html` 을 그대로 올릴 수 있는 곳이면 어디든 된다:

- 이 파일 하나만 담은 **공개 저장소**를 새로 만들고 Pages 켜기 (무료, 영구, 익명 접근 가능).
  페이지에는 비밀이 없다 — 브라우저 능력만 잰다.
- Netlify Drop, Cloudflare Pages 등 정적 https 호스팅.

## 차에서

1. 차 Wi-Fi 설정에서 폰 핫스팟의 **Remain Connected in Drive** 를 켠다.
2. 차 브라우저로 위 주소를 연다. **정차 상태에서 브레이크를 밟고 D 로 옮긴다.**
3. 30 초 본다. 손댈 것 없다. **P 로 돌아온 뒤 맨 아래 한 줄을 사진으로 남긴다.**

차에는 devtools 가 없으므로 모든 결과가 화면에 큰 글씨로 찍힌다.

## 무엇을 읽나

| 화면 | 뜻 |
|---|---|
| ① 캔버스는 도는데 ③ `<video>` 만 "정지" | 예상대로 — 렌더러를 canvas 로 바꾸면 산다 |
| ① 도 멈추고 ② 타이머만 돈다 | 페이지가 백그라운드로 눌린 것 — canvas 우회로도 안 됨 |
| ① ② 둘 다 멈춤 | 페이지 자체가 정지·종료 |
| `⚠ video pause 이벤트` 로그 | 우리가 부른 적 없는 pause = 테슬라의 개입, 그 시각이 기어를 넣은 순간 |
| `secure context: O` 인데 `VideoDecoder: X` | **안 (a) 탈락** — WebCodecs 는 이 브라우저에 없다 |
| `secure context: O`, `VideoDecoder: O` | **안 (a) 살아 있음** — 남은 일은 폰에 https+wss 세우기 |
| JPEG fps / Mbps | 안 (c)(프레임 포맷 추가)의 현실성. 지금 H.264 는 4 Mbps |

## 폰 쪽에서 오늘 확인할 것 (차 불필요)

소프트 디코더(안 b)는 **Baseline 프로파일만** 받는다. 우리 인코더는 `KEY_PROFILE` 을 걸지 않고
벤더 기본값에 맡기므로(`H264Encoder.java:68`) 실제 프로파일을 봐야 한다. 웹 클라이언트가 선언하는
`avc1.42E01E`(`mse.ts:6`)는 하드코딩이라 근거가 못 된다.

실제 SPS 에서 유도한 값이 이미 로그에 있고, 이제 상태에도 실린다:

```
GET /api/log      → "init segment: 1280x720 avc1.XXXXXX"
GET /api/status   → "codec": "avc1.XXXXXX"
```

`avc1.42….` 로 시작하면 Baseline → 안 (b) 가능. `avc1.64….` 면 High → `KEY_PROFILE` 강제를 시도해야
하는데 벤더가 거부할 수 있다. **에뮬레이터가 아니라 실제 폰에서 봐야 한다** — 소프트 인코더와
`c2.qti.avc.encoder` 의 기본 프로파일은 다르다.

## 출처

- [madpowah/tesla-video-drive](https://github.com/madpowah/tesla-video-drive) — `<video>` OS 레벨 정지, MPEG1-TS + canvas 우회
- [1MoreBuild/drive-in](https://github.com/1MoreBuild/drive-in) — Mediabunny + WebCodecs → canvas
- [Suprhimp/castla](https://github.com/Suprhimp/castla) — MediaCodec H.264 → WebCodecs → canvas
- [TesDisplay 매뉴얼](https://tesladisplay.com/tesconnect_manual/) — "Remain connected in Drive" 안내
- [Not a Tesla App](https://www.notateslaapp.com/news/540/tesla-s-remain-connected-to-wifi-how-it-ll-work-and-supported-features) — 2021.24, 네트워크별, 기본 꺼짐

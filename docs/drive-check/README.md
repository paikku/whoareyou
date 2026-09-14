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

## 다음 질문 — 소프트 디코딩이 오래 버티는가

결정은 끝났다(안 b, canvas + WASM 디코더). 실차에서 D 로 30fps·지연 6ms 가 나왔다. 남은 것은
**한 시간짜리 질문**이다: 차 MCU 가 소프트 디코딩을 계속 감당하는가, 아니면 더워지면서 느려지는가.
순간 fps 하나로는 답할 수 없고, 차에 devtools 가 없으니 나중에 물어볼 수도 없다. 그래서 메인 페이지가
**10 초에 한 칸씩 최근 한 시간**을 들고 있다가 💾 에 함께 싣는다.

- **화면**: 상태 줄에 평소에는 안 나온다. 초반보다 30% 넘게 느려지면 `⤵ 30→18fps`, 디코더가 밀리면
  `적체N` 이 붙는다. 그 두 가지가 보이면 그때가 현상이 일어나는 순간이다.
- **저장**: 💾 → 앱의 "차 진단 결과" 에서 리포트를 꺼내면 `perf` 배열이 들어 있다.
  칸마다 `{t: 시작 후 초, fps, lagMs, dropped, backlog}`.

읽는 법은 **backlog 가 가른다**:

| perf 에서 보이는 것 | 뜻 | 할 일 |
|---|---|---|
| fps 유지, backlog 0 | 잘 버틴다 | 없음 |
| fps 하락, backlog 0 | 폰이 안 보낸 것 — 정지 화면에서는 정상 | 없음 |
| **fps 하락 + backlog 증가** | 차가 못 푼다 — 이것이 발열·CPU 한계 | 해상도·fps 를 낮춘다 |
| backlog 가 60 근처에서 `dropped` 증가 | 이미 프레임을 버리고 있다 | 위와 같되 시급 |

같은 리포트의 `caps` 가 **차선책의 가능 여부**를 함께 남긴다 — `webcodecs`(하드웨어 디코더),
`secure`, `cores`, `memGb`. 평문 http 에서는 `secure:false` 이므로 그때의 `webcodecs:false` 는
"없다"가 아니라 "가려져서 모른다"로 읽는다(위 "왜 https 가 필요한가"). 즉 소프트 디코딩이 한계로
드러나면 순서는 ① 해상도·fps 낮추기(폰 쪽 한 줄), ② `caps.webcodecs` 가 살아 있으면 폰에 wss 를
세우고 WebCodecs 로 옮기기다.

## 개선 가능성 점검 (`/upgrade-check.html`)

위의 성능 추이가 "지금 것이 버티는가"라면, 이 페이지는 **"다음에 무엇을 쓸 수 있는가"**를 묻는다.
버티든 안 버티든 따로 남는 질문이고, 해상도를 낮추는 것 말고 어떤 길이 열려 있는지를 한 번에 잰다.
주소: `http://100.99.9.9:3333/upgrade-check.html` (앱에 실려 있다. 진단 페이지에서도 넘어간다).

항목마다 **무엇이 좋아지나 / 이 차에서 되나 / 막는 것이 무엇이냐**를 같이 적고, 네 가지로 가른다:

| 판정 | 뜻 |
|---|---|
| **○ 된다** | 이 차에서 되는 것. 손대면 되는 자리 |
| **△ 뚫으면 된다** | 차는 막고 있지 않은데 **우리 쪽** 조건(응답 헤더 등)이 막는다 |
| **✕ 안 된다** | 이 차에 없다. 더 생각하지 않아도 되는 것 |
| **? 가려짐** | 평문이라 못 쟀다. **"없다"로 읽으면 안 된다** |

재는 것: WebCodecs(하드웨어 디코더), WASM SIMD, 멀티스레드 WASM, OffscreenCanvas, WebGPU,
WebRTC + MediaStreamTrackProcessor, WebTransport, H.264 High·H.265·VP9·AV1, Wake Lock,
Service Worker, IndexedDB.

**WebCodecs 는 기능 검출로 끝내지 않는다.** 720p 키프레임(`web/src/probe/sample.ts`, 폰이 보내는 것과
같은 모양)을 실제로 넣어 그림이 나오는지까지 본다 — `typeof VideoDecoder` 가 함수라는 것과 이 차가
우리 스트림을 푼다는 것은 다른 말이다.

### 평문에서는 절반만 답이 나온다

`secure context` 전용 API(WebCodecs·SharedArrayBuffer·WebGPU·WebTransport·Wake Lock·Service Worker)는
폰이 주는 평문 http 에서 **있어도 안 보인다**. 그래서 폰에서 열면 12 개 중 8 개가 "가려짐"으로 남는다.
페이지 맨 위에 그 사실이 배너로 뜬다.

나머지를 가리려면 **같은 파일 하나(`web/public/upgrade-check.html`)를 아무 https 정적 호스팅에 올려
차에서 한 번 더 열면 된다.** 이 페이지는 폰과 통신하지 않고 혼자 돌기 때문에 그대로 동작한다(폰 저장만
안 되므로 그때는 화면을 사진으로 남긴다). 올릴 곳은 위 "두 갈래" 절과 같다.

> 근본적인 해결은 **폰이 https+wss 로 서빙하는 것**이다. 그러면 이 표도 다 채워지고, WebCodecs 를
> 실제로 쓰는 길도 같이 열린다. 아직 안 했다 — 자체 서명 인증서를 차 브라우저가 받아 주는지부터
> 확인해야 한다.

## 출처

- [madpowah/tesla-video-drive](https://github.com/madpowah/tesla-video-drive) — `<video>` OS 레벨 정지, MPEG1-TS + canvas 우회
- [1MoreBuild/drive-in](https://github.com/1MoreBuild/drive-in) — Mediabunny + WebCodecs → canvas
- [Suprhimp/castla](https://github.com/Suprhimp/castla) — MediaCodec H.264 → WebCodecs → canvas
- [TesDisplay 매뉴얼](https://tesladisplay.com/tesconnect_manual/) — "Remain connected in Drive" 안내
- [Not a Tesla App](https://www.notateslaapp.com/news/540/tesla-s-remain-connected-to-wifi-how-it-ll-work-and-supported-features) — 2021.24, 네트워크별, 기본 꺼짐

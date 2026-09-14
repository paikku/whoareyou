// tinyh264(h264bsd 의 WASM 포팅)를 워커에서 돌린다. 별도 엔트리로 번들되어 h264-worker.js 로 나간다.
//
// wasm 이 data: URI 로 파일 안에 들어 있어 받아올 것이 없다 — 차에는 인터넷이 없고 폰이 주는
// http 만 있으므로 이 점이 중요하다.
//
// 주고받는 말:
//   → { type: 'decode', data, offset, length, renderStateId }   NAL 하나 (Annex-B)
//   ← { type: 'decoderReady' }
//   ← { type: 'pictureReady', width, height, data }             YUV420 한 장
import { init } from 'tinyh264';

void init();

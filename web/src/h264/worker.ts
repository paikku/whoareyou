// tinyh264(h264bsd 의 WASM 포팅)를 워커에서 돌리고, 되도록 **여기서 바로 그린다**.
// 별도 엔트리로 번들되어 h264-worker.js 로 나간다.
//
// wasm 이 data: URI 로 파일 안에 들어 있어 받아올 것이 없다 — 차에는 인터넷이 없고 폰이 주는
// http 만 있으므로 이 점이 중요하다.
//
// 두 가지 모드:
//   - offscreen: 메인이 캔버스 제어권(OffscreenCanvas)을 넘겨 주면 디코드한 YUV 를 이 스레드의 WebGL 로
//     바로 올린다. 메인 스레드는 터치와 UI 만 남고, 프레임마다 1.4 MB 를 스레드 사이로 옮기지 않는다.
//     그리기는 requestAnimationFrame 에 맞춘다: 폰의 인코더는 픽셀이 바뀔 때만 내므로 프레임 간격이
//     고르지 않은데, 도착하는 대로 그리면 그 불규칙함이 그대로 화면에 찍힌다. 한 vsync 에 두 장이 오면
//     마지막 것만 그린다(skipped 로 센다) — 밀린 그림을 그리느라 시간을 쓰지 않는다.
//   - post: OffscreenCanvas 가 없는 브라우저. 예전처럼 YUV 를 메인으로 넘기고 메인이 그린다.
//
// 주고받는 말:
//   → { type: 'canvas', canvas }                                    OffscreenCanvas (transfer)
//   → { type: 'decode', data, offset, length }                      NAL 하나 (Annex-B)
//   → { type: 'release' }
//   ← { type: 'decoderReady' }
//   ← { type: 'pictureReady', width, height, data? }                그림 한 장 디코드됨 (post 모드에만 data)
//   ← { type: 'drawn', skipped }                                    offscreen 모드: 한 장 그림; skipped 는 누적
//   ← { type: 'error', message }
import TinyH264 from 'tinyh264/es/TinyH264.js';
import TinyH264Decoder from 'tinyh264/es/TinyH264Decoder.js';
import { YuvGl } from './yuv-gl';

interface Picture { yuv: Uint8Array; width: number; height: number }

// tsconfig 는 DOM lib 만 있어 self 가 Window 로 잡힌다 — 워커의 postMessage(message, transfer) 로 고쳐 부른다.
const post = (self as unknown as { postMessage: (msg: unknown, transfer?: Transferable[]) => void }).postMessage.bind(self);

let gl: YuvGl | null = null;
let pending: Picture | null = null;
let rafArmed = false;
let skipped = 0;

const raf: ((cb: () => void) => void) | null =
  typeof (self as unknown as { requestAnimationFrame?: unknown }).requestAnimationFrame === 'function'
    ? (cb) => (self as unknown as { requestAnimationFrame: (f: FrameRequestCallback) => number }).requestAnimationFrame(() => cb())
    : null;

function present(): void {
  rafArmed = false;
  const p = pending;
  pending = null;
  if (!p || !gl) return;
  try {
    gl.draw(p.yuv, p.width, p.height);
    post({ type: 'drawn', skipped });
  } catch (e) {
    post({ type: 'error', message: String(e) });
  }
}

function onPicture(yuv: Uint8Array, width: number, height: number): void {
  if (!gl) {
    // post 모드: 버퍼는 디코더가 새로 만든 복사본이라 넘겨도(transfer) 안전하다.
    post({ type: 'pictureReady', width, height, data: yuv.buffer }, [yuv.buffer as ArrayBuffer]);
    return;
  }
  post({ type: 'pictureReady', width, height });
  if (pending) skipped++;
  pending = { yuv, width, height };
  if (!raf) { present(); return; }
  if (!rafArmed) { rafArmed = true; raf(present); }
}

type Msg = { type: string; canvas?: OffscreenCanvas; data?: ArrayBuffer; offset?: number; length?: number };

// 리스너는 **지금** 건다. wasm 이 뜬 뒤에 걸면 그 사이에 온 메시지 — 메인이 attach 때 바로 보내는
// 캔버스 제어권이 그렇다 — 가 아무도 안 받은 채 버려진다. 뜰 때까지는 쌓아 뒀다가 순서대로 돌린다.
let handle: ((msg: Msg) => void) | null = null;
const early: Msg[] = [];
self.addEventListener('message', (ev: MessageEvent) => {
  const msg = ev.data as Msg;
  if (handle) handle(msg); else early.push(msg);
});

void TinyH264().then((module) => {
  let decoder: TinyH264Decoder | null = null;
  handle = (msg: Msg) => {
    switch (msg.type) {
      case 'canvas':
        try {
          gl = new YuvGl(msg.canvas!);
        } catch (e) {
          gl = null;
          post({ type: 'error', message: `워커 WebGL 실패: ${String(e)}` });
        }
        break;
      case 'decode':
        if (!decoder) decoder = new TinyH264Decoder(module, onPicture);
        try {
          decoder.decode(new Uint8Array(msg.data!, msg.offset ?? 0, msg.length ?? msg.data!.byteLength));
        } catch (e) {
          post({ type: 'error', message: `디코드 실패: ${String(e)}` });
        }
        break;
      case 'release':
        decoder?.release();
        decoder = null;
        pending = null;
        break;
    }
  };
  for (const m of early.splice(0)) handle(m);
  post({ type: 'decoderReady' });
});

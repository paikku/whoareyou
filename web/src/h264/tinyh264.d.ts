declare module 'tinyh264' {
  /** 워커 안에서 부른다: wasm 을 올리고 message 핸들러를 건 뒤 decoderReady 를 보낸다. (우리는 안 쓴다 — 아래 둘을 직접 쓴다.) */
  export function init(): Promise<void>;
}

/** h264bsd 의 Emscripten 모듈 팩토리. wasm 은 파일 안에 data: URI 로 들어 있어 받아올 것이 없다. */
declare module 'tinyh264/es/TinyH264.js' {
  export interface TinyH264Module {
    HEAPU8: Uint8Array;
    _h264bsdAlloc(): number;
    _h264bsdInit(storage: number, noOutputReordering: number): number;
    _h264bsdDecode(storage: number, data: number, length: number, picture: number, width: number, height: number): number;
    _h264bsdShutdown(storage: number): void;
    _h264bsdFree(storage: number): void;
    _malloc(bytes: number): number;
    _free(ptr: number): void;
    getValue(ptr: number, type: string): number;
  }
  const TinyH264: () => Promise<TinyH264Module>;
  export default TinyH264;
}

declare module 'tinyh264/es/TinyH264Decoder.js' {
  import type { TinyH264Module } from 'tinyh264/es/TinyH264.js';
  /** NAL 하나씩 넣으면 그림이 나올 때마다 onPictureReady(yuv, w, h) 를 부른다. yuv 는 새로 만든 복사본이다. */
  export default class TinyH264Decoder {
    constructor(module: TinyH264Module, onPictureReady: (yuv: Uint8Array, width: number, height: number) => void);
    decode(nal: Uint8Array | ArrayBuffer): void;
    release(): void;
  }
}

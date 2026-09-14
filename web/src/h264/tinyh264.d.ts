declare module 'tinyh264' {
  /** 워커 안에서 부른다: wasm 을 올리고 message 핸들러를 건 뒤 decoderReady 를 보낸다. */
  export function init(): Promise<void>;
}

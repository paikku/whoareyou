// Wire format shared with the phone (core ControlMessage.kt, shell-server InputInjector.java) and tools/fake-phone.
//
// Media WebSocket (/ws/video, /ws/audio), phone -> car, binary frames:
//   [u8 type][u64 pts_us big-endian][payload]
//   type 0 = INIT   payload = ftyp+moov (fMP4 init segment)
//   type 1 = FRAME  payload = moof+mdat, non-keyframe
//   type 2 = KEY    payload = moof+mdat, keyframe (safe point to start decoding)
//
// Control WebSocket (/ws/control):
//   car -> phone, binary: [u8 kind][...]
//     kind 1 = TOUCH       [u8 action][u8 pointerId][u16 x][u16 y][u16 pressure][u32 tMs]
//                          x,y normalised 0..65535; tMs = the car's clock (performance.now, ms, wraps at 2^32).
//                          The phone stamps the MotionEvent with downTime + (tMs - tMs of the DOWN), so the
//                          gesture keeps the driver's timing and network jitter does not become fling jitter.
//                          A 9-byte packet without tMs is still accepted (older senders, tests/device).
//     kind 2 = KEY         [u8 action][u16 keycode]
//     kind 3 = TEXT        [utf-8 text]
//     kind 4 = KEYFRAME    (no body) — please send an IDR now. Sent when the decoder had to drop frames or
//                          stalled; cheaper than reconnecting the video socket and faster than waiting for the GOP.
//     kind 5 = TOUCH_BATCH [u8 pointerId][u8 n] then n × [u16 x][u16 y][u16 pressure][u32 tMs]
//                          MOVE samples the browser coalesced into one frame (getCoalescedEvents), oldest first;
//                          the phone injects one MotionEvent with the rest as history (MotionEvent.addBatch), which
//                          is what Android's velocity tracker expects from a real touchscreen.
//     kind 6 = PING        [u32 seq][u32 tMs] — the phone echoes the packet back verbatim (binary) on the same
//                          socket; the car reads its own tMs out of the echo and gets the control round trip.
//   phone -> car, text: JSON status {"type":"status", ...}; binary: the PING echo above.

export const MEDIA_HEADER = 9;
export const enum MediaType { Init = 0, Frame = 1, Key = 2 }

export interface MediaPacket {
  type: MediaType;
  ptsUs: number;
  payload: Uint8Array<ArrayBuffer>;
}

export function parseMediaPacket(buf: ArrayBuffer): MediaPacket | null {
  if (buf.byteLength < MEDIA_HEADER) return null;
  const dv = new DataView(buf);
  const type = dv.getUint8(0) as MediaType;
  // pts fits in 53 bits for any realistic session (2^53 us ≈ 285 years).
  const hi = dv.getUint32(1);
  const lo = dv.getUint32(5);
  const ptsUs = hi * 0x1_0000_0000 + lo;
  return { type, ptsUs, payload: new Uint8Array(buf, MEDIA_HEADER) as Uint8Array<ArrayBuffer> };
}

export const enum ControlKind { Touch = 1, Key = 2, Text = 3, Keyframe = 4, TouchBatch = 5, Ping = 6 }
export const enum TouchAction { Down = 0, Up = 1, Move = 2, Cancel = 3 }
export const enum KeyAction { Down = 0, Up = 1 }

// Android KeyEvent codes we send from the nav bar.
export const KEYCODE = { BACK: 4, HOME: 3, APP_SWITCH: 187, ENTER: 66, DEL: 67 } as const;

/** The car's clock as the u32 milliseconds the touch packets carry. */
export function touchClock(): number {
  return Math.round(performance.now()) >>> 0;
}

export function encodeTouch(action: TouchAction, pointerId: number, nx: number, ny: number, pressure: number, tMs: number = touchClock()): ArrayBuffer {
  const b = new ArrayBuffer(13);
  const dv = new DataView(b);
  dv.setUint8(0, ControlKind.Touch);
  dv.setUint8(1, action);
  dv.setUint8(2, pointerId & 0xff);
  dv.setUint16(3, clamp16(nx));
  dv.setUint16(5, clamp16(ny));
  dv.setUint16(7, clamp16(pressure));
  dv.setUint32(9, tMs >>> 0);
  return b;
}

export interface TouchSample { x: number; y: number; pressure: number; tMs: number }

/** MOVE samples for one finger, oldest first. At most 255; a longer run is cut to its newest 255. */
export function encodeTouchBatch(pointerId: number, samples: TouchSample[]): ArrayBuffer {
  const list = samples.length > 255 ? samples.slice(-255) : samples;
  const b = new ArrayBuffer(3 + 10 * list.length);
  const dv = new DataView(b);
  dv.setUint8(0, ControlKind.TouchBatch);
  dv.setUint8(1, pointerId & 0xff);
  dv.setUint8(2, list.length);
  let o = 3;
  for (const s of list) {
    dv.setUint16(o, clamp16(s.x));
    dv.setUint16(o + 2, clamp16(s.y));
    dv.setUint16(o + 4, clamp16(s.pressure));
    dv.setUint32(o + 6, s.tMs >>> 0);
    o += 10;
  }
  return b;
}

export function encodeKey(action: KeyAction, keycode: number): ArrayBuffer {
  const b = new ArrayBuffer(4);
  const dv = new DataView(b);
  dv.setUint8(0, ControlKind.Key);
  dv.setUint8(1, action);
  dv.setUint16(2, keycode);
  return b;
}

export function encodeText(text: string): ArrayBuffer {
  const bytes = new TextEncoder().encode(text);
  const b = new Uint8Array(1 + bytes.length);
  b[0] = ControlKind.Text;
  b.set(bytes, 1);
  return b.buffer;
}

export function encodeKeyframeRequest(): ArrayBuffer {
  return new Uint8Array([ControlKind.Keyframe]).buffer;
}

export function encodePing(seq: number, tMs: number = touchClock()): ArrayBuffer {
  const b = new ArrayBuffer(9);
  const dv = new DataView(b);
  dv.setUint8(0, ControlKind.Ping);
  dv.setUint32(1, seq >>> 0);
  dv.setUint32(5, tMs >>> 0);
  return b;
}

/** The echo of [encodePing], or null when the buffer is something else. */
export function parsePingEcho(buf: ArrayBuffer): { seq: number; tMs: number } | null {
  if (buf.byteLength !== 9) return null;
  const dv = new DataView(buf);
  if (dv.getUint8(0) !== ControlKind.Ping) return null;
  return { seq: dv.getUint32(1), tMs: dv.getUint32(5) };
}

function clamp16(v: number): number {
  return Math.max(0, Math.min(65535, Math.round(v * 65535)));
}

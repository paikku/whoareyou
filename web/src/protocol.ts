// Wire format shared with the phone (app/.../stream) and tools/fake-phone.
//
// Media WebSocket (/ws/video, /ws/audio), phone -> car, binary frames:
//   [u8 type][u64 pts_us big-endian][payload]
//   type 0 = INIT   payload = ftyp+moov (fMP4 init segment)
//   type 1 = FRAME  payload = moof+mdat, non-keyframe
//   type 2 = KEY    payload = moof+mdat, keyframe (safe point to start decoding)
//
// Control WebSocket (/ws/control):
//   car -> phone, binary: [u8 kind][...]
//     kind 1 = TOUCH  [u8 action][u8 pointerId][u16 x][u16 y][u16 pressure]  (x,y normalised 0..65535)
//     kind 2 = KEY    [u8 action][u16 keycode]
//     kind 3 = TEXT   [utf-8 text]
//   phone -> car, text: JSON status {"type":"status", ...}

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

export const enum ControlKind { Touch = 1, Key = 2, Text = 3 }
export const enum TouchAction { Down = 0, Up = 1, Move = 2, Cancel = 3 }
export const enum KeyAction { Down = 0, Up = 1 }

// Android KeyEvent codes we send from the nav bar.
export const KEYCODE = { BACK: 4, HOME: 3, APP_SWITCH: 187, ENTER: 66, DEL: 67 } as const;

export function encodeTouch(action: TouchAction, pointerId: number, nx: number, ny: number, pressure: number): ArrayBuffer {
  const b = new ArrayBuffer(9);
  const dv = new DataView(b);
  dv.setUint8(0, ControlKind.Touch);
  dv.setUint8(1, action);
  dv.setUint8(2, pointerId & 0xff);
  dv.setUint16(3, clamp16(nx));
  dv.setUint16(5, clamp16(ny));
  dv.setUint16(7, clamp16(pressure));
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

function clamp16(v: number): number {
  return Math.max(0, Math.min(65535, Math.round(v * 65535)));
}

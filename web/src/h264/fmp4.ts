// fMP4 로 감싸 보낸 H.264 를 다시 NAL 로 꺼낸다.
//
// MSE 는 fMP4 를 그대로 먹지만 JS 디코더는 Annex-B NAL 을 받는다. 폰을 고치는 대신 차 쪽에서
// 되돌리는 편을 골랐다: 서버(안드로이드)는 빌드·배포 주기가 길고, 지금 포맷 그대로도 필요한 바이트는
// 전부 들어 있다. 나중에 이 경로가 기본이 되면 서버에 raw Annex-B 출구를 내는 편이 더 싸진다.

/** avcC(AVCDecoderConfigurationRecord)에 들어 있는 것 — 파라미터 셋과 mdat 의 길이 접두사 크기. */
export interface AvcConfig {
  sps: Uint8Array[];
  pps: Uint8Array[];
  /** mdat 안 샘플의 길이 접두사 바이트 수 (보통 4). */
  lengthSize: number;
  /** 0x42 = Baseline, 0x64 = High. JS 디코더는 Baseline 만 읽으므로 이걸로 미리 거른다. */
  profileIdc: number;
  levelIdc: number;
}

/**
 * init 세그먼트(ftyp+moov)에서 avcC 를 찾아 읽는다.
 *
 * 박스 트리를 moov/trak/mdia/minf/stbl/stsd/avc1/avcC 로 내려가는 대신 'avcC' 태그를 직접 찾는다 —
 * 우리 muxer 가 만든 것만 들어오고, configurationVersion 으로 한 번 더 확인하므로 이 정도면 충분하다.
 */
export function parseAvcC(init: Uint8Array): AvcConfig | null {
  const at = indexOfTag(init, 'avcC');
  if (at < 0) return null;
  let p = at + 4;
  if (init.length < p + 7 || init[p] !== 1) return null; // configurationVersion
  const profileIdc = init[p + 1]!;
  const levelIdc = init[p + 3]!;
  const lengthSize = (init[p + 4]! & 0x03) + 1;
  p += 5;

  const sps: Uint8Array[] = [];
  let count = init[p]! & 0x1f;
  p += 1;
  for (let i = 0; i < count; i++) {
    const len = (init[p]! << 8) | init[p + 1]!;
    p += 2;
    if (p + len > init.length) return null;
    sps.push(init.subarray(p, p + len));
    p += len;
  }

  const pps: Uint8Array[] = [];
  count = init[p] ?? 0;
  p += 1;
  for (let i = 0; i < count; i++) {
    const len = (init[p]! << 8) | init[p + 1]!;
    p += 2;
    if (p + len > init.length) return null;
    pps.push(init.subarray(p, p + len));
    p += len;
  }

  if (!sps.length || !pps.length) return null;
  return { sps, pps, lengthSize, profileIdc, levelIdc };
}

/** moof+mdat 조각에서 샘플 NAL 들을 꺼낸다 (mdat 안은 길이 접두사 + NAL 의 반복). */
export function mdatNals(fragment: Uint8Array, lengthSize: number): Uint8Array[] {
  const mdat = findTopLevelBox(fragment, 'mdat');
  if (!mdat) return [];
  const out: Uint8Array[] = [];
  let p = 0;
  while (p + lengthSize <= mdat.length) {
    let len = 0;
    for (let i = 0; i < lengthSize; i++) len = len * 256 + mdat[p + i]!;
    p += lengthSize;
    // 길이가 어긋나면 그 뒤는 믿을 수 없다 — 이미 꺼낸 것까지만 쓰고 멈춘다.
    if (len <= 0 || p + len > mdat.length) break;
    out.push(mdat.subarray(p, p + len));
    p += len;
  }
  return out;
}

/**
 * avcC 박스의 내용(configurationVersion 부터 끝까지) 그대로.
 *
 * WebCodecs 의 `VideoDecoder.configure({ description })` 가 원하는 것이 바로 이 바이트다 —
 * 그걸 주면 mdat 안의 길이 접두사 샘플을 **변환 없이** 그대로 먹일 수 있다(avc 포맷). NAL 로 풀어
 * Annex-B 로 다시 감싸는 것은 WASM 디코더(h264bsd) 때문에 하는 일이고, 하드웨어 디코더에는 낭비다.
 */
export function avcCBox(init: Uint8Array): Uint8Array<ArrayBuffer> | null {
  const at = indexOfTag(init, 'avcC');
  if (at < 0) return null;
  // 태그 앞 4 바이트가 박스 크기(자기 자신 포함).
  const sizeAt = at - 4;
  if (sizeAt < 0) return null;
  const size = (init[sizeAt]! << 24 >>> 0) + (init[sizeAt + 1]! << 16) + (init[sizeAt + 2]! << 8) + init[sizeAt + 3]!;
  const start = at + 4;
  const end = sizeAt + size;
  if (size < 8 || end > init.length || end <= start) return null;
  return init.slice(start, end) as Uint8Array<ArrayBuffer>;
}

/** `avc1.PPCCLL` — WebCodecs 와 MSE 가 쓰는 코덱 문자열. SPS 가 말하는 그대로다. */
export function codecString(cfg: AvcConfig): string {
  const sps = cfg.sps[0];
  // profile_idc, constraint flags, level_idc 는 SPS 의 첫 세 바이트(NAL 헤더 다음).
  const constraints = sps && sps.length > 2 ? sps[2]! : 0;
  const hex = (n: number) => n.toString(16).padStart(2, '0');
  return `avc1.${hex(cfg.profileIdc)}${hex(constraints)}${hex(cfg.levelIdc)}`;
}

/** moof+mdat 조각의 mdat 내용 그대로 (길이 접두사 샘플). WebCodecs 에 그대로 넣는다. */
export function mdatBytes(fragment: Uint8Array): Uint8Array<ArrayBuffer> | null {
  const mdat = findTopLevelBox(fragment, 'mdat');
  return mdat ? (mdat.slice() as Uint8Array<ArrayBuffer>) : null;
}

/** NAL 하나를 Annex-B 한 덩어리로 (h264bsd 는 시작 코드를 보고 NAL 경계를 잡는다). */
export function toAnnexB(nal: Uint8Array): Uint8Array<ArrayBuffer> {
  const out = new Uint8Array(new ArrayBuffer(4 + nal.length));
  out[3] = 1; // 00 00 00 01
  out.set(nal, 4);
  return out as Uint8Array<ArrayBuffer>;
}

function findTopLevelBox(buf: Uint8Array, type: string): Uint8Array | null {
  let p = 0;
  while (p + 8 <= buf.length) {
    const size = (buf[p]! << 24 >>> 0) + (buf[p + 1]! << 16) + (buf[p + 2]! << 8) + buf[p + 3]!;
    const name = String.fromCharCode(buf[p + 4]!, buf[p + 5]!, buf[p + 6]!, buf[p + 7]!);
    let header = 8;
    let boxSize = size;
    if (size === 1) {
      // 64-bit 크기. 우리 조각은 절대 4 GB 를 넘지 않으므로 하위 32 비트만 쓴다.
      if (p + 16 > buf.length) return null;
      header = 16;
      boxSize = (buf[p + 12]! << 24 >>> 0) + (buf[p + 13]! << 16) + (buf[p + 14]! << 8) + buf[p + 15]!;
    } else if (size === 0) {
      boxSize = buf.length - p; // 끝까지
    }
    if (boxSize < header || p + boxSize > buf.length) return null;
    if (name === type) return buf.subarray(p + header, p + boxSize);
    p += boxSize;
  }
  return null;
}

function indexOfTag(buf: Uint8Array, tag: string): number {
  const a = tag.charCodeAt(0), b = tag.charCodeAt(1), c = tag.charCodeAt(2), d = tag.charCodeAt(3);
  for (let i = 0; i + 3 < buf.length; i++) {
    if (buf[i] === a && buf[i + 1] === b && buf[i + 2] === c && buf[i + 3] === d) return i;
  }
  return -1;
}

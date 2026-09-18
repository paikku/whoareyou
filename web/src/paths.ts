// 경로(path) 카탈로그 — "폰이 무엇으로 만들고, 차가 무엇으로 푸는가" 한 벌.
//
// 왜 이 파일이 생겼나: 기법 하나가 세 군데에 흩어져 있었다. 렌더러를 고르는 사슬(main.ts 의
// pickRenderer), 폰에 요구할 인코더 프로파일(wantedProfile), 그리고 영상 소켓의 코덱 쿼리
// (`?codec=mjpeg`). 여기에 사다리 천장까지 프리셋 쪽에 boolean 으로 박혀 있었다. 새 기법을 하나
// 넣으려면 네 곳을 같이 고쳐야 했고, 그중 하나를 빠뜨리면 "고르면 화면이 멈추는 버튼"이 생긴다.
//
// 그래서 한 벌을 한 줄로 적는다. **새 기법을 넣는 일 = 이 배열에 항목 하나 더하기.**
//
// 왜 사용자가 고를 수 있어야 하나: 차도 폰도 세대가 다르다. 하드웨어 디코더가 없는 구형 차,
// H.264 인코더가 느린 구형 폰, 링크가 약한 자리 — 무엇이 맞는지는 그 사람의 조합이 정한다.
// 자동 선택은 "아마 이게 제일 나을 것"이지 답이 아니므로, 고르는 길을 막지 않는다.
//
// **새 렌더러를 더할 때** 손댈 곳은 이 배열 하나다: `supported()` 와 `make()` 를 쓰고, 이 경로가 폰에
// 요구하는 인코더와 소켓 쿼리를 적고, 재 본 천장이 있으면 적는다(없으면 `null` — 재지 않은 것을 지어내지
// 않는다). 렌더러가 `onNeedKeyframe`·`onLuma` 를 가지면 main.ts 가 알아서 걸고, 없으면 그 기능이
// (키프레임 재요청·지연 측정) 조용히 빠진다.
//
// **한 벌이 아직 덮지 못하는 것:** `encoder.codec` 은 지금 전부 'avc' 다. HEVC·AV1 을 더하려면 여기에
// 값을 늘리는 것만으로는 안 되고 폰 쪽 절반도 같이 열어야 한다 — `DisplayVideoSource.reconfigure` 가
// 코덱을 받고, `H264Encoder` 자리에 그 코덱의 인코더가 서고, `/api/encoder` 가 그것을 통과시켜야 한다.
// 그 자리를 이름으로 남겨 둔 것이고, 그날 이 주석이 할 일 목록이 된다.
import type { Renderer } from './renderer/types';
import { MseRenderer, mseSupported } from './renderer/mse';
import { MjpegRenderer } from './renderer/mjpeg';
import { H264Renderer, h264Supported } from './renderer/h264';
import { WebCodecsRenderer, webcodecsSupported } from './renderer/webcodecs';

// ── 사다리 ───────────────────────────────────────────────────────────────────────────────────
//
// 크기·fps·비트레이트. 어디까지 고를 수 있는지는 경로가 정한다([presetsFor]).
export interface Preset { id: string; label: string; width: number; height: number; fps: number; bitrate: number }

export const PRESETS: Preset[] = [
  { id: '720p30', label: '기본 · 720p 30fps', width: 1280, height: 720, fps: 30, bitrate: 4_000_000 },
  { id: '720p60', label: '부드럽게 · 720p 60fps', width: 1280, height: 720, fps: 60, bitrate: 6_000_000 },
  { id: '900p30', label: '선명하게 · 900p 30fps', width: 1600, height: 900, fps: 30, bitrate: 6_000_000 },
  { id: '900p60', label: '선명하고 부드럽게 · 900p 60fps', width: 1600, height: 900, fps: 60, bitrate: 8_000_000 },
  { id: '1080p30', label: '최대 · 1080p 30fps', width: 1920, height: 1080, fps: 30, bitrate: 8_000_000 },
  // 비트레이트는 화소율 그대로 1080p30 의 1.5 배 — High 프로파일이 같은 화질을 더 적은 비트로 내므로
  // 두 배까지는 필요 없다. 이 칸이 실제로 도는지는 차·링크마다 다르다(실차 #70: 이 차는 못 버텼고
  // 자동 내리기가 900p60 으로 내렸다). 그래서 천장을 미리 깎지 않고 눌러 볼 수 있게 남겨 둔다.
  { id: '1080p60', label: '최대 · 1080p 60fps', width: 1920, height: 1080, fps: 60, bitrate: 12_000_000 },
];

/** 디코더가 지불하는 부담: 화소 수 × fps. 사다리의 순서도 천장도 이 하나로 잰다. */
export const cost = (p: Preset): number => p.width * p.height * p.fps;

export const presetById = (id: string): Preset | undefined => PRESETS.find((p) => p.id === id);

// ── 경로 ─────────────────────────────────────────────────────────────────────────────────────

/** 렌더러가 붙을 자리들. 경로마다 쓰는 것이 달라서(캔버스냐 <video> 냐) 한 벌로 넘긴다. */
export interface PathElements {
  video: HTMLVideoElement;
  /** mjpeg 가 쓰는 2D 캔버스. */
  canvas: HTMLCanvasElement;
  /** h264(WebGL2)·webcodecs(2D)가 쓰는 캔버스. */
  gl: HTMLCanvasElement;
}

export interface Path {
  /** 렌더러 이름과 같아야 한다 — 상태줄·리포트·`?path=` 가 모두 이 한 낱말을 쓴다. */
  id: string;
  /** 시트에 보이는 이름. */
  label: string;
  /** 한 줄 설명: 무엇이 다르고 누구에게 맞나. 고르는 사람이 읽는 유일한 근거다. */
  detail: string;
  /**
   * 이 경로가 폰에 요구하는 인코더 한 벌. 차가 접속하면 이대로 맞춰 달라고 한다.
   * `profile`: Baseline 은 소프트 디코더(h264bsd)와 <video> 호환을 위한 제약이고, 하드웨어
   * 디코더는 High 까지 읽는다(같은 화질에 비트를 덜 쓴다 — 링크가 약한 자리에서 이게 크다).
   */
  encoder: { codec: 'avc'; profile: 'baseline' | 'high' };
  /** 영상 소켓에 붙일 쿼리. 폰이 아예 다른 것을 보내야 하는 경로(mjpeg)만 채운다. */
  videoQuery: string;
  /**
   * 사다리 천장: 부담(`cost`)이 이 프리셋 이하인 것만 낸다. **null 이면 전부** — 재 보지 않은 경로에
   * 천장을 지어내지 않는다는 뜻이다. 무엇이 도는지는 그 사람의 차·링크가 정하고, 못 따라오면 자동
   * 내리기가 그 경계를 찾아 준다(실차 #70 이 그 동작의 기록이다).
   */
  ceiling: string | null;
  /** https(secure context)에서만 존재하는가. 평문에서 고르면 그 주소로 옮겨가야 한다. */
  needsSecureContext: boolean;
  /** 자동 선택 순위. 큰 것부터 보고, 되는 것 중 첫 번째를 쓴다. */
  rank: number;
  /** 이 브라우저에서 쓸 수 있나. */
  supported(): boolean;
  make(el: PathElements): Renderer;
}

export const PATHS: Path[] = [
  {
    id: 'webcodecs',
    label: '하드웨어 디코더',
    detail: '차의 전용 디코더가 푼다. 가장 빠르고(실차 실측 1.1ms) 화질도 좋다. https 주소에서만 열린다.',
    encoder: { codec: 'avc', profile: 'high' },
    videoQuery: '',
    ceiling: null,
    needsSecureContext: true,
    rank: 40,
    supported: webcodecsSupported,
    make: (el) => new WebCodecsRenderer(el.gl),
  },
  {
    id: 'h264',
    label: '소프트 디코더',
    detail: '차 CPU 가 푼다(WASM). 어디서나 되지만 1080p60 은 무리다. 평문 주소의 기본.',
    encoder: { codec: 'avc', profile: 'baseline' },
    videoQuery: '',
    // 차 CPU 로 720p 한 장에 10ms 였다(실측 2026-09-17). 60fps 예산이 16ms 이므로 1080p60 은 그 위다.
    // 이것이 유일하게 근거 있는 천장이다 — 다른 경로에 천장을 두지 않은 이유는 아래에 적었다.
    ceiling: '900p60',
    needsSecureContext: false,
    rank: 30,
    supported: h264Supported,
    make: (el) => new H264Renderer(el.gl),
  },
  {
    id: 'mse',
    label: '<video> (MSE)',
    detail: '브라우저에 통째로 맡긴다. 지연이 크고(80~140ms) 기어가 P 를 벗어나면 프레임 공급이 끊긴다. 위 둘이 안 될 때.',
    encoder: { codec: 'avc', profile: 'baseline' },
    videoQuery: '',
    // 천장 없음: <video> 는 브라우저 자신의 디코더를 쓰므로 WASM 과 사정이 다르고, 얼마까지 감당하는지
    // 우리가 잰 적이 없다. 재지 않은 것을 미리 깎지 않는다 — 못 따라오면 자동 내리기가 찾아 준다.
    ceiling: null,
    needsSecureContext: false,
    rank: 20,
    supported: mseSupported,
    make: (el) => new MseRenderer(el.video),
  },
  {
    id: 'mjpeg',
    label: '낱장 그림 (MJPEG)',
    detail: '장면을 그림 한 장씩 받는다. 느리고 거칠지만 H.264 디코더가 아예 없어도 뜬다. 최후의 수단.',
    encoder: { codec: 'avc', profile: 'baseline' },
    videoQuery: '?codec=mjpeg',
    // 천장 없음, 같은 이유로. 이 경로가 느린 것은 분명하지만 어디서 무너지는지는 재 본 적이 없다.
    ceiling: null,
    needsSecureContext: false,
    rank: 10,
    supported: () => true,
    make: (el) => new MjpegRenderer(el.canvas),
  },
];

export const pathById = (id: string | null | undefined): Path | undefined =>
  PATHS.find((p) => p.id === id);

/**
 * 이 경로가 낼 수 있는 프리셋만. 순서는 선언 그대로다 — 화면에 그 순서로 놓이고, 사람은 해상도별로
 * 묶인 쪽을 읽기 쉬워한다. 자동 내리기가 쓰는 부담 순서는 따로 세운다(main.ts 의 `ladder`).
 */
export function presetsFor(path: Path): Preset[] {
  const cap = path.ceiling ? presetById(path.ceiling) : null;
  const max = cap ? cost(cap) : Infinity;
  return PRESETS.filter((p) => cost(p) <= max);
}

/** 되는 것 중 가장 좋은 것. 아무것도 안 되면 mjpeg — 그림이 없는 화면보다는 거친 그림이 낫다. */
export function autoPath(): Path {
  const ok = PATHS.filter((p) => p.supported()).sort((a, b) => b.rank - a.rank);
  return ok[0] ?? PATHS[PATHS.length - 1]!;
}

export interface Chosen { path: Path; auto: boolean; why: string }

/**
 * 어떤 경로로 갈지 정한다.
 *
 * [forced] 는 주소의 `?path=`(옛 이름 `?renderer=`)다 — **되는지 묻지 않고 그대로 쓴다.** 진단용이고,
 * "이 브라우저에 정말 없는가"를 보려면 없는 길로도 가 봐야 하기 때문이다.
 *
 * [stored] 는 사람이 시트에서 고른 것이다 — **되는 것으로만 쓴다.** 차 화면의 선택은 오래 남는데
 * (localStorage), 펌웨어가 바뀌어 그 API 가 사라지면 다음에 탔을 때 검은 화면이 된다. 그럴 때는
 * 조용히 자동으로 돌아가고 이유를 남긴다.
 */
export function pickPath(forced?: string | null, stored?: string | null): Chosen {
  const f = pathById(forced);
  if (f) return { path: f, auto: false, why: `주소에서 지정 (?path=${f.id})` };
  const s = pathById(stored);
  if (s && s.supported()) return { path: s, auto: false, why: '이 차에서 고른 경로' };
  if (s) return { path: autoPath(), auto: true, why: `고른 경로(${s.id})를 이 브라우저가 못 쓴다 — 자동으로 되돌림` };
  return { path: autoPath(), auto: true, why: '자동' };
}

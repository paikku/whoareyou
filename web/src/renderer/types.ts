import type { MediaPacket } from '../protocol';

export interface RendererStats {
  framesDecoded: number;
  fps: number;
  latencyMs: number;      // buffered-end minus currentTime, i.e. what we are lagging behind the live edge
  droppedFrames: number;
  lastError: string;
  /**
   * 아직 그림이 안 나온 접근 단위의 수 — 소프트 디코더가 따라오고 있는지를 보는 자리다.
   * 0 근처면 여유가 있고, 계속 자라면 그 기기가 이 해상도·fps 를 감당하지 못한다는 뜻이다.
   * <video> 에 맡기는 렌더러는 알 수 없으므로 내지 않는다.
   */
  backlog?: number;
  /** rAF 에 맞춰 그리면서 건너뛴 그림의 수(한 vsync 에 두 장이 왔을 때). h264 렌더러만 낸다. */
  skipped?: number;
  /**
   * 밀린 채(적체 8 초과) 도착했지만 **버리지 않고 푼** 프레임의 수. webcodecs 렌더러만 낸다 — 하드웨어
   * 디코더는 밀린 것을 따라잡을 수 있으므로 버리지 않고, 대신 "한꺼번에 왔다"를 이 수로 남긴다. 링크가
   * 잠깐 막혔다는 신호라 abr 이 드롭과 같이 읽는다(main.ts).
   */
  late?: number;
  /** 워커가 OffscreenCanvas 에 직접 그리는가. h264 렌더러만 낸다. */
  offscreen?: boolean;
  /**
   * 하드웨어 디코더를 받았는가. WebCodecs 만 낼 수 있고, 브라우저가 알려 주지 않으면 undefined 다
   * (요청은 `prefer-hardware` 이지만 준 것이 무엇인지는 명세가 보장하지 않는다).
   */
  hardware?: boolean;
}

export interface Renderer {
  readonly name: string;
  /**
   * 프레임을 버렸을 때 폰에 키프레임을 부탁하는 길 — 버린 자리부터는 참조가 깨져 있어서, 다음 IDR
   * 까지 무엇을 넣어도 그림만 깨진다. 스스로 디코딩하는 렌더러만 알 수 있으므로 선택 사항이다
   * (<video> 에 맡기는 쪽은 자기가 무엇을 버렸는지 모른다). main.ts 가 건다.
   */
  onNeedKeyframe?: (() => void) | null;
  /**
   * 그림 가운데의 평균 밝기. 끝에서 끝까지 지연 측정이 "화면이 뒤집혔다"를 이걸로 본다. 픽셀을
   * 직접 만지는 렌더러만 낼 수 있다 — 이것이 없는 경로에서는 측정 버튼이 그렇게 말한다.
   */
  onLuma?: ((luma: number, atMs: number) => void) | null;
  /**
   * <video> 를 쓰는 렌더러만 참이다. 캔버스는 자동재생 제한을 받지 않으므로 첫 터치 없이 바로
   * 그려진다 — 차에 타면 화면이 이미 나와 있다.
   */
  readonly needsGesture: boolean;
  attach(container: HTMLElement): void;
  push(packet: MediaPacket): void;
  /** Called on the first user gesture; renderers that need play() do it here. */
  resume(): Promise<void>;
  reset(): void;
  stats(): RendererStats;
  destroy(): void;
}

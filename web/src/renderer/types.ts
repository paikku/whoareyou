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
  /** 워커가 OffscreenCanvas 에 직접 그리는가. h264 렌더러만 낸다. */
  offscreen?: boolean;
}

export interface Renderer {
  readonly name: string;
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

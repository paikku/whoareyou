import type { MediaPacket } from '../protocol';

export interface RendererStats {
  framesDecoded: number;
  fps: number;
  latencyMs: number;      // buffered-end minus currentTime, i.e. what we are lagging behind the live edge
  droppedFrames: number;
  lastError: string;
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

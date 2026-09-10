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
  attach(container: HTMLElement): void;
  push(packet: MediaPacket): void;
  /** Called on the first user gesture; renderers that need play() do it here. */
  resume(): Promise<void>;
  reset(): void;
  stats(): RendererStats;
  /** One line of pipeline state for the session log (what the element was doing when a stall was declared). */
  debug?(): string;
  destroy(): void;
}

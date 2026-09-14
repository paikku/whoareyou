// Last-resort renderer: JPEG frames drawn on a canvas. Payload of each packet is a JPEG image.
import type { MediaPacket } from '../protocol';
import type { Renderer, RendererStats } from './types';

export class MjpegRenderer implements Renderer {
  readonly name = 'mjpeg';
  readonly needsGesture = false;
  private ctx: CanvasRenderingContext2D | null = null;
  private st: RendererStats = { framesDecoded: 0, fps: 0, latencyMs: 0, droppedFrames: 0, lastError: '' };
  private times: number[] = [];
  private busy = false;

  constructor(private readonly canvas: HTMLCanvasElement) {}

  attach(): void {
    this.canvas.hidden = false;
    this.ctx = this.canvas.getContext('2d');
  }

  push(p: MediaPacket): void {
    if (this.busy) { this.st.droppedFrames++; return; }
    this.busy = true;
    const blob = new Blob([p.payload], { type: 'image/jpeg' });
    createImageBitmap(blob).then((bmp) => {
      if (this.canvas.width !== bmp.width || this.canvas.height !== bmp.height) {
        this.canvas.width = bmp.width;
        this.canvas.height = bmp.height;
      }
      this.ctx?.drawImage(bmp, 0, 0);
      bmp.close();
      const now = performance.now();
      this.times.push(now);
      while (this.times.length && now - this.times[0]! > 1000) this.times.shift();
      this.st.fps = this.times.length;
      this.st.framesDecoded++;
    }).catch((e) => { this.st.lastError = String(e); }).finally(() => { this.busy = false; });
  }

  async resume(): Promise<void> { /* nothing to do */ }
  reset(): void { /* stateless */ }
  stats(): RendererStats { return { ...this.st }; }
  destroy(): void { this.canvas.hidden = true; }
}

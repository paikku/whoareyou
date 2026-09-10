// Low-latency MSE renderer: one SourceBuffer per track, fMP4 fragments appended as they
// arrive, playback kept close to the live edge. Works on http:// origins (no secure context needed).
import { MediaType, type MediaPacket } from '../protocol';
import type { Renderer, RendererStats } from './types';

export const H264_MIME = 'video/mp4; codecs="avc1.42E01E"';
export const AAC_MIME = 'audio/mp4; codecs="mp4a.40.2"';

const MAX_LAG_S = 0.3;      // when the buffer runs further ahead than this, jump to the live edge
const TRIM_KEEP_S = 8;      // keep this much history in the SourceBuffer, remove older

export function mseSupported(mime: string = H264_MIME): boolean {
  return typeof MediaSource !== 'undefined' && MediaSource.isTypeSupported(mime);
}

export class MseRenderer implements Renderer {
  readonly name = 'mse';
  private readonly video: HTMLVideoElement;
  private ms: MediaSource | null = null;
  private sb: SourceBuffer | null = null;
  private queue: Uint8Array<ArrayBuffer>[] = [];
  private objectUrl = '';
  private haveInit = false;
  private waitingForKey = true;
  private wantPlay = false; // set once the user gesture unlocked playback
  private st: RendererStats = { framesDecoded: 0, fps: 0, latencyMs: 0, droppedFrames: 0, lastError: '' };
  private fpsWindow: number[] = [];
  private rvfcHandle = 0;
  private trimTimer = 0;

  constructor(video: HTMLVideoElement, private readonly mime: string = H264_MIME) {
    this.video = video;
  }

  attach(_container: HTMLElement): void {
    this.open();
    this.startFrameCounter();
    this.trimTimer = window.setInterval(() => this.trim(), 2000);
  }

  private open(): void {
    this.close();
    const ms = new MediaSource();
    this.ms = ms;
    this.objectUrl = URL.createObjectURL(ms);
    this.video.src = this.objectUrl;
    ms.addEventListener('sourceopen', () => {
      if (this.ms !== ms) return;
      try {
        const sb = ms.addSourceBuffer(this.mime);
        sb.mode = 'segments';
        // catchUp after every append, not only before the next one: with a single fragment in the
        // buffer (car, first frame) nothing else ever arrives to trigger the jump to the live edge.
        sb.addEventListener('updateend', () => { this.pump(); this.catchUp(); });
        sb.addEventListener('error', () => { this.st.lastError = 'sourcebuffer error'; });
        this.sb = sb;
        this.pump();
        // Replacing `src` (reconnect, encoder restart) pauses the element; user activation is sticky,
        // so play() succeeds here without a new gesture.
        if (this.wantPlay) this.video.play().catch((e) => this.playFailed(e));
      } catch (e) {
        this.st.lastError = `addSourceBuffer: ${String(e)}`;
      }
    });
  }

  private close(): void {
    if (this.objectUrl) URL.revokeObjectURL(this.objectUrl);
    this.objectUrl = '';
    this.ms = null;
    this.sb = null;
    this.queue = [];
    this.haveInit = false;
    this.waitingForKey = true;
  }

  push(p: MediaPacket): void {
    if (p.type === MediaType.Init) {
      // A new init segment means the encoder restarted: rebuild the pipeline.
      if (this.haveInit) this.open();
      this.haveInit = true;
      this.waitingForKey = true;
      this.queue.push(p.payload);
    } else {
      if (!this.haveInit) return;
      if (this.waitingForKey) {
        if (p.type !== MediaType.Key) { this.st.droppedFrames++; return; }
        this.waitingForKey = false;
      }
      this.queue.push(p.payload);
    }
    this.pump();
  }

  private pump(): void {
    const sb = this.sb;
    if (!sb || sb.updating || this.queue.length === 0) return;
    if (this.ms?.readyState !== 'open') return;
    const chunk = this.queue.shift()!;
    try {
      sb.appendBuffer(chunk);
    } catch (e) {
      // QuotaExceededError or a torn-down buffer: drop history and retry on the next packet.
      this.st.lastError = `appendBuffer: ${String(e)}`;
      this.queue = [];
      this.waitingForKey = true;
      this.trim(true);
    }
    this.catchUp();
  }

  private catchUp(): void {
    const v = this.video;
    if (v.buffered.length === 0) return;
    const end = v.buffered.end(v.buffered.length - 1);
    const lag = end - v.currentTime;
    this.st.latencyMs = Math.max(0, lag * 1000);
    if (v.paused) return; // resume() will start playback on the first gesture
    if (lag > MAX_LAG_S) {
      // Never land before the range we have (a lone 30 ms fragment would put end-0.05 in the gap).
      v.currentTime = Math.max(v.buffered.start(v.buffered.length - 1), end - 0.05);
    } else if (lag > MAX_LAG_S / 2) {
      v.playbackRate = 1.1;
    } else if (v.playbackRate !== 1) {
      v.playbackRate = 1;
    }
  }

  private trim(force = false): void {
    const sb = this.sb;
    const v = this.video;
    if (!sb || sb.updating || v.buffered.length === 0) return;
    const start = v.buffered.start(0);
    const cut = v.currentTime - TRIM_KEEP_S;
    if (force || cut > start + 1) {
      try { sb.remove(0, Math.max(start, cut)); } catch { /* ignore */ }
    }
  }

  async resume(): Promise<void> {
    this.wantPlay = true;
    try {
      await this.video.play();
    } catch (e) {
      this.playFailed(e);
    }
  }

  private playFailed(e: unknown): void {
    // AbortError just means `src` was replaced (another reconnect) before playback started;
    // the new pipeline re-issues play() itself. Anything else (NotAllowedError...) is real.
    if (e instanceof DOMException && e.name === 'AbortError') return;
    this.st.lastError = `play: ${String(e)}`;
  }

  reset(): void {
    this.open();
  }

  private startFrameCounter(): void {
    const v = this.video as HTMLVideoElement & {
      requestVideoFrameCallback?: (cb: (now: number, meta: { presentedFrames: number }) => void) => number;
    };
    if (typeof v.requestVideoFrameCallback === 'function') {
      const tick = (now: number) => {
        this.st.framesDecoded++;
        this.fpsWindow.push(now);
        while (this.fpsWindow.length && now - this.fpsWindow[0]! > 1000) this.fpsWindow.shift();
        this.st.fps = this.fpsWindow.length;
        this.rvfcHandle = v.requestVideoFrameCallback!(tick);
      };
      this.rvfcHandle = v.requestVideoFrameCallback(tick);
    } else {
      // Older Chromium: approximate with the quality API.
      window.setInterval(() => {
        const q = (this.video as any).getVideoPlaybackQuality?.();
        if (q) this.st.framesDecoded = q.totalVideoFrames;
      }, 500);
    }
  }

  stats(): RendererStats {
    const now = performance.now();
    while (this.fpsWindow.length && now - this.fpsWindow[0]! > 1000) this.fpsWindow.shift();
    this.st.fps = this.fpsWindow.length;
    return { ...this.st };
  }

  destroy(): void {
    window.clearInterval(this.trimTimer);
    this.close();
    this.video.removeAttribute('src');
    this.video.load();
  }
}

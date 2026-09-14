// Low-latency MSE renderer: one SourceBuffer per track, fMP4 fragments appended as they
// arrive, playback kept close to the live edge. Works on http:// origins (no secure context needed).
import { MediaType, type MediaPacket } from '../protocol';
import type { Renderer, RendererStats } from './types';

export const H264_MIME = 'video/mp4; codecs="avc1.42E01E"';
export const AAC_MIME = 'audio/mp4; codecs="mp4a.40.2"';

const MAX_LAG_S = 0.3;      // when the buffer runs further ahead than this, jump to the live edge
const TRIM_KEEP_S = 8;      // keep this much history in the SourceBuffer, remove older
// Every fragment from the phone says its frame lasts 33 ms. On a static screen the next frame comes
// 100-150 ms later (or much later), so the timeline is full of gaps, playback underflows at the end
// of each 33 ms range and Chrome will not resume until several frames are queued — in the car that
// read "0 fps, lag 8 ms, packets still arriving". A frame is really valid until the next one, so we
// restamp each fragment's sample duration to this before appending: the buffered range always
// reaches well past the last frame, the playhead keeps moving at 1x in step with the phone's clock,
// and a new frame is simply shown when its pts comes up. Lag is measured against the last frame's
// pts, not the (padded) buffered end.
const FRAME_TAIL_US = 30_000_000;

/**
 * Overwrite the single sample's duration in a moof produced by our Fmp4Writer / the fake phone:
 * one `trun` with flags data-offset|duration|size|flags, so the duration is the first per-sample field.
 */
export function patchSampleDuration(frag: Uint8Array, durationUs: number): boolean {
  const limit = Math.min(frag.length - 8, 512); // the moof is tiny and precedes mdat; never scan the payload
  for (let i = 0; i < limit; i++) {
    if (frag[i] !== 0x74 || frag[i + 1] !== 0x72 || frag[i + 2] !== 0x75 || frag[i + 3] !== 0x6e) continue; // 'trun'
    const flags = (frag[i + 5]! << 16) | (frag[i + 6]! << 8) | frag[i + 7]!;
    if (!(flags & 0x100)) return false;
    let off = i + 4 + 4 + 4;                 // type, version+flags, sample_count
    if (flags & 0x1) off += 4;                // data_offset
    if (flags & 0x4) off += 4;                // first_sample_flags
    if (off + 4 > frag.length) return false;
    frag[off] = (durationUs >>> 24) & 0xff; frag[off + 1] = (durationUs >>> 16) & 0xff;
    frag[off + 2] = (durationUs >>> 8) & 0xff; frag[off + 3] = durationUs & 0xff;
    return true;
  }
  return false;
}

export function mseSupported(mime: string = H264_MIME): boolean {
  return typeof MediaSource !== 'undefined' && MediaSource.isTypeSupported(mime);
}

export class MseRenderer implements Renderer {
  readonly name = 'mse';
  readonly needsGesture = true;
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
  private lastPtsS = -1;        // pts of the newest frame pushed, in media-timeline seconds
  private lastFrameAtMs = 0;    // performance.now() when it arrived

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
        sb.mode = 'segments'; // real pts: the phone's clock is the timeline (see FRAME_TAIL_US)
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
    this.lastPtsS = -1;
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
      patchSampleDuration(p.payload, FRAME_TAIL_US);
      this.lastPtsS = p.ptsUs / 1e6;
      this.lastFrameAtMs = performance.now();
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
    if (v.buffered.length === 0 || this.lastPtsS < 0) return;
    // How far the newest frame is ahead of the playhead. Negative means the playhead has run past
    // it — normal on a static screen (nothing newer exists yet), a problem only if frames are
    // arriving and still land behind us (clock drift, or an overshoot of the 1.1x catch-up).
    const lag = this.lastPtsS - v.currentTime;
    this.st.latencyMs = Math.max(0, lag * 1000);
    if (v.paused) return; // resume() will start playback on the first gesture
    if (lag > MAX_LAG_S) {
      v.currentTime = Math.max(v.buffered.start(v.buffered.length - 1), this.lastPtsS - 0.05);
    } else if (lag < -0.05 && performance.now() - this.lastFrameAtMs < 500) {
      v.currentTime = this.lastPtsS;
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

// Audio playback next to the video: its own <audio> element and MediaSource fed from /ws/audio
// (AAC-LC fMP4, one 21 ms frame per fragment). Deliberately not a second track in the video's
// MediaSource: an HTMLMediaElement waits for *every* SourceBuffer to cover the playhead, so audio
// that stops (the phone's REMOTE_SUBMIX goes quiet, the capture dies, a socket hiccup) would freeze
// the picture, and vice versa. Both streams are stamped with the phone's monotonic clock, so keeping
// them in step is a matter of steering this element's playhead onto the video's (syncTo).
import { MediaType, type MediaPacket } from '../protocol';

export const AAC_MIME = 'audio/mp4; codecs="mp4a.40.2"';

const TRIM_KEEP_S = 8;
// Measured against the fake phone (Chrome 148): a seek on the MSE <audio> lands ~180 ms late (the
// element resumes that far behind where it was sent), so seeking whenever the gap exceeds the seek
// latency just seeks forever; and the element stops advancing once less than ~120-130 ms is buffered
// past its playhead, which a 1.05x pull reaches within seconds when the video sits at its own live
// edge (a stall, a seek, another pull: a 5 s cycle). So: seek only for real jumps, pull gently and
// only while there is spare buffer, ease off before the underrun — and the video holds back a little
// (MseRenderer.setTargetLag) so that "in step" is reachable at all.
const SEEK_IF_OFF_S = 0.25;   // jump when this far from the video's playhead (the video itself jumped, a reconnect)
const SEEK_COOLDOWN_MS = 1000; // one seek, then let the nudge absorb the seek latency instead of seeking again
const SEEK_LEAD_S = 0.15;     // land ahead by about the seek latency, when that much is buffered (rarely at the live edge)
const NUDGE_IF_OFF_S = 0.03;  // 1.05x / 0.95x inside this band nothing is touched
const PULL_NEEDS_AHEAD_S = 0.18; // only speed up while this much is buffered past the playhead
const EASE_IF_AHEAD_BELOW_S = 0.12; // and slow down before the element underruns

export interface AudioStats {
  packets: number;      // everything received on /ws/audio, init segments included
  frames: number;       // AAC frames appended
  bufferedMs: number;   // length of the buffered range around the playhead (0 = nothing there)
  syncMs: number;       // audio playhead minus video playhead; positive = audio ahead
  seeks: number;        // hard corrections made by syncTo
  playing: boolean;     // play() succeeded and the element is not paused
  muted: boolean;
  lastPtsS: number;     // pts of the newest frame, in media-timeline seconds
  stalledTicks: number; // consecutive syncTo calls with data at the playhead but no progress
  lastError: string;
}

export function audioSupported(): boolean {
  return typeof MediaSource !== 'undefined' && MediaSource.isTypeSupported(AAC_MIME);
}

export class AudioPlayer {
  private ms: MediaSource | null = null;
  private sb: SourceBuffer | null = null;
  private queue: Uint8Array<ArrayBuffer>[] = [];
  private objectUrl = '';
  private haveInit = false;
  private wantPlay = false;
  private trimTimer = 0;
  private lastT = -1;
  private lastSeekAt = -Infinity;
  private st: AudioStats = { packets: 0, frames: 0, bufferedMs: 0, syncMs: 0, seeks: 0, playing: false, muted: false, lastPtsS: -1, stalledTicks: 0, lastError: '' };

  constructor(private readonly audio: HTMLAudioElement) {}

  attach(): void {
    this.open();
    this.trimTimer = window.setInterval(() => this.trim(), 2000);
    this.audio.addEventListener('error', () => { this.st.lastError = `media error ${this.audio.error?.code ?? '?'}`; });
  }

  private open(): void {
    this.close();
    const ms = new MediaSource();
    this.ms = ms;
    this.objectUrl = URL.createObjectURL(ms);
    this.audio.src = this.objectUrl;
    ms.addEventListener('sourceopen', () => {
      if (this.ms !== ms) return;
      try {
        const sb = ms.addSourceBuffer(AAC_MIME);
        sb.mode = 'segments';
        sb.addEventListener('updateend', () => this.pump());
        sb.addEventListener('error', () => { this.st.lastError = 'sourcebuffer error'; });
        this.sb = sb;
        this.pump();
        if (this.wantPlay) this.audio.play().catch((e) => this.playFailed(e));
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
    this.st.lastPtsS = -1;
    this.lastT = -1;
  }

  push(p: MediaPacket): void {
    this.st.packets++;
    if (p.type === MediaType.Init) {
      if (this.haveInit) this.open(); // the phone's encoder restarted
      this.haveInit = true;
      this.queue.push(p.payload);
    } else {
      if (!this.haveInit) return;
      this.st.frames++;
      this.st.lastPtsS = p.ptsUs / 1e6;
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
      this.st.lastError = `appendBuffer: ${String(e)}`;
      this.queue = [];
      this.trim(true);
    }
  }

  /**
   * Steer the playhead onto the video's (both timelines are the phone's clock). Called
   * periodically while the video plays. Does nothing when the audio for that moment has not
   * arrived yet — the element simply waits, the video does not.
   */
  syncTo(videoTimeS: number): void {
    const a = this.audio;
    this.st.playing = this.wantPlay && !a.paused;
    this.st.muted = a.muted;
    let contained = false;
    let bufferedMs = 0;
    let bufferedEnd = 0;
    for (let i = 0; i < a.buffered.length; i++) {
      if (a.buffered.start(i) - 0.05 <= videoTimeS && videoTimeS <= a.buffered.end(i)) {
        contained = true;
        bufferedMs = Math.round((a.buffered.end(i) - a.buffered.start(i)) * 1000);
        bufferedEnd = a.buffered.end(i);
      }
    }
    this.st.bufferedMs = bufferedMs;
    if (a.paused) { this.st.stalledTicks = 0; return; }
    const diff = a.currentTime - videoTimeS;
    this.st.syncMs = Math.round(diff * 1000);
    if (!contained) { this.st.stalledTicks = 0; return; }
    if (Math.abs(diff) > SEEK_IF_OFF_S && performance.now() - this.lastSeekAt > SEEK_COOLDOWN_MS) {
      a.currentTime = Math.min(videoTimeS + SEEK_LEAD_S, Math.max(videoTimeS, bufferedEnd - 0.1));
      a.playbackRate = 1;
      this.lastSeekAt = performance.now();
      this.st.seeks++;
      this.st.stalledTicks = 0;
    } else {
      const ahead = bufferedEnd - a.currentTime;
      a.playbackRate = diff > NUDGE_IF_OFF_S || ahead < EASE_IF_AHEAD_BELOW_S ? 0.95
        : diff < -NUDGE_IF_OFF_S && ahead > PULL_NEEDS_AHEAD_S ? 1.05 : 1;
      // Data under the playhead, not paused, yet no progress since the last call: a wedged pipeline.
      this.st.stalledTicks = a.currentTime === this.lastT ? this.st.stalledTicks + 1 : 0;
    }
    this.lastT = a.currentTime;
  }

  private trim(force = false): void {
    const sb = this.sb;
    const a = this.audio;
    if (!sb || sb.updating || a.buffered.length === 0) return;
    const start = a.buffered.start(0);
    const cut = a.currentTime - TRIM_KEEP_S;
    if (force || cut > start + 1) {
      try { sb.remove(0, Math.max(start, cut)); } catch { /* ignore */ }
    }
  }

  async resume(): Promise<void> {
    this.wantPlay = true;
    try {
      await this.audio.play();
    } catch (e) {
      this.playFailed(e);
    }
  }

  private playFailed(e: unknown): void {
    if (e instanceof DOMException && e.name === 'AbortError') return;
    this.st.lastError = `play: ${String(e)}`;
  }

  setMuted(muted: boolean): void {
    this.audio.muted = muted;
    this.st.muted = muted;
  }

  reset(): void {
    this.open();
  }

  stats(): AudioStats {
    return { ...this.st };
  }

  destroy(): void {
    window.clearInterval(this.trimTimer);
    this.close();
    this.audio.removeAttribute('src');
    this.audio.load();
  }
}

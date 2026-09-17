// 하드웨어 H.264 디코더로 그린다 (WebCodecs).
//
// 왜 이 파일이 생겼나: 2026-09-17 실차(Model Y 2026.26)에서 **차가 하드웨어 디코더를 가지고 있다**는
// 것이 확인됐다(report #67, `https://100-99-9-9.local-ip.sh:3443/diag.html`):
//
//     secure context true, VideoDecoder true
//     Baseline / prefer-hardware  → supported
//     High 4.0 / prefer-hardware  → supported
//
// 그때까지 차는 WASM 소프트 디코더(h264bsd)로 풀고 있었고, 그 때문에 두 가지가 묶여 있었다 —
// 폰 인코더가 **Baseline 에 갇혀** 있었고(같은 화질에 비트레이트를 더 쓴다), 차 CPU 가 해상도·fps 의
// 천장이었다. 이 경로는 그 둘을 한꺼번에 푼다.
//
// 대가는 하나: `VideoDecoder` 는 `[SecureContext]` 라 **https 오리진에서만** 존재한다. 그래서 이
// 렌더러는 secure context 에서만 고르고, 평문에서는 예전 h264(WASM) 경로가 그대로 남는다.
//
// 먹이는 방법: 폰이 보내는 조각은 moof+mdat 이고, mdat 안은 길이 접두사 샘플이다. avcC 를 그대로
// `description` 으로 주면(avc 포맷) **그 바이트를 변환 없이** 넣을 수 있다 — NAL 로 풀어 Annex-B 로
// 다시 감싸는 일은 WASM 디코더 때문에 하던 것이고 하드웨어 디코더에는 낭비다.
import type { MediaPacket } from '../protocol';
import { MediaType } from '../protocol';
import type { Renderer, RendererStats } from './types';
import { avcCBox, codecString, mdatBytes, parseAvcC, type AvcConfig } from '../h264/fmp4';

/**
 * 이 브라우저에서 쓸 수 있는가. `VideoDecoder` 가 있다는 것은 곧 secure context 라는 뜻이기도 하다
 * (명세가 `[SecureContext]`), 그래서 평문에서는 저절로 false 가 된다.
 */
export function webcodecsSupported(): boolean {
  return typeof (globalThis as any).VideoDecoder === 'function' && typeof VideoFrame === 'function';
}

/** 디코더가 이만큼 밀리면 버린다 — h264(WASM) 경로와 같은 규칙, 같은 이유(지연이 자라는 것보다 낫다). */
const MAX_BACKLOG = 8;
/** 디코더를 이만큼 세워 보고도 안 되면 멈춘다 — 무한히 다시 세우느니 이유를 남기는 편이 낫다. */
const MAX_CONFIGURE_TRIES = 3;

export class WebCodecsRenderer implements Renderer {
  readonly name = 'webcodecs';
  readonly needsGesture = false;
  /** 프레임을 버렸을 때 폰에 키프레임을 부탁하는 길 — main.ts 가 건다. */
  onNeedKeyframe: (() => void) | null = null;
  /** 지연 측정(LatencyProbe)이 듣는 가운데 밝기. h264 경로와 같은 계약. */
  onLuma: ((luma: number, atMs: number) => void) | null = null;

  private decoder: VideoDecoder | null = null;
  private ctx: CanvasRenderingContext2D | null = null;
  private cfg: AvcConfig | null = null;
  private description: Uint8Array<ArrayBuffer> | null = null;
  private configured = false;
  private configuring = false;
  private configureFailures = 0;
  private needKey = false;
  /** 넣었는데 아직 그림으로 안 나온 것들의 도착 시각 — 지연과 적체를 여기서 읽는다. */
  private inFlight: number[] = [];
  private st: RendererStats = { framesDecoded: 0, fps: 0, latencyMs: 0, droppedFrames: 0, lastError: '', hardware: undefined };
  private times: number[] = [];
  /** 밝기를 읽을 때만 쓰는 1x1 캔버스 — 프레임마다 전체를 읽지 않는다. */
  private lumaCanvas: HTMLCanvasElement | null = null;

  constructor(private readonly canvas: HTMLCanvasElement) {}

  attach(): void {
    this.canvas.hidden = false;
    // desynchronized: 합성기를 한 박자 기다리지 않는다. 이 화면은 지연이 화질보다 중요하다.
    this.ctx = this.canvas.getContext('2d', { alpha: false, desynchronized: true }) as CanvasRenderingContext2D | null;
    if (!this.ctx) this.st.lastError = '2d 컨텍스트를 못 만듦';
  }

  /**
   * 디코더를 세운다 — 하드웨어를 **물어보고** 나서.
   *
   * `hardwareAcceleration: 'prefer-hardware'` 는 이름과 달리 취향이 아니다: 하드웨어가 없는 기기에서
   * 크로미엄은 그 설정을 **거절한다**(`isConfigSupported` 가 supported:false, configure 는
   * "Unsupported configuration"). 그래서 먼저 물어보고, 없으면 `no-preference` 로 세운다 — 그래도
   * 소프트웨어 WebCodecs 는 WASM 보다 빠르고 High 프로파일을 읽는다. 무엇을 받았는지는 리포트에 남긴다
   * (`stats.hardware`) — 차에서 그 줄이 곧 "이 차가 하드웨어로 풀고 있다"는 증거다.
   */
  private async ensureDecoder(): Promise<void> {
    const cfg = this.cfg;
    const description = this.description;
    if (!cfg || !description || this.configuring) return;
    if (this.configureFailures >= MAX_CONFIGURE_TRIES) return;
    this.configuring = true;
    try {
      const base: VideoDecoderConfig = {
        codec: codecString(cfg),
        description,
        // 프레임을 모아 두지 말고 넣는 대로 내라. 이 화면의 전부다.
        optimizeForLatency: true,
      };
      let hardware = false;
      try {
        hardware = (await VideoDecoder.isConfigSupported({ ...base, hardwareAcceleration: 'prefer-hardware' })).supported === true;
      } catch { /* 물어보다 실패하면 그냥 안 쓰는 것으로 친다 */ }
      const decoder = new VideoDecoder({
        output: (frame) => this.draw(frame),
        error: (e) => this.onDecoderError(e),
      });
      decoder.configure(hardware ? { ...base, hardwareAcceleration: 'prefer-hardware' } : base);
      this.decoder = decoder;
      this.configured = true;
      this.st.hardware = hardware;
      // 새 디코더는 키프레임부터 시작해야 한다. GOP 가 10초라 부탁하는 편이 빠르다.
      this.needKey = true;
      this.onNeedKeyframe?.();
    } catch (e) {
      this.configureFailures++;
      this.st.lastError = `configure 실패: ${String(e)}`;
    } finally {
      this.configuring = false;
    }
  }

  /**
   * 디코더가 죽었다. 한 번 죽은 코덱은 되살릴 수 없으므로(state=closed) 새로 세우고 키프레임을 받는다.
   * 몇 번을 시도해도 안 되면 그때는 이유를 화면과 리포트에 남기고 멈춘다 — 조용히 검은 화면이 되는 것보다 낫다.
   */
  private onDecoderError(e: DOMException | Error): void {
    this.st.lastError = `디코더 오류: ${e.message}`;
    this.configured = false;
    this.closeDecoder();
    this.configureFailures++;
    if (this.configureFailures < MAX_CONFIGURE_TRIES) void this.ensureDecoder();
  }

  private closeDecoder(): void {
    const decoder = this.decoder;
    this.decoder = null;
    // 이미 닫힌 코덱에 close() 를 부르면 InvalidStateError 가 난다.
    if (decoder && decoder.state !== 'closed') {
      try { decoder.close(); } catch { /* 경합; 어차피 버릴 것이다 */ }
    }
  }

  private draw(frame: VideoFrame): void {
    try {
      const ctx = this.ctx;
      if (ctx) {
        if (this.canvas.width !== frame.displayWidth || this.canvas.height !== frame.displayHeight) {
          this.canvas.width = frame.displayWidth;
          this.canvas.height = frame.displayHeight;
        }
        ctx.drawImage(frame, 0, 0);
      }
      const pushedAt = this.inFlight.shift();
      if (pushedAt !== undefined) this.st.latencyMs = performance.now() - pushedAt;
      this.st.framesDecoded++;
      this.tick();
      if (this.onLuma) this.onLuma(this.centreLuma(frame), performance.now());
    } catch (e) {
      this.st.lastError = `그리기 실패: ${String(e)}`;
    } finally {
      // VideoFrame 은 GPU 자원을 쥐고 있다. 안 닫으면 디코더가 곧 멈춘다.
      frame.close();
    }
  }

  /**
   * 그림 가운데의 평균 밝기. 지연 측정이 "화면이 뒤집혔다"를 이걸로 본다(폰의 측정 액티비티가 터치마다
   * 검정↔흰색을 오간다). 32x32 로 줄여 한 번 읽을 뿐이라 비용은 없다시피 하다.
   */
  private centreLuma(frame: VideoFrame): number {
    let c = this.lumaCanvas;
    if (!c) { c = document.createElement('canvas'); c.width = 1; c.height = 1; this.lumaCanvas = c; }
    const ctx = c.getContext('2d', { willReadFrequently: true });
    if (!ctx) return 0;
    const half = Math.min(32, frame.displayWidth, frame.displayHeight);
    ctx.drawImage(frame, (frame.displayWidth - half) / 2, (frame.displayHeight - half) / 2, half, half, 0, 0, 1, 1);
    const [r, g, b] = ctx.getImageData(0, 0, 1, 1).data;
    return 0.299 * (r ?? 0) + 0.587 * (g ?? 0) + 0.114 * (b ?? 0);
  }

  private tick(): void {
    const now = performance.now();
    this.times.push(now);
    while (this.times.length && now - this.times[0]! > 1000) this.times.shift();
    this.st.fps = this.times.length;
  }

  push(packet: MediaPacket): void {
    if (packet.type === MediaType.Init) {
      const cfg = parseAvcC(packet.payload);
      const description = avcCBox(packet.payload);
      if (!cfg || !description) { this.st.lastError = 'init 세그먼트에서 avcC 를 못 읽음'; return; }
      // 인코더가 다시 시작하면 새 init 이 온다: 디코더도 새로 만든다(해상도·프로파일이 바뀌었을 수 있다).
      this.cfg = cfg;
      this.description = description;
      this.reset();
      this.closeDecoder();
      this.configured = false;
      this.configureFailures = 0; // 새 스트림이다: 예전 실패로 미리 포기하지 않는다
      void this.ensureDecoder();
      return;
    }
    if (!this.configured) return;
    const decoder = this.decoder;
    if (!decoder || decoder.state !== 'configured') return;

    const key = packet.type === MediaType.Key;
    if (key) {
      this.needKey = false;
    } else if (this.needKey || this.inFlight.length > MAX_BACKLOG) {
      // 밀렸다: 키프레임까지 버리고 폰에 하나 달라고 한다. 깨진 참조를 디코더에 넣어 봐야 그림만 깨진다.
      if (!this.needKey) { this.needKey = true; this.onNeedKeyframe?.(); }
      this.st.droppedFrames++;
      return;
    }

    const data = mdatBytes(packet.payload);
    if (!data || !data.length) return;
    try {
      this.inFlight.push(performance.now());
      decoder.decode(new EncodedVideoChunk({ type: key ? 'key' : 'delta', timestamp: packet.ptsUs, data }));
    } catch (e) {
      this.inFlight.pop();
      this.st.lastError = `decode 실패: ${String(e)}`;
      this.needKey = true;
      this.onNeedKeyframe?.();
    }
  }

  /** 캔버스는 자동재생 제한을 받지 않는다 — 첫 터치 없이 이미 그려져 있다. */
  async resume(): Promise<void> { /* 할 일 없음 */ }

  reset(): void {
    this.inFlight = [];
    this.needKey = false;
  }

  stats(): RendererStats { return { ...this.st, backlog: this.inFlight.length }; }

  destroy(): void {
    this.closeDecoder();
    this.canvas.hidden = true;
  }
}

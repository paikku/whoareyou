// 소프트웨어 H.264 렌더러: WebSocket 으로 오는 fMP4 를 NAL 로 되돌려 WASM 디코더에 넣고,
// 나온 YUV 를 캔버스에 그린다.
//
// 왜 있나: 기어가 P 를 벗어나면 테슬라가 <video> 에 프레임 공급을 끊는다(실측 2026-09-14,
// docs/drive-check). 캔버스는 그때도 61fps 로 멀쩡했다. 그래서 <video> 를 아예 쓰지 않는 경로가
// 필요하고, 이것이 그 경로다. 덤으로 자동재생 제한도 안 받는다 — resume() 이 할 일이 없다.
//
// 전제: 스트림이 Baseline 이어야 한다. h264bsd 는 Baseline/Constrained Baseline 의 I·P 프레임만
// 읽는다. 폰의 인코더는 이제 Constrained Baseline 을 요청하고(H264Encoder.java), 실측 SPS 는
// avc1.42C020 이다. High(0x64)로 돌아오면 그림이 안 나오므로 그 자리에서 이유를 남긴다.
import type { MediaPacket } from '../protocol';
import { MediaType } from '../protocol';
import type { Renderer, RendererStats } from './types';
import { mdatNals, parseAvcC, toAnnexB, type AvcConfig } from '../h264/fmp4';
import { YuvGl } from '../h264/yuv-gl';

/** 이 경로를 쓸 수 있는 브라우저인가. 못 쓰면 <video> 로 물러난다(주차 중에는 그래도 보인다). */
export function h264Supported(): boolean {
  if (typeof Worker === 'undefined' || typeof WebAssembly === 'undefined') return false;
  try {
    return !!document.createElement('canvas').getContext('webgl2');
  } catch {
    return false;
  }
}

/**
 * 페이지가 받은 빌드 sha 로 URL 을 버전 지정한다 — 폰 서버는 `?v=<sha>` 가 맞는 자산을 1 년 immutable 로
 * 주므로, 175 KB 워커를 차에 탈 때마다 다시 받지 않는다. sha 가 안 채워진 곳(가짜 폰)에서는 그대로 둔다.
 */
function versioned(url: string): string {
  const v = document.querySelector('meta[name="carcast-build"]')?.getAttribute('content') ?? '';
  return v && v !== '__BUILD__' ? `${url}?v=${encodeURIComponent(v)}` : url;
}

/** 디코더가 이만큼 밀리면 키프레임이 아닌 것은 버린다. 지연이 무한정 자라는 것보다 낫다. */
const MAX_BACKLOG = 60;
/** 워커의 입력 버퍼가 1 MB 다(TinyH264Decoder). 그보다 큰 NAL 은 넣지 않는다. */
const MAX_NAL = 1024 * 1024;
/** decoderReady 를 기다리는 동안 쌓아 둘 NAL 의 상한 (4 초치쯤). */
const MAX_QUEUED = 240;

export class H264Renderer implements Renderer {
  readonly name = 'h264';
  readonly needsGesture = false;
  private worker: Worker | null = null;
  private gl: YuvGl | null = null;
  private cfg: AvcConfig | null = null;
  private ready = false;
  /** decoderReady 전에 온 NAL — 워커가 wasm 을 올리는 동안에도 첫 키프레임을 놓치지 않는다. */
  private queued: Uint8Array<ArrayBuffer>[] = [];
  /** 넣었는데 아직 그림으로 안 나온 접근 단위의 수. 지연과 적체를 여기서 읽는다. */
  private inFlight: number[] = [];
  private st: RendererStats = { framesDecoded: 0, fps: 0, latencyMs: 0, droppedFrames: 0, lastError: '' };
  private times: number[] = [];

  constructor(private readonly canvas: HTMLCanvasElement) {}

  attach(): void {
    this.canvas.hidden = false;
    try {
      this.gl = new YuvGl(this.canvas);
    } catch (e) {
      this.st.lastError = String(e);
      return;
    }
    this.startWorker();
  }

  private startWorker(): void {
    try {
      const worker = new Worker(versioned('h264-worker.js'));
      worker.onmessage = (ev: MessageEvent) => this.onWorkerMessage(ev.data);
      worker.onerror = (ev) => { this.st.lastError = `워커 오류: ${ev.message || ev.type}`; };
      this.worker = worker;
    } catch (e) {
      this.st.lastError = `워커를 못 띄움: ${e}`;
    }
  }

  private onWorkerMessage(msg: { type: string; width?: number; height?: number; data?: ArrayBuffer }): void {
    if (msg.type === 'decoderReady') {
      this.ready = true;
      for (const nal of this.queued) this.send(nal);
      this.queued = [];
      return;
    }
    if (msg.type !== 'pictureReady' || !msg.data || !msg.width || !msg.height) return;

    const pushedAt = this.inFlight.shift();
    if (pushedAt !== undefined) this.st.latencyMs = performance.now() - pushedAt;

    try {
      this.gl?.draw(new Uint8Array(msg.data), msg.width, msg.height);
    } catch (e) {
      this.st.lastError = String(e);
      return;
    }
    this.st.framesDecoded++;
    const now = performance.now();
    this.times.push(now);
    while (this.times.length && now - this.times[0]! > 1000) this.times.shift();
    this.st.fps = this.times.length;
  }

  push(packet: MediaPacket): void {
    if (packet.type === MediaType.Init) {
      const cfg = parseAvcC(packet.payload);
      if (!cfg) { this.st.lastError = 'init 세그먼트에서 avcC 를 못 읽음'; return; }
      this.cfg = cfg;
      if (cfg.profileIdc !== 0x42) {
        // 여기서 멈추지는 않는다 — 디코더가 어디까지 버티는지 보는 편이 낫고, 이유는 화면에 남는다.
        this.st.lastError = `Baseline 이 아님 (profile 0x${cfg.profileIdc.toString(16)}) — 소프트 디코더는 Baseline 만 읽는다`;
      }
      this.sendParameterSets();
      return;
    }

    const cfg = this.cfg;
    if (!cfg) return; // init 이 오기 전 조각은 어차피 못 푼다

    // 밀렸으면 키프레임만 통과시킨다. 다음 키프레임까지 그림이 깨지지만, 2 초 GOP 라 곧 복구되고
    // 지연이 계속 자라는 쪽보다 낫다. 이 수가 곧 "이 기기가 감당 못 한다"는 증거이기도 하다.
    if (this.inFlight.length > MAX_BACKLOG && packet.type !== MediaType.Key) {
      this.st.droppedFrames++;
      return;
    }
    if (packet.type === MediaType.Key) this.sendParameterSets();

    const nals = mdatNals(packet.payload, cfg.lengthSize);
    if (!nals.length) return;
    this.inFlight.push(performance.now());
    for (const nal of nals) {
      if (nal.length + 4 > MAX_NAL) { this.st.droppedFrames++; continue; }
      this.send(toAnnexB(nal));
    }
  }

  /** SPS·PPS 를 먼저 넣는다. 키프레임마다 다시 넣어도 해롭지 않고, 중간에 붙은 차를 살린다. */
  private sendParameterSets(): void {
    const cfg = this.cfg;
    if (!cfg) return;
    for (const s of cfg.sps) this.send(toAnnexB(s));
    for (const p of cfg.pps) this.send(toAnnexB(p));
  }

  private send(nal: Uint8Array<ArrayBuffer>): void {
    if (!this.ready) {
      // 워커가 끝내 안 뜨면(wasm 실패, 워커 차단) 여기 무한정 쌓인다 — 차에서 그건 메모리로 죽는
      // 길이다. 4 초치쯤에서 손을 뗀다. decoderReady 는 보통 100 ms 안에 온다.
      if (this.queued.length >= MAX_QUEUED) { this.st.droppedFrames++; return; }
      this.queued.push(nal);
      return;
    }
    const worker = this.worker;
    if (!worker) return;
    // 버퍼는 NAL 마다 새로 만든 것이라 넘겨도(transfer) 안전하다 — 복사 한 번을 아낀다.
    worker.postMessage(
      { type: 'decode', data: nal.buffer, offset: nal.byteOffset, length: nal.byteLength, renderStateId: 1 },
      [nal.buffer],
    );
  }

  /** 캔버스는 사용자 제스처 없이도 그려진다 — <video> 와 달리 자동재생 제한이 없다. */
  async resume(): Promise<void> { /* 할 일 없음 */ }

  reset(): void {
    // 소켓이 새로 붙으면 폰이 init 과 키프레임을 다시 보낸다. 적체만 비우고 디코더는 그대로 둔다.
    this.inFlight = [];
    this.queued = [];
  }

  stats(): RendererStats { return { ...this.st, backlog: this.inFlight.length }; }

  destroy(): void {
    this.worker?.postMessage({ type: 'release', renderStateId: 1 });
    this.worker?.terminate();
    this.worker = null;
    this.canvas.hidden = true;
  }
}

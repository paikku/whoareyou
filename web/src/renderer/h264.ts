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
//
// 그리기는 되도록 워커가 한다(h264/worker.ts, OffscreenCanvas): 메인 스레드는 터치와 UI 만 남고,
// 그림은 rAF 에 맞춰 고른 간격으로 나온다. 캔버스 제어권을 못 넘기는 브라우저에서는 워커가 YUV 를
// 넘겨 주고 여기서 그린다.
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

/**
 * 디코더가 이만큼 밀리면 버린다. 예전 60(2 초치)은 너무 컸다: 지연이 2 초까지 자란 뒤에야 버리기 시작했고,
 * 버린 뒤에는 다음 IDR 까지(GOP 2 초) 그림이 깨진 채였다. 지금은 8 장(30fps 에서 ¼ 초)에서 손을 떼고,
 * 버리는 순간 폰에 키프레임을 요청해(onNeedKeyframe) 깨진 구간을 한 왕복으로 끝낸다.
 */
const MAX_BACKLOG = 8;
/** 워커의 입력 버퍼가 1 MB 다(TinyH264Decoder). 그보다 큰 NAL 은 넣지 않는다. */
const MAX_NAL = 1024 * 1024;
/** decoderReady 를 기다리는 동안 쌓아 둘 NAL 의 상한 (4 초치쯤). */
const MAX_QUEUED = 240;

export class H264Renderer implements Renderer {
  readonly name = 'h264';
  readonly needsGesture = false;
  /** 프레임을 버렸거나 디코더가 막혔을 때 부른다 — 폰에 키프레임을 부탁하는 길. main.ts 가 건다. */
  onNeedKeyframe: (() => void) | null = null;
  private worker: Worker | null = null;
  private gl: YuvGl | null = null;
  /** 워커가 직접 그리는가(OffscreenCanvas). false 면 워커가 YUV 를 넘기고 여기서 그린다. */
  private offscreen = false;
  private cfg: AvcConfig | null = null;
  private ready = false;
  /** decoderReady 전에 온 NAL — 워커가 wasm 을 올리는 동안에도 첫 키프레임을 놓치지 않는다. */
  private queued: Uint8Array<ArrayBuffer>[] = [];
  /** 넣었는데 아직 그림으로 안 나온 접근 단위의 수. 지연과 적체를 여기서 읽는다. */
  private inFlight: number[] = [];
  /**
   * 한 번 버렸으면 다음 키프레임까지는 P 프레임을 전부 버린다. 버린 프레임을 참조하는 P 프레임을 디코더에
   * 넣어 봐야 깨진 그림이 나올 뿐이고, 그 시간에 적체만 더 는다.
   */
  private needKey = false;
  private st: RendererStats = { framesDecoded: 0, fps: 0, latencyMs: 0, droppedFrames: 0, lastError: '', skipped: 0 };
  private times: number[] = [];

  constructor(private readonly canvas: HTMLCanvasElement) {}

  attach(): void {
    this.canvas.hidden = false;
    this.startWorker();
  }

  private startWorker(): void {
    let worker: Worker;
    try {
      worker = new Worker(versioned('h264-worker.js'));
    } catch (e) {
      this.st.lastError = `워커를 못 띄움: ${e}`;
      return;
    }
    worker.onmessage = (ev: MessageEvent) => this.onWorkerMessage(ev.data);
    worker.onerror = (ev) => { this.st.lastError = `워커 오류: ${ev.message || ev.type}`; };
    this.worker = worker;
    // 캔버스 제어권을 워커로. 한 번 넘긴 캔버스는 이 스레드에서 다시 못 쓰므로, 못 넘기는 브라우저를
    // 먼저 가려 두 길 중 하나만 간다.
    if (typeof this.canvas.transferControlToOffscreen === 'function') {
      try {
        const off = this.canvas.transferControlToOffscreen();
        worker.postMessage({ type: 'canvas', canvas: off }, [off]);
        this.offscreen = true;
        return;
      } catch (e) {
        this.st.lastError = `OffscreenCanvas 실패, 메인에서 그림: ${e}`;
      }
    }
    try {
      this.gl = new YuvGl(this.canvas);
    } catch (e) {
      this.st.lastError = String(e);
    }
  }

  private onWorkerMessage(msg: { type: string; width?: number; height?: number; data?: ArrayBuffer; skipped?: number; message?: string }): void {
    if (msg.type === 'decoderReady') {
      this.ready = true;
      for (const nal of this.queued) this.send(nal);
      this.queued = [];
      return;
    }
    if (msg.type === 'error') {
      this.st.lastError = msg.message ?? '워커 오류';
      // 워커가 OffscreenCanvas 에 WebGL 을 못 열었다(워커 안 WebGL2 가 없는 브라우저). 넘긴 캔버스는 돌려받을 수
      // 없으므로 새 캔버스를 그 자리에 놓고 여기서 그린다 — 워커는 이제 YUV 를 넘겨 준다(post 모드).
      // 검은 화면으로 끝나는 것보다 낫고, 이 줄이 리포트에 남으면 그 차의 브라우저가 어디까지 되는지 안다.
      if (this.offscreen && (msg.message ?? '').includes('워커 WebGL 실패')) this.fallBackToMainThread();
      return;
    }
    if (msg.type === 'drawn') {
      this.st.skipped = msg.skipped ?? 0;
      this.tick();
      return;
    }
    if (msg.type !== 'pictureReady' || !msg.width || !msg.height) return;

    const pushedAt = this.inFlight.shift();
    if (pushedAt !== undefined) this.st.latencyMs = performance.now() - pushedAt;
    this.st.framesDecoded++;

    if (this.offscreen) return; // 그리기와 fps 는 워커의 drawn 이 말한다
    if (!msg.data) return;
    try {
      this.gl?.draw(new Uint8Array(msg.data), msg.width, msg.height);
    } catch (e) {
      this.st.lastError = String(e);
      return;
    }
    this.tick();
  }

  private fallBackToMainThread(): void {
    this.offscreen = false;
    try {
      const fresh = document.createElement('canvas');
      fresh.id = this.canvas.id;
      this.canvas.replaceWith(fresh); // #stage canvas 의 스타일이 그대로 붙는다
      this.gl = new YuvGl(fresh);
      this.st.lastError = `${this.st.lastError} → 메인 스레드에서 그림`;
    } catch (e) {
      this.st.lastError = `${this.st.lastError}; 메인도 실패: ${String(e)}`;
    }
  }

  /** 화면에 한 장이 올라갔다: fps 창에 적는다. */
  private tick(): void {
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

    if (packet.type === MediaType.Key) {
      this.needKey = false;
    } else if (this.needKey || this.inFlight.length > MAX_BACKLOG) {
      // 밀렸다: 키프레임까지 버리고, 폰에 지금 하나 달라고 한다. 지연이 계속 자라는 쪽보다 낫고,
      // 이 수가 곧 "이 기기가 감당 못 한다"는 증거이기도 하다.
      if (!this.needKey) { this.needKey = true; this.onNeedKeyframe?.(); }
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
      { type: 'decode', data: nal.buffer, offset: nal.byteOffset, length: nal.byteLength },
      [nal.buffer],
    );
  }

  /** 캔버스는 사용자 제스처 없이도 그려진다 — <video> 와 달리 자동재생 제한이 없다. */
  async resume(): Promise<void> { /* 할 일 없음 */ }

  reset(): void {
    // 소켓이 새로 붙으면 폰이 init 과 키프레임을 다시 보낸다. 적체만 비우고 디코더는 그대로 둔다.
    this.inFlight = [];
    this.queued = [];
    this.needKey = false;
  }

  stats(): RendererStats { return { ...this.st, backlog: this.inFlight.length, offscreen: this.offscreen }; }

  destroy(): void {
    this.worker?.postMessage({ type: 'release' });
    this.worker?.terminate();
    this.worker = null;
    this.canvas.hidden = true;
  }
}

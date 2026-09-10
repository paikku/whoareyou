// Reconnecting WebSocket. Tesla's browser fails roughly half of WS handshakes at times,
// so every channel retries with exponential backoff and never gives up on its own.

export interface WsOptions {
  binaryType?: BinaryType;
  onOpen?: () => void;
  onClose?: (ev: CloseEvent | Event) => void;
  onMessage: (data: ArrayBuffer | string) => void;
  minDelayMs?: number;
  maxDelayMs?: number;
}

export class ReconnectingWs {
  private ws: WebSocket | null = null;
  private stopped = false;
  private attempt = 0;
  private timer = 0;
  readonly stats = { connects: 0, failures: 0 };

  constructor(private readonly url: string, private readonly opts: WsOptions) {}

  start(): void {
    this.stopped = false;
    this.connect();
  }

  stop(): void {
    this.stopped = true;
    window.clearTimeout(this.timer);
    this.detach(this.ws);
    this.ws = null;
  }

  /** Close the current socket and open a fresh one now (the phone resends init + keyframe on attach). */
  restart(): void {
    this.stop();
    this.start();
  }

  // A socket we are done with must neither deliver messages nor schedule a retry: in the car a
  // restart() per stall left the old socket's onclose re-connecting, one extra stream per recovery.
  private detach(ws: WebSocket | null): void {
    if (!ws) return;
    ws.onopen = null; ws.onmessage = null; ws.onerror = null; ws.onclose = null;
    try { ws.close(); } catch { /* already closed */ }
  }

  get open(): boolean {
    return this.ws?.readyState === WebSocket.OPEN;
  }

  send(data: ArrayBuffer | string): boolean {
    if (!this.open) return false;
    this.ws!.send(data);
    return true;
  }

  private connect(): void {
    if (this.stopped) return;
    let ws: WebSocket;
    try {
      ws = new WebSocket(this.url);
    } catch (e) {
      this.scheduleRetry();
      return;
    }
    ws.binaryType = this.opts.binaryType ?? 'arraybuffer';
    let opened = false;
    ws.onopen = () => {
      opened = true;
      this.attempt = 0;
      this.stats.connects++;
      this.opts.onOpen?.();
    };
    ws.onmessage = (ev) => this.opts.onMessage(ev.data);
    ws.onerror = () => { /* onclose follows */ };
    ws.onclose = (ev) => {
      if (this.ws !== ws) return; // superseded by a restart(); nothing to retry
      if (!opened) this.stats.failures++;
      this.opts.onClose?.(ev);
      this.ws = null;
      this.scheduleRetry();
    };
    this.ws = ws;
  }

  private scheduleRetry(): void {
    if (this.stopped) return;
    const min = this.opts.minDelayMs ?? 250;
    const max = this.opts.maxDelayMs ?? 2000;
    const delay = Math.min(max, min * 2 ** Math.min(this.attempt, 6)) * (0.5 + Math.random());
    this.attempt++;
    this.timer = window.setTimeout(() => this.connect(), delay);
  }
}

export function wsUrl(path: string): string {
  const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
  return `${proto}//${location.host}${path}`;
}

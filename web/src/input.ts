// Pointer events on the stage -> normalised touch packets. Coordinates are relative to the
// rendered video area (object-fit: contain letterboxing is taken into account).
import { TouchAction, encodeTouch, encodeTouchBatch, type TouchSample } from './protocol';

export interface InputSink {
  send(data: ArrayBuffer): boolean;
}

/** `performance.now()`-based event time as the u32 ms the packets carry. */
const clock = (e: PointerEvent): number => Math.round(e.timeStamp) >>> 0;

export class TouchInput {
  private readonly ids = new Map<number, number>(); // pointerId -> slot 0..9
  private videoAspect = 16 / 9;
  /** MOVE runs that went out as one batch packet, and the samples they carried (the tests and 💾 read these). */
  readonly stats = { batches: 0, samples: 0 };

  constructor(private readonly stage: HTMLElement, private readonly sink: InputSink) {
    stage.addEventListener('pointerdown', this.onDown);
    stage.addEventListener('pointermove', this.onMove);
    stage.addEventListener('pointerup', this.onUp);
    stage.addEventListener('pointercancel', this.onCancel);
    // Losing capture (another element grabs the pointer, the tab is hidden mid-drag) is a cancel too:
    // without this the finger stays down on the phone until the next touch.
    stage.addEventListener('lostpointercapture', this.onLostCapture);
    stage.addEventListener('contextmenu', (e) => e.preventDefault());
  }

  /**
   * Let go of every finger. The phone cancels its own side when the control socket dies
   * (InputInjector.cancelAll); this keeps our slot bookkeeping from leaking across a reconnect, so the
   * next touch starts at slot 0 instead of piling up phantom fingers.
   */
  cancelAll(send = true): void {
    for (const slot of this.ids.values()) {
      if (send) this.sink.send(encodeTouch(TouchAction.Cancel, slot, 0, 0, 0));
    }
    this.ids.clear();
  }

  /** Fingers this client believes are down (the tests read it). */
  get activePointers(): number {
    return this.ids.size;
  }

  setVideoSize(w: number, h: number): void {
    if (w > 0 && h > 0) this.videoAspect = w / h;
  }

  /**
   * Maps a client point to normalised video coordinates. `inside` says whether the point was on the
   * picture; the coordinates are clamped to it either way, so a finger that slides off the edge keeps
   * dragging along the edge (what the phone's own screen does) instead of freezing until it comes back.
   */
  normalise(clientX: number, clientY: number): { x: number; y: number; inside: boolean } {
    const r = this.stage.getBoundingClientRect();
    const stageAspect = r.width / r.height;
    let vw = r.width, vh = r.height, ox = 0, oy = 0;
    if (stageAspect > this.videoAspect) {
      vw = r.height * this.videoAspect;
      ox = (r.width - vw) / 2;
    } else {
      vh = r.width / this.videoAspect;
      oy = (r.height - vh) / 2;
    }
    const x = (clientX - r.left - ox) / vw;
    const y = (clientY - r.top - oy) / vh;
    const inside = x >= 0 && x <= 1 && y >= 0 && y <= 1;
    return { x: Math.min(1, Math.max(0, x)), y: Math.min(1, Math.max(0, y)), inside };
  }

  private slot(pointerId: number, allocate: boolean): number {
    let s = this.ids.get(pointerId);
    if (s === undefined && allocate) {
      const used = new Set(this.ids.values());
      s = 0;
      while (used.has(s)) s++;
      this.ids.set(pointerId, s);
    }
    return s ?? -1;
  }

  private onDown = (e: PointerEvent) => {
    // 스테이지 위에 겹쳐 놓은 차 쪽 UI(상태 패널의 버튼 등)는 폰으로 보내지 않는다. 보내면 안 되기도 하지만,
    // 아래의 setPointerCapture 가 클릭을 통째로 삼켜 그 버튼이 눌리지 않는다.
    if ((e.target as Element | null)?.closest?.('[data-ui]')) return;
    const p = this.normalise(e.clientX, e.clientY);
    if (!p.inside) return; // a touch that starts on the letterbox is not a touch on the phone
    this.stage.setPointerCapture(e.pointerId);
    const s = this.slot(e.pointerId, true);
    this.sink.send(encodeTouch(TouchAction.Down, s, p.x, p.y, e.pressure || 1, clock(e)));
  };

  /**
   * The browser delivers one pointermove per frame and folds the touchscreen's samples in between into
   * it (getCoalescedEvents). Those samples are the finger's real path and timing, which is what the
   * phone's velocity tracker wants for a fling — so they all go, as one batch packet the phone turns into
   * one MotionEvent with history. A browser without coalesced events sends the one sample it has.
   */
  private onMove = (e: PointerEvent) => {
    const s = this.slot(e.pointerId, false);
    if (s < 0) return;
    const raw = typeof e.getCoalescedEvents === 'function' ? e.getCoalescedEvents() : [];
    const events = raw.length ? raw : [e];
    const samples: TouchSample[] = [];
    for (const ev of events) {
      const p = this.normalise(ev.clientX, ev.clientY);
      samples.push({ x: p.x, y: p.y, pressure: ev.pressure || 1, tMs: clock(ev) });
    }
    if (samples.length === 1) {
      const p = samples[0]!;
      this.sink.send(encodeTouch(TouchAction.Move, s, p.x, p.y, p.pressure, p.tMs));
      return;
    }
    this.stats.batches++;
    this.stats.samples += samples.length;
    this.sink.send(encodeTouchBatch(s, samples));
  };

  private onUp = (e: PointerEvent) => {
    const s = this.slot(e.pointerId, false);
    if (s < 0) return;
    const p = this.normalise(e.clientX, e.clientY);
    this.sink.send(encodeTouch(TouchAction.Up, s, p.x, p.y, 0, clock(e)));
    this.ids.delete(e.pointerId);
  };

  private onLostCapture = (e: PointerEvent) => {
    const s = this.slot(e.pointerId, false);
    if (s < 0) return;
    this.sink.send(encodeTouch(TouchAction.Cancel, s, 0, 0, 0, clock(e)));
    this.ids.delete(e.pointerId);
  };

  private onCancel = (e: PointerEvent) => {
    const s = this.slot(e.pointerId, false);
    if (s < 0) return;
    this.sink.send(encodeTouch(TouchAction.Cancel, s, 0, 0, 0, clock(e)));
    this.ids.delete(e.pointerId);
  };
}

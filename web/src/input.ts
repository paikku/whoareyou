// Pointer events on the stage -> normalised touch packets. Coordinates are relative to the
// rendered video area (object-fit: contain letterboxing is taken into account).
import { TouchAction, encodeTouch } from './protocol';

export interface InputSink {
  send(data: ArrayBuffer): boolean;
}

export class TouchInput {
  private readonly ids = new Map<number, number>(); // pointerId -> slot 0..9
  private videoAspect = 16 / 9;

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

  /** Maps a client point to normalised video coordinates, or null when outside the picture. */
  normalise(clientX: number, clientY: number): { x: number; y: number } | null {
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
    if (x < 0 || x > 1 || y < 0 || y > 1) return null;
    return { x, y };
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
    if (!p) return;
    this.stage.setPointerCapture(e.pointerId);
    const s = this.slot(e.pointerId, true);
    this.sink.send(encodeTouch(TouchAction.Down, s, p.x, p.y, e.pressure || 1));
  };

  private onMove = (e: PointerEvent) => {
    const s = this.slot(e.pointerId, false);
    if (s < 0) return;
    const p = this.normalise(e.clientX, e.clientY);
    if (!p) return;
    this.sink.send(encodeTouch(TouchAction.Move, s, p.x, p.y, e.pressure || 1));
  };

  private onUp = (e: PointerEvent) => {
    const s = this.slot(e.pointerId, false);
    if (s < 0) return;
    const p = this.normalise(e.clientX, e.clientY) ?? { x: 0, y: 0 };
    this.sink.send(encodeTouch(TouchAction.Up, s, p.x, p.y, 0));
    this.ids.delete(e.pointerId);
  };

  private onLostCapture = (e: PointerEvent) => {
    const s = this.slot(e.pointerId, false);
    if (s < 0) return;
    this.sink.send(encodeTouch(TouchAction.Cancel, s, 0, 0, 0));
    this.ids.delete(e.pointerId);
  };

  private onCancel = (e: PointerEvent) => {
    const s = this.slot(e.pointerId, false);
    if (s < 0) return;
    this.sink.send(encodeTouch(TouchAction.Cancel, s, 0, 0, 0));
    this.ids.delete(e.pointerId);
  };
}

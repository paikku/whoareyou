import type { Page } from '@playwright/test';

export interface ClientStats {
  renderer: string;
  /** 화면에 떠 있는 상태 패널: '' | 'app-on-phone' | 'no-app' | 'no-phone' */
  state: string;
  framesDecoded: number;
  fps: number;
  latencyMs: number;
  droppedFrames: number;
  lastError: string;
  packets: number;
  idleMs: number;
  recoveries: number;
  appOnPhone: boolean;
  videoWs: { connects: number; failures: number; open: boolean };
  controlWs: { connects: number; failures: number; open: boolean };
  started: boolean;
  /** 컨트롤 소켓 왕복(ms); 아직 한 번도 못 쟀으면 -1. */
  rttMs: number;
  keyframeRequests: number;
  touch: { batches: number; samples: number };
  skipped?: number;
  offscreen?: boolean;
}

export const stats = (page: Page) => page.evaluate(() => (window as any).__carcast.stats() as ClientStats);

export async function statusOf(page: Page): Promise<any> {
  return page.evaluate(async () => (await fetch('/api/status')).json());
}

/**
 * First "touch" on the stage: unlocks autoplay the same way a driver's first tap does.
 *
 * 기본 렌더러(h264)는 캔버스라 자동재생 제한을 받지 않아 **스스로 시작한다** — 그때는 누를 것이
 * 없고, 이미 걷힌 오버레이를 누르려 들면 보이기를 기다리다 타임아웃 난다(실제로 그렇게 깨졌다).
 *
 * `__carcast` 가 붙는 것은 자동 시작보다 뒤이므로(main.ts), 그것이 보이는 시점에는 이미 결판이
 * 나 있다 — 여기서 started 를 보는 데에 경합이 없다.
 */
export async function startPlayback(page: Page): Promise<void> {
  await page.waitForFunction(() => !!(window as any).__carcast, null, { timeout: 30_000 });
  if (await page.evaluate(() => (window as any).__carcast.stats().started === true)) return;
  await page.locator('#overlay').click({ position: { x: 100, y: 100 } });
}

export const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

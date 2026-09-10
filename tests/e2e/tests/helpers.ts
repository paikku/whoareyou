import type { Page } from '@playwright/test';

export interface ClientStats {
  renderer: string;
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
}

export const stats = (page: Page) => page.evaluate(() => (window as any).__carcast.stats() as ClientStats);

export async function statusOf(page: Page): Promise<any> {
  return page.evaluate(async () => (await fetch('/api/status')).json());
}

/** First "touch" on the stage: unlocks autoplay the same way a driver's first tap does. */
export async function startPlayback(page: Page): Promise<void> {
  await page.locator('#overlay').click({ position: { x: 100, y: 100 } });
}

export const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

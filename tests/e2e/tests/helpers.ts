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
}

export const stats = (page: Page) => page.evaluate(() => (window as any).__carcast.stats() as ClientStats);

export async function statusOf(page: Page): Promise<any> {
  return page.evaluate(async () => (await fetch('/api/status')).json());
}

/**
 * First "touch" on the stage: unlocks autoplay the same way a driver's first tap does.
 *
 * 기본 렌더러(h264)는 캔버스라 자동재생 제한을 받지 않아 스스로 시작한다 — 그때는 누를 것이 없다.
 * <video> 를 쓰는 세션에서만 실제로 한 번 누른다.
 */
export async function startPlayback(page: Page): Promise<void> {
  const overlay = page.locator('#overlay');
  if (await overlay.isHidden()) return;
  await overlay.click({ position: { x: 100, y: 100 } });
}

export const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

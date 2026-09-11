// 한 시점의 "양쪽 상태" 한 장. 폰 쪽은 /api/status, 차 쪽은 브라우저 클라이언트의 __carcast.stats().
// 생애주기 문제는 언제나 이 둘이 어긋나는 데서 난다 — 폰은 화면을 껐다고 하는데 차는 계속 프레임을 받고 있다든가,
// 앱은 폰으로 갔는데 차는 검은 화면을 "정상"으로 그리고 있다든가.
import type { Page } from '@playwright/test';

export interface Probe {
  at: number;
  /** 폰 쪽 (/api/status) */
  serverAlive: boolean;
  source: string | null;        // display | clip | none
  displayId: number | null;
  frames: number | null;        // 인코더가 낸 누적 프레임
  appDisplay: number | null;    // 띄운 앱의 task 가 있는 디스플레이
  appOnPhone: boolean | null;
  screenOn: boolean | null;     // 서버가 보는 폰 화면 전원
  injected: number | null;
  injectFailed: number | null;
  /** 차 쪽 (브라우저) */
  pageAlive: boolean;
  framesDecoded: number;
  fps: number;
  latencyMs: number;
  wsConnects: number;
  wsOpen: boolean;
  recoveries: number;
  idleMs: number;
  lastError: string;
}

export const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

export async function serverStatus(base: string): Promise<any | null> {
  try {
    const res = await fetch(`${base}/api/status`, { signal: AbortSignal.timeout(3000) });
    return res.ok ? await res.json() : null;
  } catch { return null; }
}

export async function clientStats(page: Page): Promise<any | null> {
  try {
    return await page.evaluate(() => (window as any).__carcast?.stats() ?? null);
  } catch { return null; } // 새로고침 중이거나 페이지가 죽었다
}

export async function probe(page: Page, base: string): Promise<Probe> {
  const [s, c] = await Promise.all([serverStatus(base), clientStats(page)]);
  return {
    at: Date.now(),
    serverAlive: !!s,
    source: s?.source ?? null,
    displayId: s?.displayId ?? null,
    frames: s?.frames ?? null,
    appDisplay: s?.appDisplay ?? null,
    appOnPhone: s?.appOnPhone ?? null,
    screenOn: s?.screenOn ?? null,
    injected: s?.injected ?? null,
    injectFailed: s?.injectFailed ?? null,
    pageAlive: !!c,
    framesDecoded: c?.framesDecoded ?? -1,
    fps: c?.fps ?? -1,
    latencyMs: c?.latencyMs ?? -1,
    wsConnects: c?.videoWs?.connects ?? -1,
    wsOpen: c?.videoWs?.open ?? false,
    recoveries: c?.recoveries ?? -1,
    idleMs: c?.idleMs ?? -1,
    lastError: c?.lastError ?? '',
  };
}

/** 서버와 차가 서로 다른 말을 하고 있는가. 문자열 하나로 요약해 표에 넣는다. */
export function disagreements(p: Probe): string[] {
  const out: string[] = [];
  if (p.serverAlive && p.source !== 'display') out.push(`source=${p.source}`);
  if (p.serverAlive && p.appOnPhone && p.framesDecoded >= 0 && p.fps > 5) {
    out.push('앱은 폰에 있는데 차에 프레임이 흐른다');
  }
  if (p.lastError) out.push(`err=${p.lastError}`);
  if (p.injectFailed) out.push(`injectFailed=${p.injectFailed}`);
  return out;
}

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
  screenOn: boolean | null;     // 서버가 보는 폰 화면 전원 (📵 장부 + PowerManager)
  interactive: boolean | null;  // PowerManager: 기기가 깨어 있는가
  forcedOff: boolean | null;    // 우리가 📵 로 꺼 둔 상태인가
  panelState: string | null;    // dumpsys display 가 말하는 패널 상태
  powerReconciled: number | null; // 전원 버튼이 우리 장부와 어긋나 되돌린 횟수
  sleepRecoveries: number | null;  // 차가 보는 동안 잠든 폰을 되살린 횟수
  injected: number | null;
  injectFailed: number | null;
  pointersDown: number | null;  // 폰이 눌려 있다고 믿는 손가락 수
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
    interactive: s?.interactive ?? null,
    forcedOff: s?.forcedOff ?? null,
    panelState: s?.panelState ?? null,
    powerReconciled: s?.powerReconciled ?? null,
    sleepRecoveries: s?.sleepRecoveries ?? null,
    injected: s?.injected ?? null,
    injectFailed: s?.injectFailed ?? null,
    pointersDown: s?.pointersDown ?? null,
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
  // (전에 있던 "앱은 폰에 있는데 프레임이 흐른다" 검사는 뺐다: 측정 중에 하네스가 직접 화면을 움직이므로
  //  그 프레임은 우리가 만든 것이고, 더 이상 어긋남의 증거가 아니다.)
  if (p.pointersDown) out.push(`눌린 채 남은 손가락 ${p.pointersDown}개`);
  if (p.lastError) out.push(`err=${p.lastError}`);
  if (p.injectFailed) out.push(`injectFailed=${p.injectFailed}`);
  // 서버가 "화면 꺼짐"이라고 하는데 기기는 패널이 켜져 있다고 하면, 차의 📵 버튼이 뒤집힌 상태다.
  if (p.screenOn === false && p.panelState === 'ON') out.push('📵 장부와 패널이 어긋남');
  // 차가 보고 있는데 폰이 잠든 채로 남아 있으면 가상 디스플레이도 멈춘 상태다.
  if (p.interactive === false) out.push('폰이 잠들어 있음');
  return out;
}

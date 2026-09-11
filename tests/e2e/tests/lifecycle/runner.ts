// 동작 하나를 걸고, 차가 다시 정상으로 돌아오는 데 얼마나 걸리는지 잰다.
// "쾌적한가"는 결국 이 숫자다: 폰에서 무슨 일이 일어난 뒤 차 화면이 몇 초 만에 다시 살아나는가,
// 아니면 아예 안 살아나는가.
import type { Page } from '@playwright/test';
import { Action, Ctx } from './actions';
import { Probe, clientStats, disagreements, probe, sleep } from './probe';

/** 이만큼 프레임이 더 디코드되면 "돌아왔다"고 본다 (한 장은 우연일 수 있다). */
const RECOVERED_FRAMES = 5;
const RECOVER_TIMEOUT_MS = Number(process.env.RECOVER_TIMEOUT_MS ?? 25_000);

export interface Step {
  action: string;
  title: string;
  side: 'phone' | 'car';
  before: Probe;
  after: Probe;
  /** 동작 뒤 다시 프레임이 흐르기까지 걸린 시간. null 이면 시간 안에 돌아오지 않았다. */
  recoveryMs: number | null;
  /** 동작 직후 곧바로 흐르고 있었다 (끊김 없음) */
  uninterrupted: boolean;
  reconnects: number;
  notes: string[];
}

export async function applyAndMeasure(ctx: Ctx, action: Action): Promise<Step> {
  const { page, base } = ctx;
  const before = await probe(page, base);

  await action.run(ctx);

  // 동작 직후를 기준점으로 삼는다. 새로고침처럼 카운터가 0으로 돌아가는 동작이 있어서
  // before 가 아니라 여기서 다시 읽어야 한다.
  const t0 = Date.now();
  const startFrames = (await clientStats(page))?.framesDecoded ?? 0;
  let recoveryMs: number | null = null;
  let uninterrupted = false;
  for (;;) {
    const c = await clientStats(page);
    const decoded = c?.framesDecoded ?? -1;
    if (decoded >= startFrames + RECOVERED_FRAMES) {
      recoveryMs = Date.now() - t0;
      uninterrupted = recoveryMs < 1200; // 사람이 끊겼다고 느끼지 않을 만큼
      break;
    }
    if (Date.now() - t0 > RECOVER_TIMEOUT_MS) break;
    await sleep(250);
  }

  const after = await probe(page, base);
  const notes = disagreements(after);
  if (!after.serverAlive) notes.unshift('서버가 죽었다');
  if (recoveryMs === null && !action.mayStopVideo) notes.unshift('영상이 돌아오지 않았다');

  return {
    action: action.id,
    title: action.title,
    side: action.side,
    before,
    after,
    recoveryMs,
    uninterrupted,
    reconnects: Math.max(0, after.wsConnects - before.wsConnects),
    notes,
  };
}

/** 차 화면이 살아 있는 상태로 되돌린다 (다음 동작을 깨끗한 자리에서 시작하려고). */
export async function reset(ctx: Ctx): Promise<void> {
  const { page, base, post, app } = ctx;
  try { await post('/api/screen?on=1'); } catch { /* 이 기기에서 안 되면 그대로 */ }
  try { await post(`/api/app?name=${encodeURIComponent(app.pkg)}`); } catch { /* 아래에서 걸린다 */ }
  const s = await clientStats(page);
  if (!s?.started) {
    await page.locator('#overlay').click({ position: { x: 100, y: 100 } }).catch(() => {});
  }
  await sleep(1500);
  void base;
}

export function table(steps: Step[]): string {
  const rows = steps.map((s, i) => {
    const rec = s.recoveryMs === null ? '**안 돌아옴**' : s.uninterrupted ? '끊김 없음' : `${(s.recoveryMs / 1000).toFixed(1)}s`;
    const app = s.after.appOnPhone === null ? '-' : s.after.appOnPhone ? '📱폰' : `차(${s.after.appDisplay})`;
    const screen = s.after.screenOn === null ? '-' : s.after.screenOn ? '켜짐' : '꺼짐';
    return `| ${i + 1} | ${s.side === 'phone' ? '📱' : '🚗'} ${s.title} | ${rec} | ${s.reconnects} | ${app} | ${screen} | ${s.after.fps} | ${s.notes.join(', ') || '-'} |`;
  });
  return [
    '| # | 무슨 일이 일어났나 | 차 화면 복구 | 재접속 | 앱 위치 | 폰 화면 | fps | 어긋남 |',
    '|---|---|---|---|---|---|---|---|',
    ...rows,
  ].join('\n');
}

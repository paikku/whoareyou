// 스스로 돌아다니기: 폰 쪽·차 쪽 동작을 무작위 순서로 걸어 보고, 어긋나는 자리를 표로 남긴다.
// 시나리오는 "내가 아는 상황"만 본다. 실제로 곤란한 것은 아무도 안 짜 본 순서에서 나온다 —
// 📵 중에 전원 버튼을 누르고 폰이 앱을 가져간 뒤 차에서 새로고침, 같은 것.
//
//   EXPLORE_STEPS=40 BASE_URL=http://127.0.0.1:3333 CHROME_PATH=... \
//     npx playwright test tests/lifecycle-explore.spec.ts --project=model-y-2026.26
//
// 같은 순서를 다시 보려면 보고서에 찍힌 EXPLORE_SEED 를 그대로 넣는다.
import { existsSync, mkdirSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { expect, test } from '@playwright/test';
import { ALL_ACTIONS, Action, Ctx, adbAvailable, pickApp } from './lifecycle/actions';
import { clientStats, probe } from './lifecycle/probe';
import { Step, applyAndMeasure, openCarPage, reset, table } from './lifecycle/runner';

const BASE = process.env.BASE_URL ?? '';
const STEPS = Number(process.env.EXPLORE_STEPS ?? 0);
const SEED = Number(process.env.EXPLORE_SEED ?? Math.floor(Math.random() * 1e9));

test.skip(!STEPS, 'EXPLORE_STEPS 를 주면 돈다 (예: EXPLORE_STEPS=40)');
test.skip(!BASE, '진짜 서버가 있어야 한다 (tools/virtual-phone/vphone.sh up)');
test.skip(!adbAvailable(), '폰 쪽 조작에 adb 가 필요하다');

/** 씨앗을 받는 작은 난수기(mulberry32): 같은 씨앗이면 같은 순서가 나온다. */
function rng(seed: number): () => number {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

test('무작위 순서로 걸어 보고 어긋나는 자리를 남긴다', async ({ page }) => {
  test.setTimeout(STEPS * 45_000 + 120_000);
  const post = async (path: string) => {
    const res = await fetch(BASE + path, { method: 'POST', signal: AbortSignal.timeout(15_000) });
    return res.json();
  };
  const ctx: Ctx = { page, base: BASE, app: pickApp(), post };

  await openCarPage(page, BASE);
  await reset(ctx);

  // 이 기기에서 디스플레이 전원이 안 먹으면 그 동작들은 아무것도 바꾸지 않으니 목록에서 뺀다.
  const screenWorks = (await post('/api/screen?on=0'))?.ok === true;
  await post('/api/screen?on=1');
  const pool: Action[] = ALL_ACTIONS.filter((a) => screenWorks || !a.id.startsWith('car.screen'));

  const rand = rng(SEED);
  const steps: Step[] = [];
  let died: string | null = null;
  for (let i = 0; i < STEPS; i++) {
    const action = pool[Math.floor(rand() * pool.length)]!;
    const step = await applyAndMeasure(ctx, action);
    steps.push(step);
    if (!step.after.serverAlive) { died = action.title; break; }
    // 도즈에 들어갔으면 다음 단계 전에 빠져나온다 — 도즈에 갇힌 채로 걷는 것은 도즈 시험이지 탐색이 아니다.
    if (action.id === 'phone.doze') {
      steps.push(await applyAndMeasure(ctx, pool.find((a) => a.id === 'phone.undoze')!));
    }
  }

  // 마지막에 원래대로 돌아올 수 있어야 한다. 이것이 "실 사용에서 쾌적한가"의 최소 조건이다.
  // 마지막 확인: 프레임에 기대지 않는다(정지 화면에서는 물을 수 없는 질문이다). 걸어 본 뒤에도
  // 서버가 살아 있고, 차 페이지가 살아 있고, 영상 소켓이 다시 붙어 있는가 — 그것이 "되돌아올 수 있다"이다.
  await reset(ctx);
  const flowing = await openCarPage(page, BASE).catch(() => false);
  const end = await probe(page, BASE);
  const recovered = end.serverAlive && end.pageAlive && end.wsOpen;

  const worst = steps.filter((s) => s.recoveryMs === null).length;
  const slow = steps.filter((s) => s.recoveryMs !== null && s.recoveryMs > 5_000);
  const md = [
    `# 무작위 탐색 (seed ${SEED}, ${steps.length} 단계)`,
    '',
    `다시 보려면: \`EXPLORE_SEED=${SEED} EXPLORE_STEPS=${STEPS} …\``,
    '',
    `- 영상이 돌아오지 않은 단계: **${worst}**`,
    `- 5초 넘게 걸린 단계: **${slow.length}**${slow.length ? ` (${slow.map((s) => `${s.title} ${(s.recoveryMs! / 1000).toFixed(1)}s`).join(', ')})` : ''}`,
    `- 마지막에 원래대로 돌아왔나: **${recovered ? '예' : '아니오'}** (서버 ${end.serverAlive ? 'O' : 'X'} / 페이지 ${end.pageAlive ? 'O' : 'X'} / 영상 소켓 ${end.wsOpen ? 'O' : 'X'} / 프레임 ${flowing ? 'O' : 'X'})`,
    died ? `- ⚠️ 서버가 죽은 지점: **${died}**` : '',
    '',
    table(steps),
  ].join('\n');

  const path = resolve(process.cwd(), '../../out/lifecycle/explore.md');
  if (!existsSync(dirname(path))) mkdirSync(dirname(path), { recursive: true });
  writeFileSync(path, md);
  test.info().attach('explore.md', { body: md, contentType: 'text/markdown' });
  console.log(`\n${md}\n\n보고서: ${path}`);

  // 탐색은 "무엇이 깨지나 보는" 것이므로 느린 단계를 실패로 세지 않는다.
  // 단언하는 것은 두 가지뿐: 서버가 죽으면 안 되고, 마지막에는 되돌아올 수 있어야 한다.
  expect(died, `무작위 순서 도중 서버가 죽었다 (seed ${SEED})`).toBeNull();
  expect(recovered, `걸어 본 뒤 원래대로 돌아오지 못했다 (seed ${SEED}) — 보고서: ${path}`).toBe(true);
  void clientStats;
});

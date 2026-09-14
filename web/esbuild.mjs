// Bundles the car-side client into app/src/main/assets/web (served by the phone).
import * as esbuild from 'esbuild';
import { cpSync, mkdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const outdir = resolve(here, '../app/src/main/assets/web');
const watch = process.argv.includes('--watch');

mkdirSync(outdir, { recursive: true });
cpSync(resolve(here, 'public'), outdir, { recursive: true });

// Tesla runs Chromium 148 today, but older firmware shipped 88: keep ES2020 output, no top-level await.
const options = {
  entryPoints: {
    main: resolve(here, 'src/main.ts'),
    diag: resolve(here, 'src/diag.ts'),
    // 디코더는 워커에서 돈다 — 메인 스레드가 멈추면 터치도 상태줄도 같이 멈춘다.
    'h264-worker': resolve(here, 'src/h264/worker.ts'),
  },
  bundle: true,
  format: 'iife',
  target: ['chrome88'],
  outdir,
  sourcemap: false,
  minify: !watch,
  logLevel: 'info',
};

if (watch) {
  const ctx = await esbuild.context(options);
  await ctx.watch();
} else {
  await esbuild.build(options);
}

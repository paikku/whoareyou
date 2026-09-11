// 기기 검사용 공통 도구. 대상은 BASE_URL(기본 127.0.0.1:3333)에서 응답하는 **진짜 서버**다 —
// 가상 폰(tools/virtual-phone)이든 핫스팟에 붙은 실기기든 같은 코드로 검사한다.
// 가짜 폰(tools/fake-phone)은 대상이 아니다: 여기서 보려는 것이 바로 가짜 폰이 흉내 낼 수 없는 부분이다.
import { execFileSync } from 'node:child_process';
import { WebSocket } from 'ws';

export const BASE_URL = (process.env.BASE_URL ?? 'http://127.0.0.1:3333').replace(/\/$/, '');
const WS_BASE = BASE_URL.replace(/^http/, 'ws');
const ADB = process.env.ADB ?? (process.env.ANDROID_HOME ? `${process.env.ANDROID_HOME}/platform-tools/adb` : 'adb');

export const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

export async function api(path, init) {
  const res = await fetch(BASE_URL + path, init);
  if (!res.ok) throw new Error(`${init?.method ?? 'GET'} ${path} → ${res.status}`);
  return res.json();
}

export const status = () => api('/api/status');
export const startApp = (name, restart) =>
  api(`/api/app?name=${encodeURIComponent(name)}${restart ? `&restart=${restart}` : ''}`, { method: 'POST' });

/** 서버 로그(폰 앱의 "로그"와 같은 목록). 실패를 설명할 때 붙인다. */
export async function serverLog(lines = 30) {
  try { return (await api('/api/log')).slice(-lines).join('\n'); } catch { return '(로그를 읽지 못함)'; }
}

/** cond()가 참을 돌려줄 때까지 기다린다. 돌려준 값을 그대로 준다. */
export async function waitFor(cond, { timeoutMs = 15_000, stepMs = 500, what = 'condition' } = {}) {
  const deadline = Date.now() + timeoutMs;
  let last;
  for (;;) {
    last = await cond();
    if (last) return last;
    if (Date.now() > deadline) throw new Error(`${what}: ${timeoutMs}ms 안에 만족되지 않음`);
    await sleep(stepMs);
  }
}

// ---- adb ------------------------------------------------------------------------------------
// 폰 쪽에서 밖에 못 하는 조작(런처가 앱을 도로 가져가기 등)에만 쓴다. adb가 없으면 그 검사만 건너뛴다.

export function adb(...args) {
  return execFileSync(ADB, args, { encoding: 'utf8', timeout: 30_000 });
}
export const adbShell = (cmd) => adb('shell', cmd).trim();

export const adbAvailable = (() => {
  try { adb('shell', 'true'); return true; } catch { return false; }
})();

/** 이 기기에서 실제로 띄울 수 있는 런처 앱 하나. 에뮬레이터·폰 어디서나 하나는 있다. */
export function pickLauncherApp() {
  const candidates = [
    'com.android.settings', 'com.google.android.deskclock', 'com.android.deskclock',
    'com.google.android.calculator', 'com.android.calculator2', 'com.android.contacts',
  ];
  for (const pkg of candidates) {
    try {
      const out = adbShell(`cmd package resolve-activity --brief ${pkg}`);
      if (out.split('\n').pop().includes('/')) return pkg;
    } catch { /* 다음 후보 */ }
  }
  throw new Error('띄울 수 있는 런처 앱을 찾지 못했다');
}

// ---- WebSocket ------------------------------------------------------------------------------

/** /ws/video 를 ms 동안 듣고 [{type, ptsUs, size}] 를 돌려준다. type 0=INIT 1=FRAME 2=KEY. */
export function collectVideo(ms) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(`${WS_BASE}/ws/video`);
    const packets = [];
    const done = () => { try { ws.close(); } catch { /* 이미 닫힘 */ } resolve(packets); };
    const timer = setTimeout(done, ms);
    ws.on('message', (data) => {
      const buf = Buffer.isBuffer(data) ? data : Buffer.from(data);
      if (buf.length < 9) return;
      packets.push({ type: buf.readUInt8(0), ptsUs: Number(buf.readBigUInt64BE(1)), size: buf.length - 9, at: Date.now() });
    });
    ws.on('error', (e) => { clearTimeout(timer); reject(e); });
  });
}

/** /ws/control 에 붙어 터치·키를 보낸다. web/src/protocol.ts 와 같은 바이트. */
export async function control() {
  const ws = new WebSocket(`${WS_BASE}/ws/control`);
  await new Promise((resolve, reject) => { ws.once('open', resolve); ws.once('error', reject); });
  const u16 = (v) => Math.max(0, Math.min(65535, Math.round(v * 65535)));
  return {
    touch(action, nx, ny, pressure = 1) {
      const b = Buffer.alloc(9);
      b.writeUInt8(1, 0); b.writeUInt8(action, 1); b.writeUInt8(0, 2);
      b.writeUInt16BE(u16(nx), 3); b.writeUInt16BE(u16(ny), 5); b.writeUInt16BE(u16(pressure), 7);
      ws.send(b);
    },
    key(action, keycode) {
      const b = Buffer.alloc(4);
      b.writeUInt8(2, 0); b.writeUInt8(action, 1); b.writeUInt16BE(keycode, 2);
      ws.send(b);
    },
    async tap(nx, ny) { this.touch(0, nx, ny); await sleep(60); this.touch(1, nx, ny, 0); },
    close() { try { ws.close(); } catch { /* 이미 닫힘 */ } },
  };
}

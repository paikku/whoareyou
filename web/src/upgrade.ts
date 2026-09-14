// 개선 가능성 점검: "지금 것이 얼마나 좋은가"가 아니라 **"다음에 무엇을 쓸 수 있는가"**를 묻는다.
//
// 성능 추이(main.ts 의 perf)는 현재 경로가 버티는지만 말한다. 버티든 안 버티든 남는 질문은 따로다 —
// 해상도를 낮추는 것 말고, 이 차에서 **어떤 기술이 실제로 가능한가**. 하드웨어 디코더는 열리나,
// 디코더를 여러 코어에 나눌 수 있나, 그리기를 워커로 넘길 수 있나, 더 좋은 코덱이 열리나.
//
// 차에는 devtools 가 없어서 이런 것을 나중에 물어볼 수 없다. 그래서 한 번 나갈 때 전부 재고,
// 되는 것/우리가 뚫으면 되는 것/영영 안 되는 것으로 갈라 폰에 남긴다.
//
// 기능 검출로 끝내지 않는다. WebCodecs 는 **실제 720p 키프레임을 넣어 그림이 나오는지**까지 본다 —
// `typeof VideoDecoder` 가 함수라는 것과 이 차가 우리 스트림을 푼다는 것은 다른 말이다.
import { SAMPLE, b64ToBytes } from './probe/sample';

const $ = (id: string) => document.getElementById(id)!;
const logEl = $('log');
const log = (s: string) => { logEl.textContent += `${new Date().toISOString().slice(11, 19)} ${s}\n`; };
window.addEventListener('error', (e) => log(`error: ${e.message}`));

/**
 * `yes`  — 이 차에서 된다. 지금 손대면 되는 것.
 * `open` — 차는 막고 있지 않은데 **우리 쪽 조건**(평문, 응답 헤더)이 막는다. 우리가 뚫을 수 있다.
 * `no`   — 이 차에 없다. 더 생각하지 않아도 되는 것.
 * `hidden` — 평문이라 가려져서 못 쟀다. "없다"로 읽으면 안 된다.
 */
type State = 'yes' | 'open' | 'no' | 'hidden';
interface Result { state: State; detail: string; todo?: string }
interface Probe {
  id: string;
  group: string;
  name: string;
  /** 이것이 되면 무엇이 좋아지나. 판정만 있고 이게 없으면 표를 읽을 수가 없다. */
  gain: string;
  run: () => Promise<Result>;
}

const secure = window.isSecureContext;
/** 평문에서 안 보이는 기능은 "없다"가 아니라 "모른다"다. 이걸 섞으면 판단을 통째로 그르친다. */
const hiddenByPlaintext = (what: string): Result => ({
  state: 'hidden',
  detail: `${what}: secure context 전용이다. 지금은 평문 http 라 있어도 안 보인다.`,
  todo: '이 페이지를 아무 https 주소에서 한 번 더 열면 갈린다 — 폰 없이 혼자 도는 페이지다.',
});

// ── 디코드: CPU 를 줄이는 길 ─────────────────────────────────────────────────
type DecoderCtor = new (init: { output: (f: { close: () => void; codedWidth?: number }) => void; error: (e: Error) => void }) => {
  configure: (c: unknown) => void;
  decode: (c: unknown) => void;
  flush: () => Promise<void>;
  close: () => void;
};

function webcodecs(): DecoderCtor | null {
  const c = (window as unknown as { VideoDecoder?: DecoderCtor }).VideoDecoder;
  return typeof c === 'function' ? c : null;
}

/** `isConfigSupported` 한 번. 코덱 표에서 여러 번 쓴다. */
async function codecSupported(codec: string, hw?: 'prefer-hardware'): Promise<boolean> {
  const V = webcodecs() as unknown as { isConfigSupported?: (c: unknown) => Promise<{ supported?: boolean }> } | null;
  if (!V?.isConfigSupported) return false;
  try {
    const r = await V.isConfigSupported({ codec, codedWidth: 1280, codedHeight: 720, hardwareAcceleration: hw });
    return !!r.supported;
  } catch { return false; }
}

const probes: Probe[] = [
  {
    id: 'webcodecs',
    group: '디코드 — CPU 를 줄이는 길',
    name: 'WebCodecs VideoDecoder',
    gain: '차의 하드웨어 디코더를 쓴다. 지금처럼 WASM 으로 푸는 것과 달리 CPU 를 거의 안 쓰므로 발열·지연 문제의 근본 해결책이다.',
    async run() {
      const V = webcodecs();
      if (!V) return secure
        ? { state: 'no' as State, detail: 'secure context 인데도 VideoDecoder 가 없다 — 이 브라우저에는 WebCodecs 가 없다.' }
        : hiddenByPlaintext('VideoDecoder');
      // 있다고 끝이 아니다. 우리 스트림을 실제로 푸는지까지 본다.
      const t0 = performance.now();
      const ok = await decodeOnce(V);
      const ms = Math.round(performance.now() - t0);
      if (!ok.ok) return { state: 'no', detail: `VideoDecoder 는 있는데 우리 키프레임을 못 풀었다: ${ok.why}` };
      return {
        state: 'yes',
        detail: `실제로 ${SAMPLE.codec} 720p 키프레임을 풀었다 (${ms} ms, ${ok.w}×${ok.h}).`,
        todo: '렌더러를 WebCodecs 경로로 하나 더 만들고, 되면 그쪽을 기본으로. WASM 디코더는 못 쓰는 차를 위해 남긴다.',
      };
    },
  },
  {
    id: 'wasm-simd',
    group: '디코드 — CPU 를 줄이는 길',
    name: 'WebAssembly SIMD',
    gain: '지금 디코더(tinyh264)는 스칼라 코드다. SIMD 를 쓰는 빌드로 바꾸면 같은 CPU 로 여러 배 빨라진다. 차 쪽 변경이 필요 없는 가장 싼 개선이다.',
    async run() {
      // wasm-feature-detect 의 표준 탐지 모듈: v128 을 돌려주는 함수 하나.
      const simd = new Uint8Array([0, 97, 115, 109, 1, 0, 0, 0, 1, 5, 1, 96, 0, 1, 123, 3, 2, 1, 0, 10, 10, 1, 8, 0, 65, 0, 253, 15, 253, 98, 11]);
      const ok = WebAssembly.validate(simd);
      return ok
        ? { state: 'yes', detail: 'v128 명령이 있는 모듈을 통과시켰다.', todo: '디코더를 SIMD 빌드(openh264 등)로 교체. 폰·차 프로토콜은 그대로.' }
        : { state: 'no', detail: 'SIMD 모듈을 거부했다 — 스칼라 디코더로 남는다.' };
    },
  },
  {
    id: 'wasm-threads',
    group: '디코드 — CPU 를 줄이는 길',
    name: '멀티스레드 WASM (SharedArrayBuffer)',
    gain: `디코드를 여러 코어로 나눈다. 이 차는 코어가 ${navigator.hardwareConcurrency || '?'} 개로 보인다.`,
    async run() {
      let shared = false;
      try { shared = new WebAssembly.Memory({ initial: 1, maximum: 1, shared: true }).buffer instanceof SharedArrayBuffer; } catch { /* 아래에서 가른다 */ }
      const isolated = (window as unknown as { crossOriginIsolated?: boolean }).crossOriginIsolated === true;
      if (shared && isolated) return { state: 'yes', detail: '공유 메모리가 열려 있다.', todo: '디코더를 스레드 빌드로.' };
      if (typeof SharedArrayBuffer === 'undefined' && !secure) return hiddenByPlaintext('SharedArrayBuffer');
      // 여기가 핵심: 막고 있는 것이 차가 아니라 **우리 서버의 응답 헤더**다.
      return {
        state: 'open',
        detail: `SharedArrayBuffer=${typeof SharedArrayBuffer !== 'undefined'}, crossOriginIsolated=${isolated}. 브라우저가 아니라 격리 조건이 안 맞는다.`,
        todo: '폰 서버가 Cross-Origin-Opener-Policy: same-origin 과 Cross-Origin-Embedder-Policy: require-corp 를 보내면 열린다. 응답 헤더 두 줄이다.',
      };
    },
  },

  // ── 렌더: 메인 스레드를 비우는 길 ────────────────────────────────────────
  {
    id: 'offscreen',
    group: '렌더 — 메인 스레드를 비우는 길',
    name: 'OffscreenCanvas (워커에서 그리기)',
    gain: '디코드에 이어 그리기까지 워커로 넘긴다. 메인 스레드는 터치만 맡으니 화면이 밀려도 조작이 안 밀린다.',
    async run() {
      if (typeof OffscreenCanvas === 'undefined') return { state: 'no', detail: 'OffscreenCanvas 가 없다.' };
      const can = document.createElement('canvas');
      if (typeof can.transferControlToOffscreen !== 'function') return { state: 'no', detail: 'transferControlToOffscreen 이 없다.' };
      // 진짜로 컨텍스트가 잡히는지까지 본다 — 클래스만 있고 webgl2 가 안 잡히는 기기가 있다.
      const gl = new OffscreenCanvas(4, 4).getContext('webgl2');
      return gl
        ? { state: 'yes', detail: '워커로 넘길 캔버스에서 webgl2 컨텍스트가 잡힌다.', todo: '렌더러를 워커 안으로. 프로토콜 변경 없음.' }
        : { state: 'no', detail: 'OffscreenCanvas 는 있는데 webgl2 컨텍스트를 못 잡는다.' };
    },
  },
  {
    id: 'webgpu',
    group: '렌더 — 메인 스레드를 비우는 길',
    name: 'WebGPU',
    gain: 'YUV→RGB 변환을 더 싸게 하거나 compute 로 후처리한다. 지금은 WebGL2 셰이더로 한다.',
    async run() {
      const gpu = (navigator as unknown as { gpu?: { requestAdapter: () => Promise<unknown> } }).gpu;
      if (!gpu) return secure ? { state: 'no', detail: 'navigator.gpu 가 없다.' } : hiddenByPlaintext('WebGPU');
      try {
        const a = await gpu.requestAdapter();
        return a
          ? { state: 'yes', detail: '어댑터를 받았다.', todo: '이득이 확인되면 셰이더를 옮긴다. 지금 병목은 디코드 쪽이라 우선순위는 낮다.' }
          : { state: 'no', detail: 'navigator.gpu 는 있는데 어댑터가 없다(소프트웨어 폴백도 없음).' };
      } catch (e) { return { state: 'no', detail: `어댑터 요청 실패: ${e}` }; }
    },
  },

  // ── 전송: <video> 를 피하면서 하드웨어 디코더를 쓰는 다른 길 ─────────────
  {
    id: 'track-processor',
    group: '전송 — 다른 경로',
    name: 'WebRTC + MediaStreamTrackProcessor',
    gain: 'WebCodecs 가 막혔을 때의 우회로. WebRTC 로 받은 트랙에서 프레임만 뽑아 캔버스에 그린다 — 디코드는 차의 하드웨어가 하고 <video> 는 안 거치므로 D 에서도 산다.',
    async run() {
      const hasPc = typeof RTCPeerConnection === 'function';
      const hasProc = typeof (window as unknown as { MediaStreamTrackProcessor?: unknown }).MediaStreamTrackProcessor === 'function';
      if (hasPc && hasProc) return {
        state: 'yes',
        detail: 'RTCPeerConnection 과 MediaStreamTrackProcessor 가 둘 다 있다.',
        todo: '폰에 WebRTC 송신을 붙여야 한다 — 이 표에서 가장 큰 공사다. WebCodecs 가 열리면 그쪽이 훨씬 싸므로, 이건 WebCodecs 가 막혔을 때만 간다.',
      };
      if (!hasPc) return { state: 'no', detail: 'RTCPeerConnection 이 없다 — 이 길은 닫혔다.' };
      return secure
        ? { state: 'no', detail: 'MediaStreamTrackProcessor 가 없다. WebRTC 는 되지만 <video> 를 거쳐야 하고, 그건 D 에서 멈춘다.' }
        : hiddenByPlaintext('MediaStreamTrackProcessor');
    },
  },
  {
    id: 'webtransport',
    group: '전송 — 다른 경로',
    name: 'WebTransport (HTTP/3)',
    gain: '프레임 하나가 늦어도 뒤가 안 막힌다(head-of-line blocking 없음). 지금 WebSocket 은 TCP 라 한 번 밀리면 뒤가 다 밀린다.',
    async run() {
      const has = typeof (window as unknown as { WebTransport?: unknown }).WebTransport === 'function';
      if (has) return { state: 'yes', detail: 'WebTransport 가 있다.', todo: '폰이 HTTP/3 서버를 띄워야 한다. 지금 WebSocket 이 안 밀리는 한 순위는 낮다.' };
      return secure ? { state: 'no', detail: 'WebTransport 가 없다.' } : hiddenByPlaintext('WebTransport');
    },
  },

  // ── 코덱: 같은 화질에 더 적은 비트 ───────────────────────────────────────
  {
    id: 'codec-high',
    group: '코덱 — 같은 화질에 더 적은 비트',
    name: 'H.264 High profile',
    gain: '지금은 소프트 디코더가 Baseline 만 읽어서 폰 인코더를 Constrained Baseline 으로 묶어 뒀다. High 가 열리면 같은 비트레이트에 더 좋은 화질이 나온다.',
    async run() {
      if (!webcodecs()) return secure ? { state: 'no', detail: 'WebCodecs 가 없어 물어볼 수 없다.' } : hiddenByPlaintext('WebCodecs');
      const ok = await codecSupported('avc1.640020');
      return ok
        ? { state: 'yes', detail: 'avc1.640020 (High 3.2) 지원.', todo: 'WebCodecs 경로를 쓸 때는 H264Encoder 의 Baseline 강제를 푼다.' }
        : { state: 'no', detail: 'High profile 은 안 된다 — Baseline 강제를 유지한다.' };
    },
  },
  {
    id: 'codec-modern',
    group: '코덱 — 같은 화질에 더 적은 비트',
    name: 'H.265 / VP9 / AV1',
    gain: 'H.264 보다 같은 화질에 30~50% 적은 비트. 대역과 인코딩 부담이 함께 줄어든다.',
    async run() {
      if (!webcodecs()) return secure ? { state: 'no', detail: 'WebCodecs 가 없어 물어볼 수 없다.' } : hiddenByPlaintext('WebCodecs');
      const list: [string, string][] = [['hev1.1.6.L93.B0', 'H.265'], ['vp09.00.10.08', 'VP9'], ['av01.0.04M.08', 'AV1']];
      const got: string[] = [];
      for (const [codec, label] of list) if (await codecSupported(codec)) got.push(label);
      return got.length
        ? { state: 'yes', detail: `${got.join(', ')} 지원.`, todo: `폰의 MediaCodec 에도 같은 인코더가 있어야 한다 — /api/status 의 codec 과 맞춰 본다.` }
        : { state: 'no', detail: '셋 다 안 된다. H.264 로 남는다.' };
    },
  },

  // ── 안정성 ──────────────────────────────────────────────────────────────
  {
    id: 'wakelock',
    group: '안정성',
    name: 'Screen Wake Lock',
    gain: '차 화면이 스스로 꺼지는 것을 막는다. 지금은 차가 화면을 끄면 그대로 끊긴다.',
    async run() {
      const has = 'wakeLock' in navigator;
      if (has) return { state: 'yes', detail: 'navigator.wakeLock 이 있다.', todo: '재생이 시작되면 잠금을 잡고, 놓치면 다시 잡는다. 몇 줄이다.' };
      return secure ? { state: 'no', detail: 'navigator.wakeLock 이 없다.' } : hiddenByPlaintext('Wake Lock');
    },
  },
  {
    id: 'serviceworker',
    group: '안정성',
    name: 'Service Worker',
    gain: '페이지를 껍데기째 캐시해 두면 폰이 잠깐 끊겨도 흰 화면 대신 재접속 화면이 뜬다.',
    async run() {
      const has = 'serviceWorker' in navigator;
      if (has) return { state: 'yes', detail: 'serviceWorker 가 있다.', todo: '껍데기 캐시 + 재접속 화면.' };
      return secure ? { state: 'no', detail: 'serviceWorker 가 없다.' } : hiddenByPlaintext('Service Worker');
    },
  },
  {
    id: 'storage',
    group: '안정성',
    name: 'IndexedDB (기록 보존)',
    gain: '지금은 새로고침 한 번에 그 세션의 기록이 다 날아간다. 남겨 두면 차에서 문제가 난 뒤에도 무엇이 있었는지 읽을 수 있다.',
    async run() {
      if (typeof indexedDB === 'undefined') return { state: 'no', detail: 'indexedDB 가 없다.' };
      const ok = await new Promise<boolean>((res) => {
        let done = false;
        const fin = (v: boolean) => { if (!done) { done = true; res(v); } };
        try {
          const req = indexedDB.open('carcast-probe', 1);
          req.onsuccess = () => { req.result.close(); indexedDB.deleteDatabase('carcast-probe'); fin(true); };
          req.onerror = () => fin(false);
          setTimeout(() => fin(false), 3000);
        } catch { fin(false); }
      });
      return ok
        ? { state: 'yes', detail: '열고 지우는 데 성공했다.', todo: '이벤트 로그와 perf 표본을 여기에 남긴다. 차 밖에서 읽는 길이 하나 더 생긴다.' }
        : { state: 'no', detail: '열지 못했다(사생활 보호 모드이거나 저장이 막혀 있다).' };
    },
  },
];

/** 진짜로 한 장 풀어 본다. 이 표에서 유일하게 "말고 실제로" 확인하는 항목이다. */
function decodeOnce(V: DecoderCtor): Promise<{ ok: boolean; why: string; w: number; h: number }> {
  return new Promise((resolve) => {
    let settled = false;
    const done = (ok: boolean, why: string, w = 0, h = 0) => { if (!settled) { settled = true; resolve({ ok, why, w, h }); } };
    let dec: { configure: (c: unknown) => void; decode: (c: unknown) => void; close: () => void } | null = null;
    try {
      dec = new V({
        output: (frame) => {
          const f = frame as unknown as { codedWidth: number; codedHeight: number; close: () => void };
          done(true, '', f.codedWidth, f.codedHeight);
          f.close();
          try { dec?.close(); } catch { /* 이미 닫혔으면 그만 */ }
        },
        error: (e) => done(false, String(e && e.message ? e.message : e)),
      });
      dec.configure({
        codec: SAMPLE.codec,
        codedWidth: SAMPLE.width,
        codedHeight: SAMPLE.height,
        description: b64ToBytes(SAMPLE.descriptionB64),
        // 하드웨어가 있으면 그걸 쓰되, 없다고 실패시키지는 않는다 — 우리가 묻는 것은 "풀리는가"다.
        optimizeForLatency: true,
      });
      const Chunk = (window as unknown as { EncodedVideoChunk?: new (i: unknown) => unknown }).EncodedVideoChunk;
      if (!Chunk) return done(false, 'EncodedVideoChunk 가 없다');
      dec.decode(new Chunk({ type: 'key', timestamp: 0, data: b64ToBytes(SAMPLE.keyframeB64) }));
    } catch (e) {
      return done(false, String(e));
    }
    // 끝내 아무것도 안 오면 "안 된다"로 본다. 차에서 무한정 기다릴 수는 없다.
    setTimeout(() => done(false, '5 초 안에 프레임이 안 나왔다'), 5000);
  });
}

// ── 그리기 ──────────────────────────────────────────────────────────────────
const LABEL: Record<State, string> = { yes: '○ 된다', open: '△ 뚫으면 된다', no: '✕ 안 된다', hidden: '? 가려짐' };
const CLASS: Record<State, string> = { yes: 'ok', open: 'warn', no: 'bad', hidden: 'warn' };

const results: Record<string, Result & { name: string; group: string; gain: string }> = {};

function render(): void {
  const host = $('probes');
  host.textContent = '';
  const groups = [...new Set(probes.map((p) => p.group))];
  for (const g of groups) {
    const h = document.createElement('h2');
    h.textContent = g;
    host.append(h);
    const table = document.createElement('table');
    table.className = 'probe';
    for (const p of probes.filter((x) => x.group === g)) {
      const r = results[p.id];
      const tr = document.createElement('tr');
      // 판정을 DOM 에 박아 둔다 — 테스트가 글자를 긁지 않고 물을 수 있고, 차에서 눈으로 볼 때와
      // 자동 검사가 보는 것이 같아진다.
      tr.dataset.probe = p.id;
      if (r) tr.dataset.state = r.state;
      const name = document.createElement('td');
      name.className = 'pname';
      name.textContent = p.name;
      const verdict = document.createElement('td');
      verdict.className = `pv ${r ? CLASS[r.state] : ''}`;
      verdict.textContent = r ? LABEL[r.state] : '…';
      const body = document.createElement('td');
      const gain = document.createElement('div');
      gain.className = 'gain';
      gain.textContent = p.gain;
      body.append(gain);
      if (r) {
        const d = document.createElement('div');
        d.className = 'detail';
        d.textContent = r.detail;
        body.append(d);
        if (r.todo) {
          const t = document.createElement('div');
          t.className = 'todo';
          t.textContent = `→ ${r.todo}`;
          body.append(t);
        }
      }
      tr.append(name, verdict, body);
      table.append(tr);
    }
    host.append(table);
  }
}

/** 표를 다 읽지 않아도 되게, 할 일만 추려 맨 위에 둔다. */
function verdict(): string {
  const by = (s: State) => probes.filter((p) => results[p.id]?.state === s).map((p) => p.name);
  const lines: string[] = [];
  const yes = by('yes'), open = by('open'), hidden = by('hidden'), no = by('no');
  if (yes.length) lines.push(`지금 쓸 수 있다: ${yes.join(', ')}`);
  if (open.length) lines.push(`우리가 뚫으면 된다: ${open.join(', ')}`);
  if (hidden.length) lines.push(`평문이라 못 쟀다(https 로 한 번 더): ${hidden.join(', ')}`);
  if (no.length) lines.push(`이 차에서는 안 된다: ${no.join(', ')}`);
  return lines.join(' | ');
}

async function main(): Promise<void> {
  if (!secure) {
    const b = $('banner');
    b.hidden = false;
    b.textContent = '평문 http 로 열려 있다 — 표의 절반은 여기서 답이 안 나온다. '
      + 'WebCodecs·SharedArrayBuffer·WebGPU·WebTransport·Wake Lock·Service Worker 는 secure context 전용이라 '
      + '있어도 안 보인다. 아래의 "가려짐"은 "없다"가 아니다. '
      + '이 페이지는 폰이 없어도 혼자 도니까, 같은 파일(web/public/upgrade-check.html)을 아무 https 정적 호스팅에 '
      + '올려 차에서 한 번 더 열면 나머지가 갈린다. 그때는 폰에 저장이 안 되므로 화면을 사진으로 남긴다.';
  }
  render();
  for (const p of probes) {
    log(`${p.name} …`);
    try {
      const r = await p.run();
      results[p.id] = { ...r, name: p.name, group: p.group, gain: p.gain };
      log(`  ${LABEL[r.state]} — ${r.detail}`);
    } catch (e) {
      results[p.id] = { state: 'no', detail: `점검 자체가 실패: ${e}`, name: p.name, group: p.group, gain: p.gain };
      log(`  점검 실패: ${e}`);
    }
    render();
  }

  const summary = verdict();
  $('summary').textContent = summary;

  // 차에서 사진으로 옮겨 적을 수는 없다. 폰에 붙여 두면 나중에 앱에서 꺼내 읽는다.
  const body = {
    version: 1,
    kind: 'upgrade',
    page: location.href,
    clientTime: new Date().toISOString(),
    env: {
      UA: navigator.userAgent,
      secureContext: secure,
      cores: navigator.hardwareConcurrency ?? 0,
      memGb: (navigator as unknown as { deviceMemory?: number }).deviceMemory ?? 0,
      viewport: `${innerWidth}x${innerHeight}`,
    },
    probes: results,
    summary,
    log: logEl.textContent ?? '',
  };
  try {
    const r = await (await fetch('/api/report', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body) })).json();
    $('report-result').textContent = r.ok ? `폰에 저장됨 #${r.id} — 앱의 "차 진단 결과"에서 읽는다` : `저장 실패 ${r.error ?? ''}`;
  } catch (e) {
    // https 로 열었으면 폰(평문)으로 POST 가 막힌다 — 그건 고장이 아니라 예상된 일이다.
    $('report-result').textContent = `폰에 저장 못 함 (${String(e)}). https 로 열었다면 정상이다 — 화면을 사진으로 남긴다.`;
  }
}

void main();

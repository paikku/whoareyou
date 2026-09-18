// Diagnostics page: the only observability we have inside the car (no devtools, no copy/paste).
// Everything measured here is also POSTed back to the phone (/api/report), so the car visit is
// "open the page, wait for 저장됨", and the numbers are read later from a laptop or the app.
import { parseMediaPacket } from './protocol';
import { autoPath } from './paths';

// 브라우저가 무엇을 읽을 수 있다고 답하는지의 기록. 우리가 MSE 로 그리지는 않지만(2026-09-18 에 뺐다)
// 펌웨어가 바뀔 때 "무엇이 생기고 없어졌나"를 보는 데는 여전히 값이 있다 — 표에만 남긴다.
const H264_MIME = 'video/mp4; codecs="avc1.42E01E"';
const AAC_MIME = 'audio/mp4; codecs="mp4a.40.2"';
const mseSupported = (mime: string = H264_MIME): boolean =>
  typeof MediaSource !== 'undefined' && MediaSource.isTypeSupported(mime);
import { wsUrl } from './transport/ws';

const $ = (id: string) => document.getElementById(id)!;
const logEl = $('log');
const log = (s: string) => { logEl.textContent += `${new Date().toISOString().slice(11, 19)} ${s}\n`; };
window.addEventListener('error', (e) => log(`error: ${e.message}`));

interface Report {
  version: number;
  page: string;
  clientTime: string;
  firmware: string;
  env: Record<string, string | boolean | number>;
  api: Record<string, boolean>;
  ws?: { ok: number; avg: number };
  /**
   * 본 화면이 실제로 쓸 경로(paths.ts 의 autoPath)로 5 초를 풀어 본 결과. `path` 가 무엇으로 쟀는지이고,
   * `state` 는 그 렌더러가 마지막에 말한 것(hardware/backlog/error) — 멈췄을 때 어디서 멈췄는지를 본다.
   */
  video?: { path: string; packets: number; frames: number; fps: number; latencyMs: number; error: string; state: string; packetTimes: string };
  /** Control group: the car must fail to reach the phone's real (private) addresses. */
  addresses?: Record<string, 'reachable' | 'blocked' | 'skipped'>;
  /**
   * The one question plain http cannot ask: does this browser have a hardware decoder we could drive?
   * `secure` is what the page found where it is now; on http `videoDecoder` is always false and means
   * "could not ask", which is why `isSecureContext` travels with it.
   */
  secure?: {
    isSecureContext: boolean;
    videoDecoder: boolean;
    /** codec + hardwareAcceleration → 'supported' | 'unsupported' | an error string. */
    configs: Record<string, string>;
    /** Where the same page can be reopened in a secure context (the phone's TLS listener), if there is one. */
    httpsUrl: string;
  };
  /**
   * WebRTC 가 이 브라우저에서 **도는가** — 평문에서도 되는 유일한 하드웨어 디코드 길이고(prior-art §5),
   * TCP/WebSocket 의 머리막힘(큰 IDR 하나가 뒤의 전부를 막는 것)을 벗어나는 유일한 전송이라 언젠가 쓸 수
   * 있는지를 미리 물어 둔다. 같은 페이지 안에서 pc1 → pc2 로 캔버스 영상을 보내 본다: ICE·DTLS·SRTP 가 이
   * 브라우저에서 성립하는지, 무슨 코덱으로 협상됐는지, 디코더가 무엇이었는지(`decoderImplementation` —
   * 하드웨어면 ExternalDecoder 류, 아니면 FFmpeg/libvpx/dav1d). 폰 쪽 스택은 아직 없다 — 이것은 가능성 조사다.
   */
  webrtc?: {
    present: boolean;
    /** RTCRtpReceiver.getCapabilities('video') 가 말한 코덱들(rtx·fec 제외). */
    codecs: string[];
    /** 'ok' | 'no frames' | 실패 이유. H.264 를 청한 첫 루프백의 결과(요약 줄에 실리는 것). */
    loopback: string;
    framesDecoded: number;
    /** 협상된 코덱(mimeType). */
    codec: string;
    decoder: string;
    powerEfficient: boolean | null;
    dataChannel: boolean;
    /** ordered:false, maxRetransmits:0 채널이 열렸는가 — 머리막힘 없는 전송의 최소 단위. */
    unreliableChannel: boolean;
    /** WebTransport(HTTP/3) 가 이 브라우저에 있는가. secure context 전용이라 평문에서는 늘 X 다. */
    webTransport: boolean;
    ms: number;
    /**
     * 코덱을 하나씩 청한 루프백들: 무엇으로 협상됐고 어느 디코더가 풀었나. 하드웨어 디코더가 있는 코덱이
     * 무엇인지는 이 줄들이 말한다(`decoder` 가 ExternalDecoder 류면 하드웨어).
     */
    perCodec: Record<string, { loopback: string; framesDecoded: number; codec: string; decoder: string; powerEfficient: boolean | null; ms: number }>;
  };
  /**
   * 이 링크에서 N 바이트가 걸리는 시간(`/api/blob`). IDR 한 장이 100~575 KB 이니 그 크기의 패킷이 몇 ms 인지를
   * 인코더 없이 직접 잰 것이다. 첫 요청은 TCP 슬로 스타트를 포함하므로 600 KB 는 두 번 잰다.
   */
  link?: Record<string, { ms: number; mbps: number } | string>;
  summary: string;
  log: string;
}

// Older firmware appended `Tesla/<version>` to the UA; 2026.26 (Model Y) sends a bare
// `Mozilla/5.0 (X11; Linux x86_64) ... Chrome/148.0.0.0 Safari/537.36`, so the token alone cannot
// tell the car from a laptop. Label by what the UA does say and let the reader judge.
function uaLabel(ua: string): string {
  const fw = /Tesla\/(\S+)/.exec(ua)?.[1];
  if (fw) return `Tesla ${fw}`;
  const platform = /\(([^)]*)\)/.exec(ua)?.[1]?.split(';').map((t) => t.trim()).filter(Boolean).slice(0, 2).join(' ') ?? 'unknown';
  const chrome = /Chrome\/(\d+)/.exec(ua)?.[1];
  return `${platform}${chrome ? ` Chrome/${chrome}` : ''} (no Tesla/ token)`;
}

const report: Report = {
  version: 1,
  page: location.href,
  clientTime: new Date().toISOString(),
  firmware: /Tesla\/(\S+)/.exec(navigator.userAgent)?.[1] ?? '',
  env: {},
  api: {},
  summary: '',
  log: '',
};

function row(table: HTMLElement, k: string, v: string | boolean, into?: Record<string, any>): void {
  const tr = document.createElement('tr');
  const td1 = document.createElement('td');
  const td2 = document.createElement('td');
  td1.textContent = k;
  if (typeof v === 'boolean') { td2.textContent = v ? 'O' : 'X'; td2.className = v ? 'ok' : 'bad'; }
  else td2.textContent = v;
  tr.append(td1, td2);
  table.append(tr);
  if (into) into[k] = v;
}

const env = $('env');
row(env, 'UA', navigator.userAgent, report.env);
row(env, 'viewport', `${innerWidth}x${innerHeight} (screen ${screen.width}x${screen.height})`, report.env);
row(env, 'devicePixelRatio', String(devicePixelRatio), report.env);
row(env, 'origin', location.origin, report.env);
row(env, 'secure context', isSecureContext, report.env);
row(env, 'language', navigator.language, report.env);
report.env.innerWidth = innerWidth; report.env.innerHeight = innerHeight;
report.env.screenWidth = screen.width; report.env.screenHeight = screen.height;
report.env.dpr = devicePixelRatio;

const api = $('api');
row(api, 'MediaSource', typeof MediaSource !== 'undefined', report.api);
row(api, `MSE ${H264_MIME}`, mseSupported(H264_MIME), report.api);
row(api, 'MSE avc1.640028 (High)', mseSupported('video/mp4; codecs="avc1.640028"'), report.api);
row(api, 'MSE hvc1 (H.265)', mseSupported('video/mp4; codecs="hvc1.1.6.L93.B0"'), report.api);
row(api, `MSE ${AAC_MIME}`, mseSupported(AAC_MIME), report.api);
row(api, 'WebCodecs VideoDecoder', typeof (window as any).VideoDecoder !== 'undefined', report.api);
row(api, 'AudioContext', typeof (window as any).AudioContext !== 'undefined', report.api);
row(api, 'RTCPeerConnection', typeof (window as any).RTCPeerConnection !== 'undefined', report.api);
row(api, 'requestVideoFrameCallback', 'requestVideoFrameCallback' in HTMLVideoElement.prototype, report.api);
row(api, 'Fullscreen API', typeof document.documentElement.requestFullscreen === 'function', report.api);
row(api, 'Pointer Events', typeof (window as any).PointerEvent !== 'undefined', report.api);
row(api, 'createImageBitmap', typeof createImageBitmap === 'function', report.api);

/**
 * WebCodecs, asked where it can actually answer.
 *
 * `VideoDecoder` is `[SecureContext]` in the WebCodecs IDL, so on `http://100.99.9.9:3333` it is absent no
 * matter what the browser can do — the X in the API table above says "not asked", not "not there". The phone
 * also serves the same pages over TLS with a self-signed certificate (`/api/status.httpsPort`); past the
 * car's warning that origin is secure, and then this table means something. What we want to learn there:
 * whether a decoder exists at all, and whether it will give us a **hardware** one — that is what would take
 * the car's software decoder (and the Baseline-only limit it puts on the phone's encoder) out of the picture.
 */
async function codecProbe(): Promise<void> {
  const table = $('codec');
  const note = $('codec-note');
  const VD = (window as any).VideoDecoder;
  const present = typeof VD === 'function';
  const secure: NonNullable<Report['secure']> = {
    isSecureContext,
    videoDecoder: present,
    configs: {},
    httpsUrl: '',
  };

  row(table, 'secure context', isSecureContext);
  row(table, 'VideoDecoder', present);

  if (present) {
    // 42C01F: exactly what the phone's encoder sends today (Constrained Baseline 3.1). 640028: High 4.0 —
    // if that one decodes in hardware, the encoder could stop being held down to Baseline for the sake of
    // the car's WASM decoder, which is worth a chunk of bitrate at the same picture.
    //
    // 그 다음 셋은 "H.264 말고 다른 코덱으로 갈 수 있나"의 차 쪽 절반이다(폰 쪽 절반은 /api/status.encoders).
    // HEVC Main 4.0 · AV1 Main 4.0 8-bit · VP9 profile 0 — 같은 화질에 비트를 30~40% 덜 쓰고, 그만큼 IDR 도
    // 작아진다(실차 #76 의 575 KB 가 이 프로젝트의 병목이었다). 크로미엄은 AV1 소프트 디코더(dav1d)를 늘 품고
    // 있어서 av01 의 no-preference 는 거의 항상 O 다 — 뜻이 있는 칸은 **prefer-hardware** 쪽이다.
    // 여기서 O 가 나와도 파이프라인은 아직 H.264 뿐이다(paths.ts 의 주석이 할 일 목록).
    // 1080p 줄은 사다리의 맨 위 칸(1080p60)이 이 디코더의 약속 안에 있는지다 — 720p 에서 O 인 것이 1080p 에서
    // 하드웨어를 못 받으면 그 칸은 소프트웨어로 풀리고 있었던 것이다.
    const CODECS: ReadonlyArray<readonly [string, string, number, number]> = [
      ['우리 스트림 (Baseline)', 'avc1.42C01F', 1280, 720],
      ['High 4.0', 'avc1.640028', 1280, 720],
      ['High 4.2 @1080p', 'avc1.64002A', 1920, 1080],
      ['HEVC Main 4.0', 'hvc1.1.6.L120.B0', 1280, 720],
      ['HEVC Main 4.1 @1080p', 'hvc1.1.6.L123.B0', 1920, 1080],
      ['AV1 Main 4.0', 'av01.0.08M.08', 1280, 720],
      ['AV1 Main 4.1 @1080p', 'av01.0.09M.08', 1920, 1080],
      ['VP9 profile 0', 'vp09.00.40.08', 1280, 720],
    ];
    for (const [label, codec, w, h] of CODECS) {
      for (const hw of ['no-preference', 'prefer-hardware'] as const) {
        const key = `${label} / ${hw}`;
        try {
          const res = await VD.isConfigSupported({
            codec, codedWidth: w, codedHeight: h, optimizeForLatency: true, hardwareAcceleration: hw,
          });
          secure.configs[key] = res?.supported ? 'supported' : 'unsupported';
          row(table, key, !!res?.supported);
        } catch (e) {
          secure.configs[key] = String(e);
          row(table, key, String(e));
        }
      }
    }
  }

  // Where to go to ask properly. The phone knows its own TLS port; if it has none, say so plainly rather
  // than leaving a reader to wonder whether the X above was an answer.
  if (!isSecureContext) {
    let httpsPort = 0;
    let trusted = false;
    let host = '';
    try {
      const st = await (await fetch('/api/status')).json();
      httpsPort = Number(st.httpsPort) || 0;
      trusted = st.tlsTrusted === true;
      host = typeof st.tlsHost === 'string' ? st.tlsHost : '';
    } catch { /* the note below still says what this X means */ }
    if (httpsPort) {
      // 신뢰받는 인증서가 있으면 그 이름으로 가야 한다 — 주소(100.99.9.9)로 가면 이름이 안 맞아 도로 경고다.
      // 그리고 이 차의 경고는 넘길 수가 없다(2026-09-17: 고급 버튼이 없는 NET::ERR_CERT_AUTHORITY_INVALID).
      secure.httpsUrl = `https://${trusted && host ? host : location.hostname}:${httpsPort}/diag.html`;
      note.innerHTML = `평문 http 라 <b>물어볼 수 없었습니다</b> — 위의 X 는 "없다"가 아니라 "못 물었다"입니다. ` +
        `<a class="link" href="${secure.httpsUrl}">${secure.httpsUrl}</a> 를 열어 주세요` +
        (trusted && host
          ? ' — 공개 CA 가 서명한 인증서라 <b>경고 없이</b> 열립니다. 안 열리면 그것은 차가 그 이름을 해석하지 못한다는 뜻입니다(DNS).'
          : ' (자체서명이라 경고가 뜹니다. 넘길 수 없으면 거기서 멈추고 그대로 기록하세요.)');
    } else {
      note.textContent = '평문 http 라 물어볼 수 없었고, 폰에 TLS listener 도 없습니다 (https_port=0). 위의 X 는 답이 아닙니다.';
    }
  } else if (!present) {
    note.textContent = 'secure context 인데도 VideoDecoder 가 없습니다 — 이 브라우저에는 WebCodecs 가 없다는 뜻입니다.';
  } else {
    note.textContent = 'secure context 에서 물었습니다. 이 표가 곧 답입니다.';
  }

  report.secure = secure;
  (window as any).__diag.secure = secure;
  log(`codec probe secure=${isSecureContext} videoDecoder=${present}`);
}

// WebSocket: 20 sequential handshakes, count successes. Tesla intermittently fails these.
async function wsProbe(): Promise<void> {
  const nEl = $('ws-n');
  const out = $('ws-result');
  let ok = 0;
  const times: number[] = [];
  for (let i = 0; i < 20; i++) {
    nEl.textContent = String(i + 1);
    const t0 = performance.now();
    const success = await new Promise<boolean>((resolve) => {
      let ws: WebSocket;
      try { ws = new WebSocket(wsUrl('/ws/control')); } catch { resolve(false); return; }
      const timer = setTimeout(() => { ws.close(); resolve(false); }, 3000);
      ws.onopen = () => { clearTimeout(timer); ws.close(); resolve(true); };
      ws.onerror = () => { clearTimeout(timer); resolve(false); };
    });
    if (success) { ok++; times.push(performance.now() - t0); }
  }
  const avg = times.length ? Math.round(times.reduce((a, b) => a + b, 0) / times.length) : 0;
  out.textContent = `${ok}/20 성공, 평균 ${avg}ms`;
  out.className = ok >= 18 ? 'ok' : ok > 0 ? '' : 'bad';
  log(`ws probe ${ok}/20 avg ${avg}ms`);
  report.ws = { ok, avg };
  (window as any).__diag.ws = report.ws;
}

// Video: connect once and report decode fps over 5 seconds.
// Nothing here may wait forever: in the car (Model Y 2026.26) `video.play()` never resolved — the
// element's play() promise only settles once the first frame is presented — and the page sat on
// "측정 중…" with nothing saved. Every await is bounded, and whatever we learned goes into the report.
const VIDEO_WS_OPEN_MS = 5000;
const VIDEO_PLAY_MS = 3000;
const VIDEO_MEASURE_MS = 5000;
const sleep = (ms: number) => new Promise<void>((res) => setTimeout(res, ms));
/** Resolves to true when `p` settles first, false when the timeout wins. */
const within = (p: Promise<unknown>, ms: number) => Promise.race([p.then(() => true, () => true), sleep(ms).then(() => false)]);

async function videoProbe(): Promise<void> {
  const out = $('video-result');
  // 본 화면과 같은 규칙으로 고른다: 이 페이지가 잰 숫자가 곧 본 화면이 낼 숫자여야 리포트가 뜻이 있다.
  // https 면 하드웨어 디코더, 평문이면 소프트 디코더 — 어느 쪽인지는 `path` 로 리포트에 남는다.
  const path = autoPath();
  const canvas = document.getElementById('gl') as HTMLCanvasElement;
  const r = path.make({ gl: canvas });
  const rendererState = (): string => {
    const st = r.stats();
    return `path=${path.id}${st.hardware !== undefined ? ` hardware=${st.hardware}` : ''}${st.backlog !== undefined ? ` backlog=${st.backlog}` : ''}`;
  };
  const empty = (error: string) => ({ path: path.id, packets: 0, frames: 0, fps: 0, latencyMs: 0, error, state: rendererState(), packetTimes: '' });
  if (!path.supported()) {
    out.textContent = `${path.label} 미지원`; out.className = 'bad';
    report.video = empty(`${path.id} unsupported`);
    (window as any).__diag.video = report.video;
    return;
  }
  r.attach(document.body);
  let packets = 0;
  // When each packet arrived (ms since the probe started), first few + last: "2 packets" from the
  // phone reads very differently when they came at 0 ms and 8000 ms than at 0 ms and 30 ms.
  const t0 = performance.now();
  const firstArrivals: number[] = [];
  let lastArrival = -1;
  const ws = new WebSocket(wsUrl('/ws/video'));
  ws.binaryType = 'arraybuffer';
  ws.onmessage = (ev) => {
    if (typeof ev.data === 'string') return;
    const p = parseMediaPacket(ev.data);
    if (!p) return;
    packets++;
    lastArrival = Math.round(performance.now() - t0);
    if (firstArrivals.length < 5) firstArrivals.push(lastArrival);
    r.push(p);
  };
  ws.onerror = () => log('video ws error');
  const opened = await within(new Promise<void>((resolve) => { ws.onopen = () => resolve(); ws.onclose = () => resolve(); }), VIDEO_WS_OPEN_MS);
  let error = '';
  if (!opened) {
    error = `video ws: no open/close in ${VIDEO_WS_OPEN_MS}ms`;
    log(error);
  } else if (ws.readyState !== WebSocket.OPEN) {
    error = 'video ws: closed before open';
    log(error);
  } else {
    // 캔버스 경로는 resume() 이 할 일이 없지만, 첫 제스처가 필요한 렌더러가 다시 생겨도 이 자리가
    // 그것을 기다린다 — 끝나지 않으면 그것도 하나의 결과이므로 재는 것은 그대로 한다.
    const resumed = await within(r.resume(), VIDEO_PLAY_MS);
    if (!resumed) log(`renderer resume() still pending after ${VIDEO_PLAY_MS}ms, measuring anyway`);
    await sleep(VIDEO_MEASURE_MS);
  }
  const s = r.stats();
  const state = rendererState();
  const packetTimes = packets ? `${firstArrivals.join(',')}${packets > 5 ? `…last=${lastArrival}` : ''}ms` : '';
  ws.close();
  if (!error) error = s.lastError;
  if (!error && s.framesDecoded === 0) error = packets ? 'no frames decoded' : 'no packets received';
  else if (!error && s.fps === 0) error = 'stalled: frames stopped before the end of the probe';
  out.textContent = `패킷 ${packets}, 디코드 ${s.framesDecoded}프레임, ${s.fps}fps, lag ${Math.round(s.latencyMs)}ms${error ? `, err: ${error}` : ''}`;
  out.className = s.framesDecoded > 0 && !error ? 'ok' : 'bad';
  log(`video probe packets=${packets} at ${packetTimes || '-'} frames=${s.framesDecoded} fps=${s.fps} ${state}${error ? ` err=${error}` : ''}`);
  report.video = { path: path.id, packets, frames: s.framesDecoded, fps: s.fps, latencyMs: Math.round(s.latencyMs), error, state, packetTimes };
  (window as any).__diag.video = report.video;
  r.destroy();
}

// Control group: the phone's real addresses (hotspot 10.x etc.) must be unreachable from the car,
// otherwise the tun address detour is unnecessary — and if a firmware update opens or closes them
// we want to know. Cross-origin, so use no-cors: an opaque response means "reachable", a network
// error means "blocked" (DNS/route/policy). Same-origin address = the one we came in on, skip it.
async function addressProbe(): Promise<void> {
  // https 페이지에서 http 주소로 fetch 하면 혼합 콘텐츠로 **브라우저가** 막는다 — 그러면 모든 주소가
  // "blocked" 로 나와서 "차가 사설 주소를 막는다"처럼 보이지만 아무것도 잰 것이 없다. 이 대조군은
  // 평문 페이지에서만 뜻이 있다.
  if (isSecureContext && location.protocol === 'https:') {
    $('addr-result').textContent = 'https 페이지에서는 재지 않는다 (혼합 콘텐츠를 브라우저가 막는다) — 평문 /diag 의 값을 보라';
    log('address probe skipped on https (mixed content)');
    return;
  }
  const out = $('addr-result');
  let addresses: string[] = [];
  try {
    const st = await (await fetch('/api/status')).json();
    addresses = Array.isArray(st.addresses) ? st.addresses : [];
  } catch (e) { log(`status fetch failed: ${String(e)}`); }
  const result: NonNullable<Report['addresses']> = {};
  if (!addresses.length) { out.textContent = '서버가 주소 목록을 주지 않음'; report.addresses = result; return; }
  const lines: string[] = [];
  for (const entry of addresses) {
    const addr = entry.includes('=') ? entry.slice(entry.indexOf('=') + 1) : entry;
    const url = `http://${addr}:${location.port || 80}/api/status`;
    if (addr === location.hostname) { result[entry] = 'skipped'; lines.push(`${entry}: 현재 주소`); continue; }
    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), 4000);
    try {
      await fetch(url, { mode: 'no-cors', cache: 'no-store', signal: ctrl.signal });
      result[entry] = 'reachable';
      lines.push(`${entry}: 접속됨 (차단 안 됨)`);
    } catch {
      result[entry] = 'blocked';
      lines.push(`${entry}: 차단됨`);
    } finally { clearTimeout(timer); }
  }
  out.textContent = lines.join(' · ');
  log(`address probe ${JSON.stringify(result)}`);
  report.addresses = result;
  (window as any).__diag.addresses = result;
}

/**
 * WebRTC 가능성 조사. 결론만 말하면 되므로 한 방향, 한 트랙, 코덱마다 몇 초다. 전부 시간 상한이 있다 — 차에서 이
 * 페이지가 "측정 중…"에 멈추는 것이 최악이다. H.264 를 먼저 청하는 이유: 폰이 지금 만드는 것이 그것이라,
 * 만약 WebRTC 로 간다면 재인코딩 없이 그대로 실을 수 있는지가 첫 질문이기 때문이다. 그 다음 AV1·VP9·H.265 를
 * 하나씩 청해 어느 코덱이 하드웨어 디코더를 받는지(`decoderImplementation`)를 적는다.
 */
const RTC_FIRST_MS = 4000;
const RTC_MS = 2500;
interface Loopback { loopback: string; framesDecoded: number; codec: string; decoder: string; powerEfficient: boolean | null; ms: number; dataChannel: boolean; unreliableChannel: boolean }

async function loopback(PC: typeof RTCPeerConnection, prefer: RegExp | null, ms: number, mount: HTMLElement): Promise<Loopback> {
  const r: Loopback = { loopback: '', framesDecoded: 0, codec: '', decoder: '', powerEfficient: null, ms: 0, dataChannel: false, unreliableChannel: false };
  const t0 = performance.now();
  const canvas = document.createElement('canvas');
  canvas.width = 320; canvas.height = 180;
  const ctx = canvas.getContext('2d');
  let hue = 0;
  // 움직이는 그림이어야 프레임이 난다(정지 화면은 캡처도 인코더도 쉰다).
  const paint = setInterval(() => { if (ctx) { hue = (hue + 37) % 360; ctx.fillStyle = `hsl(${hue} 80% 50%)`; ctx.fillRect(0, 0, 320, 180); } }, 33);
  let pc1: RTCPeerConnection | null = null;
  let pc2: RTCPeerConnection | null = null;
  let video: HTMLVideoElement | null = null;
  try {
    const stream = (canvas as any).captureStream?.(30) as MediaStream | undefined;
    const track = stream?.getVideoTracks()[0];
    if (!stream || !track) { r.loopback = 'canvas.captureStream 없음'; return r; }
    pc1 = new PC(); pc2 = new PC();
    const a = pc1, b = pc2;
    a.onicecandidate = (e) => { if (e.candidate) void b.addIceCandidate(e.candidate).catch(() => { /* 늦게 온 후보 */ }); };
    b.onicecandidate = (e) => { if (e.candidate) void a.addIceCandidate(e.candidate).catch(() => { /* 늦게 온 후보 */ }); };
    const dc = a.createDataChannel('probe');
    dc.onopen = () => { r.dataChannel = true; };
    // 순서도 재전송도 없는 채널: 큰 프레임 하나가 뒤를 막지 않는 전송의 최소 단위. 열리기만 하면 O 다.
    try {
      const udc = a.createDataChannel('unreliable', { ordered: false, maxRetransmits: 0 });
      udc.onopen = () => { r.unreliableChannel = true; };
    } catch { /* 옵션을 거부하면 X 로 남는다 */ }
    const tx = a.addTransceiver(track, { direction: 'sendonly', streams: [stream] });
    if (prefer) {
      try {
        const send = (window as any).RTCRtpSender?.getCapabilities?.('video') as RTCRtpCapabilities | null | undefined;
        const codecs = send?.codecs ?? [];
        const want = codecs.filter((c) => prefer.test(c.mimeType));
        if (!want.length) { r.loopback = '보낼 수 있는 코덱에 없음'; return r; }
        if (typeof (tx as any).setCodecPreferences === 'function') {
          // rtx·fec 는 짝을 맞춰 뒤에 붙인다 — 없으면 협상이 실패하는 브라우저가 있다.
          (tx as any).setCodecPreferences([...want, ...codecs.filter((c) => !prefer.test(c.mimeType))]);
        }
      } catch (e) { r.loopback = `setCodecPreferences 실패: ${String(e)}`; return r; }
    }
    const gotTrack = new Promise<MediaStream | null>((res) => { b.ontrack = (e) => res(e.streams[0] ?? new MediaStream([e.track])); });
    const offer = await a.createOffer();
    await a.setLocalDescription(offer);
    await b.setRemoteDescription(offer);
    const answer = await b.createAnswer();
    await b.setLocalDescription(answer);
    await a.setRemoteDescription(answer);
    const remote = await Promise.race([gotTrack, sleep(ms).then(() => null)]);
    if (!remote) { r.loopback = `${ms}ms 안에 트랙이 안 옴`; return r; }
    // 싱크가 있어야 크로미엄이 디코드한다. 화면 밖에 두는 게 아니라 작게 보이게 둔다 — 보이지 않는 요소는 재생을 멈출 수 있다.
    video = document.createElement('video');
    video.muted = true; video.autoplay = true; (video as any).playsInline = true;
    video.style.cssText = 'width:64px;height:36px;background:#000';
    video.srcObject = remote;
    mount.append(video);
    void video.play().catch(() => { /* 자동재생 제한이면 getStats 가 0f 로 말해 준다 */ });
    await sleep(ms);
    const stats = await b.getStats();
    let codecId = '';
    stats.forEach((st: any) => {
      if (st.type === 'inbound-rtp' && (st.kind === 'video' || st.mediaType === 'video')) {
        r.framesDecoded = Number(st.framesDecoded ?? 0);
        r.decoder = String(st.decoderImplementation ?? '');
        r.powerEfficient = typeof st.powerEfficientDecoder === 'boolean' ? st.powerEfficientDecoder : null;
        codecId = String(st.codecId ?? '');
      }
    });
    const codec: any = codecId ? stats.get(codecId) : null;
    r.codec = String(codec?.mimeType ?? '').replace(/^video\//i, '');
    r.loopback = r.framesDecoded > 0 ? 'ok' : 'no frames';
    return r;
  } catch (e) {
    r.loopback = `실패: ${String(e)}`;
    return r;
  } finally {
    r.ms = Math.round(performance.now() - t0);
    clearInterval(paint);
    try { pc1?.close(); } catch { /* 이미 닫힘 */ }
    try { pc2?.close(); } catch { /* 이미 닫힘 */ }
    try { video?.remove(); } catch { /* 없음 */ }
  }
}

async function webrtcProbe(): Promise<void> {
  const table = $('rtc');
  const note = $('rtc-note');
  const PC = (window as any).RTCPeerConnection as typeof RTCPeerConnection | undefined;
  const r: NonNullable<Report['webrtc']> = {
    present: typeof PC === 'function', codecs: [], loopback: '', framesDecoded: 0, codec: '', decoder: '', powerEfficient: null,
    dataChannel: false, unreliableChannel: false, webTransport: typeof (window as any).WebTransport === 'function', ms: 0, perCodec: {},
  };
  const fmt = (l: { loopback: string; framesDecoded: number; codec: string; decoder: string; powerEfficient: boolean | null; ms: number }) =>
    l.loopback === 'ok' ? `O (${l.framesDecoded}f, ${l.codec || '?'}, ${l.decoder || '?'}${l.powerEfficient === true ? ', 저전력' : ''}, ${l.ms}ms)` : `X ${l.loopback}`;
  const finish = (): void => {
    row(table, '루프백 (H.264 우선)', fmt(r));
    for (const [k, v] of Object.entries(r.perCodec)) row(table, `루프백 ${k}`, fmt(v));
    row(table, '데이터 채널', r.dataChannel);
    row(table, '비신뢰 채널 (ordered:false, maxRetransmits:0)', r.unreliableChannel);
    row(table, 'WebTransport', r.webTransport);
    note.textContent = r.present
      ? '같은 페이지 안에서 보내고 받아 본 것이다. O 는 "이 브라우저에서 WebRTC 가 성립한다"까지이고, 폰 쪽 스택은 아직 없다. 디코더 이름이 ExternalDecoder 류면 하드웨어, FFmpeg/libvpx/dav1d 면 소프트웨어다.'
      : 'RTCPeerConnection 이 없다 — 이 브라우저에서 WebRTC 는 길이 아니다.';
    report.webrtc = r;
    (window as any).__diag.webrtc = r;
    log(`webrtc probe ${JSON.stringify(r)}`);
  };
  row(table, 'RTCPeerConnection', r.present);
  if (!PC) { r.loopback = 'RTCPeerConnection 없음'; finish(); return; }
  try {
    const caps = (window as any).RTCRtpReceiver?.getCapabilities?.('video') as RTCRtpCapabilities | null | undefined;
    const seen = new Set<string>();
    for (const c of caps?.codecs ?? []) {
      const name = c.mimeType.replace(/^video\//i, '');
      if (/^(rtx|red|ulpfec|flexfec-03)$/i.test(name)) continue;
      const profile = /profile-level-id=([0-9a-f]+)/i.exec(c.sdpFmtpLine ?? '')?.[1] ?? /profile=(\d+)/.exec(c.sdpFmtpLine ?? '')?.[1];
      seen.add(profile ? `${name} ${profile}` : name);
    }
    r.codecs = [...seen];
  } catch (e) { r.codecs = [`getCapabilities 실패: ${String(e)}`]; }
  row(table, '수신 코덱', r.codecs.join(', ') || '-');

  const mount = table.parentElement ?? document.body;
  const first = await loopback(PC, /h264/i, RTC_FIRST_MS, mount);
  Object.assign(r, { loopback: first.loopback, framesDecoded: first.framesDecoded, codec: first.codec, decoder: first.decoder, powerEfficient: first.powerEfficient, ms: first.ms, dataChannel: first.dataChannel, unreliableChannel: first.unreliableChannel });
  // 성립하지 않는 브라우저에서 코덱마다 다시 기다릴 이유가 없다.
  if (first.loopback === 'ok') {
    for (const [name, re] of [['AV1', /av1/i], ['VP9', /vp9/i], ['H265', /h265|hevc/i]] as const) {
      if (!r.codecs.some((c) => re.test(c))) continue;
      const l = await loopback(PC, re, RTC_MS, mount);
      r.perCodec[name] = { loopback: l.loopback, framesDecoded: l.framesDecoded, codec: l.codec, decoder: l.decoder, powerEfficient: l.powerEfficient, ms: l.ms };
    }
  }
  finish();
}

/**
 * 링크 프로브: N 바이트를 받는 데 걸리는 시간. 폰의 `/api/blob` 이 무작위 ASCII 를 준다. IDR 한 장(100~575 KB)이
 * 이 링크에서 몇 ms 인지를 인코더 없이 직접 재는 것이다 — 실차 #76 의 "575 KB = 반 초" 는 10 Mbps 를 가정한
 * 계산이었고, 이것은 측정이다. 크기 순으로 하나씩, 각각 8 초 상한. 600 KB 는 두 번(첫 번은 슬로 스타트 포함).
 */
const LINK_SIZES: ReadonlyArray<readonly [string, number]> = [['64KB', 65536], ['256KB', 262144], ['600KB', 614400], ['600KB#2', 614400], ['2MB', 2097152]];
async function linkProbe(): Promise<void> {
  const out = $('link-result');
  const result: NonNullable<Report['link']> = {};
  const lines: string[] = [];
  for (const [label, bytes] of LINK_SIZES) {
    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), 8000);
    const t0 = performance.now();
    try {
      const res = await fetch(`/api/blob?bytes=${bytes}`, { cache: 'no-store', signal: ctrl.signal });
      const buf = await res.arrayBuffer();
      const ms = Math.round(performance.now() - t0);
      const mbps = Math.round((buf.byteLength * 8) / Math.max(1, ms) / 1000 * 10) / 10;
      result[label] = { ms, mbps };
      lines.push(`${label} ${ms}ms (${mbps} Mbps)`);
      out.textContent = lines.join(' · ');
    } catch (e) {
      result[label] = `실패: ${String(e)}`;
      lines.push(`${label} 실패`);
      out.textContent = lines.join(' · ');
      break; // 한 번 막힌 링크에 더 큰 것을 던져 봐야 다음 프로브만 늦어진다
    } finally { clearTimeout(timer); }
  }
  log(`link probe ${JSON.stringify(result)}`);
  report.link = result;
  (window as any).__diag.link = result;
}

function summarize(): string {
  const v = report.video;
  const w = report.ws;
  const blocked = Object.values(report.addresses ?? {}).filter((s) => s === 'blocked').length;
  const reachable = Object.values(report.addresses ?? {}).filter((s) => s === 'reachable').length;
  return [
    uaLabel(navigator.userAgent),
    `${innerWidth}x${innerHeight}@${devicePixelRatio}`,
    w ? `ws ${w.ok}/20 ${w.avg}ms` : 'ws -',
    // 무엇으로 쟀는지가 없는 lag 숫자는 비교할 수 없다 (2026-09-18 이전 리포트의 video 줄은 MSE 였다).
    v ? `video ${v.path} ${v.frames}f ${v.fps}fps lag ${v.latencyMs}ms${v.error ? ` err=${v.error}` : ''}` : 'video -',
    `private-ip blocked=${blocked} reachable=${reachable}`,
    // Reading a report later, "WebCodecs X" is worthless without knowing where it was asked.
    report.secure ? `secure=${report.secure.isSecureContext ? 'O' : 'X'} webcodecs=${report.secure.videoDecoder ? 'O' : 'X'}` : '',
    // 가능성 조사 한 칸: WebRTC 가 이 브라우저에서 돌았는가, 돌았다면 무엇으로 풀었는가.
    report.webrtc ? `webrtc=${report.webrtc.loopback === 'ok' ? `O ${report.webrtc.codec || '?'}/${report.webrtc.decoder || '?'}` : `X ${report.webrtc.loopback}`}` : '',
    // 이 링크에서 IDR 한 장 크기(600 KB)가 걸리는 시간 — 두 번째 측정(슬로 스타트 제외).
    (() => { const l = report.link?.['600KB#2']; return l && typeof l !== 'string' ? `link 600KB=${l.ms}ms ${l.mbps}Mbps` : ''; })(),
  ].filter(Boolean).join(', ');
}

let submitted = false;
async function submit(): Promise<void> {
  if (submitted) return;
  submitted = true;
  const out = $('report-result');
  report.summary = summarize();
  report.log = logEl.textContent ?? '';
  $('summary').textContent = report.summary;
  try {
    const res = await fetch('/api/report', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(report) });
    const j = await res.json();
    if (j.ok) {
      out.textContent = `저장됨 #${j.id} (${j.receivedAt}, 총 ${j.stored}건) — 노트북에서 /api/reports 로 확인`;
      out.className = 'ok';
    } else {
      out.textContent = `저장 실패: ${j.error ?? res.status}`; out.className = 'bad';
    }
    (window as any).__diag.report = j;
  } catch (e) {
    out.textContent = `저장 실패: ${String(e)} — 이 화면을 사진으로 남기세요`; out.className = 'bad';
    (window as any).__diag.report = { ok: false, error: String(e) };
  }
}

(window as any).__diag = { done: false };
// Worst case of the probes above: WS 20×3s + video 5+3+5s + addresses 4×4s + link 5×8s + webrtc 8+3×5s ≈ 160s. Past that,
// something is wedged; save what we have rather than sit on "측정 중…" forever.
const WATCHDOG_MS = 180_000;
async function step(name: string, fn: () => Promise<void>): Promise<void> {
  try { await fn(); } catch (e) { log(`${name} failed: ${String(e)}`); }
}
(async () => {
  const watchdog = setTimeout(() => {
    log(`watchdog: ${WATCHDOG_MS / 1000}초 안에 안 끝남, 지금까지 결과 저장`);
    submit().then(() => { (window as any).__diag.done = true; });
  }, WATCHDOG_MS);
  await step('codec probe', codecProbe);
  await step('ws probe', wsProbe);
  await step('video probe', videoProbe);
  await step('address probe', addressProbe);
  await step('link probe', linkProbe);
  await step('webrtc probe', webrtcProbe);
  clearTimeout(watchdog);
  await submit();
  (window as any).__diag.done = true;
  log('done');
})();

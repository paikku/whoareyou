// 적응 비트레이트 — 링크가 막히면 비트를 먼저 내리고, 풀리면 천천히 되올린다.
//
// 왜 이 파일이 생겼나: 실차 report #70 에서 1080p60 12Mbps 가 무너졌을 때 디코더는 밀리지 않았다(적체 0~2).
// 무너진 것은 링크였다 — rtt 7 → 45 → 69 ms, 2 초에 패킷 12 개(60fps 기대치의 1/10). 그때 유일한 대응은
// 화질 사다리를 한 칸 내리는 것이었는데, 그건 10 초 표본 두 개(20 초)를 기다린 뒤 **화소**를 줄이는 일이다.
// 링크가 모자란 것은 **비트**이고, 그 답은 몇 초 안에 비트레이트를 줄이는 것이다. 폰이 비트레이트를
// 재빌드 없이 즉시 바꿀 수 있게 되었으므로(`/api/encoder?bitrate=`, `bitrateLive`) 그 답을 여기서 낸다.
// WebRTC 의 혼잡 제어가 하는 일을 WebRTC 없이 하는 셈이다.
//
// 신호는 셋이다. 차가 보는 rtt(컨트롤 ping, 2 초마다), 렌더러가 버린 프레임, 디코더 적체. rtt 는 폰의
// 큐보다 빠르다 — 폰은 커널 소켓 버퍼 뒤에 있어서 링크가 막힌 뒤 한참 지나야 자기 큐가 차는 것을 본다.
//
// 규칙은 일부러 단순하다. 내리기는 빠르게(25% 씩, 5 초 간격), 올리기는 느리게(공칭의 10% 씩, 20 초
// 조용한 뒤). 바닥은 공칭의 40% — 그 아래로 내려도 화질만 나빠지고 링크 문제는 사다리가 맡는 편이 낫다.
// 순수 함수에 가깝게 써서(시각은 표본이 들고 온다) 테스트가 시계를 돌릴 수 있다.

export interface AbrSample {
  /** 표본 시각(ms). 실제로는 Date.now(), 테스트에서는 임의. */
  nowMs: number;
  /** 컨트롤 소켓 왕복(ms); 아직 모르면 -1. */
  rttMs: number;
  /** 지난 표본 이후 렌더러가 버린 프레임 수. */
  dropped: number;
  /** 디코더에 넣었는데 아직 안 나온 프레임 수. */
  backlog: number;
}

export type AbrAction = { kind: 'cut' | 'raise'; bitrate: number; why: string };

export interface AbrInfo {
  nominal: number;
  target: number;
  floor: number;
  cuts: number;
  raises: number;
  /** 최근 60 초의 최소 rtt — "정상"의 기준. 표본이 모자라면 null. */
  baselineRtt: number | null;
}

/** 내릴 때 곱하는 비율. */
const CUT_RATIO = 0.75;
/** 올릴 때 더하는 몫(공칭 대비). */
const RAISE_STEP = 0.1;
/** 바닥(공칭 대비). */
const FLOOR_RATIO = 0.4;
/** 내린 뒤 다음 조치까지. 폰의 인코더가 새 예산으로 프레임을 내고 링크가 그것을 반영할 시간. */
const CUT_COOLDOWN_MS = 5_000;
/** 이만큼 조용해야 한 칸 올린다. 오르내림이 반복되는 것보다 낮은 데 머무는 편이 낫다. */
const RAISE_AFTER_MS = 20_000;
/** 기준 rtt 를 세는 창. */
const BASELINE_WINDOW_MS = 60_000;
/** 기준을 믿기 시작하는 표본 수(2 초 ping 이므로 10 초). */
const BASELINE_MIN_SAMPLES = 5;
/** rtt 가 기준보다 이만큼 **그리고** 두 배 넘게 높으면 링크가 막힌 것으로 본다. */
const RTT_SLACK_MS = 25;
/** 적체가 이 위면 디코더가 못 따라오는 것 — 사다리와 같은 문턱. */
const BACKLOG_BAD = 4;
/** 비트레이트는 이 단위로 자른다(폰 로그와 리포트가 읽기 쉽게). */
const STEP_BPS = 100_000;

export class AbrController {
  nominal = 0;
  target = 0;
  cuts = 0;
  raises = 0;
  private rtts: { t: number; rtt: number }[] = [];
  private lastChangeAt = -Infinity;
  private cleanSince = -1;
  private badTicks = 0;

  /**
   * 새 공칭값(프리셋이 바뀌었다). `current` 는 폰이 지금 도는 비트레이트 — 재빌드 직후라면 공칭과 같고,
   * 페이지를 다시 열었을 때는 지난 세션이 내려 둔 값일 수 있다. 어느 쪽이든 거기서 시작한다.
   */
  setNominal(nominal: number, current: number = nominal): void {
    this.nominal = nominal;
    this.target = Math.min(nominal, Math.max(this.floor, current > 0 ? current : nominal));
    this.badTicks = 0;
    this.cleanSince = -1;
  }

  get floor(): number {
    return round(this.nominal * FLOOR_RATIO);
  }

  get atFloor(): boolean {
    return this.nominal > 0 && this.target <= this.floor;
  }

  baselineRtt(nowMs: number): number | null {
    const from = nowMs - BASELINE_WINDOW_MS;
    this.rtts = this.rtts.filter((s) => s.t >= from);
    if (this.rtts.length < BASELINE_MIN_SAMPLES) return null;
    return Math.min(...this.rtts.map((s) => s.rtt));
  }

  /** 표본 하나를 보고 할 일을 돌려준다(없으면 null). 돌려준 조치는 호출자가 폰에 보낸 뒤 반영된 것으로 친다. */
  observe(s: AbrSample): AbrAction | null {
    if (this.nominal <= 0) return null;
    const baseline = this.baselineRtt(s.nowMs);
    if (s.rttMs >= 0) this.rtts.push({ t: s.nowMs, rtt: s.rttMs });
    const rttBad = baseline !== null && s.rttMs >= 0 && s.rttMs > baseline + RTT_SLACK_MS && s.rttMs > baseline * 2;
    const dropBad = s.dropped > 0 || s.backlog > BACKLOG_BAD;
    if (rttBad || dropBad) {
      this.badTicks++;
      this.cleanSince = -1;
      // 버린 프레임은 그 자체가 사건이라 바로, rtt 만으로는 두 표본(4 초) 연속이어야 움직인다 — ping 한 번의
      // 튐(#73 t=20 의 18ms 같은 것)으로 화질을 깎지 않기 위해서다.
      if (!dropBad && this.badTicks < 2) return null;
      if (s.nowMs - this.lastChangeAt < CUT_COOLDOWN_MS || this.atFloor) return null;
      const next = Math.max(this.floor, round(this.target * CUT_RATIO));
      if (next >= this.target) return null;
      this.target = next;
      this.lastChangeAt = s.nowMs;
      this.cuts++;
      const why = dropBad ? `dropped ${s.dropped}, backlog ${s.backlog}` : `rtt ${s.rttMs}ms (기준 ${baseline}ms)`;
      return { kind: 'cut', bitrate: next, why };
    }
    this.badTicks = 0;
    if (this.cleanSince < 0) this.cleanSince = s.nowMs;
    if (this.target >= this.nominal) return null;
    if (s.nowMs - this.cleanSince < RAISE_AFTER_MS || s.nowMs - this.lastChangeAt < RAISE_AFTER_MS) return null;
    const next = Math.min(this.nominal, round(this.target + this.nominal * RAISE_STEP));
    if (next <= this.target) return null;
    this.target = next;
    this.lastChangeAt = s.nowMs;
    this.cleanSince = s.nowMs;
    this.raises++;
    return { kind: 'raise', bitrate: next, why: `${Math.round(RAISE_AFTER_MS / 1000)}s 조용함` };
  }

  info(nowMs: number): AbrInfo {
    return { nominal: this.nominal, target: this.target, floor: this.floor, cuts: this.cuts, raises: this.raises, baselineRtt: this.baselineRtt(nowMs) };
  }
}

const round = (bps: number): number => Math.floor(bps / STEP_BPS) * STEP_BPS;

// ── 렌더러가 내는 수에서 "링크가 막혔다"만 골라내기 ────────────────────────────────────────────
//
// 왜 이것이 따로 있나: 실차 #79 에서 abr 은 2.5 분 동안 12 번 자르고 한 번도 못 올렸다 — 인코더를 다시
// 세울 때마다(프리셋 변경, I-QP 변경) 20 초 안에 바닥(공칭의 40%)이었다. 링크는 멀쩡했다(rtt 12~15ms,
// 70 Mbps). 자른 이유는 전부 **우리가 만든 공백**이었다: 새 init 이 오면 디코더를 다시 세우고 키프레임을
// 기다리며 오는 것을 버리는데, 그 버린 수가 그대로 혼잡으로 읽혔다("abr ↓ 9000k (dropped 1)").
//
// 그래서 신호를 이렇게 읽는다.
//   · 재동기 중(키프레임 대기)의 표본은 **건너뛴다** — 그동안 버린 것은 링크의 증거가 아니다.
//   · 혼잡의 크기는 버린 장수가 아니라 **적체가 한계를 넘은 횟수**(`overloads`)다. 기다린 길이에
//     비례해 부풀지 않는다. 스스로 버리는 소프트 경로(h264)는 그 수가 없으므로 예전처럼 드롭을 본다.
//   · 재동기 **직후** 표본의 "늦음"도 안 센다: 키프레임이 오는 순간 그동안 쌓인 것이 한꺼번에 들어와
//     늘 수십 장이 늦게 온 것으로 보이는데, 그것도 우리가 만든 버스트다.

/** 렌더러 통계 중 여기서 보는 것만(`RendererStats` 의 부분집합). */
export interface RendererSignal {
  droppedFrames: number;
  late?: number;
  overloads?: number;
  waitingForKey?: boolean;
  backlog?: number;
}

/** 2 초 표본 안에 이만큼까지의 "늦음"은 혼잡으로 치지 않는다(60fps 에서 한두 장은 늘 있다). */
export const LATE_TOLERANCE = 4;

export class CongestionReader {
  private dropped = 0;
  private late = 0;
  private overloads = 0;
  /** 지난 표본이 재동기 중이었나. */
  private resyncing = false;
  /** 건너뛴 표본에서 본 적체 초과 — 진짜 신호이므로 다음 표본에 실어 보낸다. */
  private pending = 0;

  /** 표본 하나를 읽는다. 건너뛸 표본이면 null. */
  read(s: RendererSignal): { dropped: number; backlog: number } | null {
    const overload = s.overloads === undefined
      ? Math.max(0, s.droppedFrames - this.dropped)
      : Math.max(0, s.overloads - this.overloads);
    const late = this.resyncing ? 0 : Math.max(0, (s.late ?? 0) - this.late);
    this.dropped = s.droppedFrames;
    this.late = s.late ?? 0;
    this.overloads = s.overloads ?? 0;
    if (s.waitingForKey) { this.resyncing = true; this.pending += overload; return null; }
    this.resyncing = false;
    const dropped = this.pending + overload + (late > LATE_TOLERANCE ? late : 0);
    this.pending = 0;
    return { dropped, backlog: s.backlog ?? 0 };
  }
}

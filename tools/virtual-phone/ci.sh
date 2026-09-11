#!/usr/bin/env bash
# CI 안에서 가상 폰을 띄우고 검사를 순서대로 돌린다 (emulator.yml 이 부른다).
# 로컬에서 손으로 하면: vphone.sh up → npm run device → BASE_URL=… npx playwright test → vphone.sh down
set -euo pipefail
cd "$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

mkdir -p out/vphone

collect() {
  local code=$?
  echo "::group::가상 폰 로그"
  tools/virtual-phone/vphone.sh logs 2>&1 | tee out/vphone/server.log || true
  tools/virtual-phone/vphone.sh status 2>&1 | tee out/vphone/status.json || true
  [ -f out/lifecycle/report.md ] && cat out/lifecycle/report.md || true
  [ -f out/lifecycle/explore.md ] && cat out/lifecycle/explore.md || true
  "${ANDROID_HOME:-$ANDROID_SDK_ROOT}/platform-tools/adb" logcat -d -t 500 > out/vphone/logcat.txt 2>&1 || true
  echo "::endgroup::"
  return $code
}
trap collect EXIT

# 어딘가에서 멈추면 60분을 태우지 말고 여기서 끊고 로그를 남긴다(trap collect).
echo "::group::가상 폰 기동"
timeout 900 tools/virtual-phone/vphone.sh up
echo "::endgroup::"

echo "::group::기기 검사 (M4 / M4-b / M5 / M7)"
timeout 900 npm run device
echo "::endgroup::"

echo "::group::차 쪽 클라이언트를 진짜 서버에 대고 (Chrome 148, Model Y 프로필)"
# 절대 경로로: npm workspace 는 tests/e2e 에서 돌기 때문에 상대 경로는 어긋난다.
CHROME_PATH="$PWD/$(find tests/e2e/.cache -name chrome -type f | head -1)" \
BASE_URL=http://127.0.0.1:3333 \
  timeout 1200 npm test --workspace tests/e2e -- --project=model-y-2026.26
echo "::endgroup::"

# 폰 생애주기 × 웹 생애주기: 앱 전환·전원·도즈·새로고침을 순서대로 걸고 복구 시간을 표로 남긴다.
echo "::group::생애주기 시나리오"
CHROME_PATH="$PWD/$(find tests/e2e/.cache -name chrome -type f | head -1)" \
BASE_URL=http://127.0.0.1:3333 \
  timeout 1200 npm run lifecycle --workspace tests/e2e
echo "::endgroup::"

# 무작위 탐색은 기본으로 돌지 않는다. Actions 에서 "Run workflow" 의 explore_steps 로 켠다.
if [ "${EXPLORE_STEPS:-0}" -gt 0 ] 2>/dev/null; then
  echo "::group::무작위 탐색 ($EXPLORE_STEPS 단계)"
  CHROME_PATH="$PWD/$(find tests/e2e/.cache -name chrome -type f | head -1)" \
  BASE_URL=http://127.0.0.1:3333 EXPLORE_STEPS="$EXPLORE_STEPS" \
    timeout 2400 npm run explore --workspace tests/e2e || echo "탐색이 실패로 끝났다 — 보고서를 본다"
  echo "::endgroup::"
fi

echo "::group::킬 스위치 (마지막: 서버를 죽인다)"
timeout 300 npm run test:kill-switch --workspace tests/device
echo "::endgroup::"

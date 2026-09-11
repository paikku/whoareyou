#!/usr/bin/env bash
# 가상 폰: Android 에뮬레이터 위에서 폰과 똑같은 shell uid 서버를 띄운다.
#
# 실기기(S26U) 없이도 M4(가상 디스플레이)·M4-b(앱 충돌)·M5(터치 주입)·M7(화면 OFF)을 볼 수 있게 하는 것이 목적이다.
# 서버를 띄우는 명령은 docs/testing-guide.md 의 "PC에서 띄울 때" 폴백과 같은 것을 쓴다 — 즉 폰에서 도는 것과
# 같은 dex, 같은 인자, 같은 uid(2000)다. 다른 점은 접속 경로뿐이다: 차/노트북은 VPN 주소(100.99.9.9)로 오지만
# 여기서는 `adb forward` 로 온다. 그래서 **가정 1(VpnService 주소 배달)과 핫스팟은 여전히 실기기에서만** 본다.
#
#   tools/virtual-phone/vphone.sh sdk      # SDK·시스템 이미지 설치 (최초 1회, 약 2GB)
#   tools/virtual-phone/vphone.sh up       # 에뮬레이터 부팅 → APK 설치 → 서버 기동 → 127.0.0.1:3333 포워딩
#   tools/virtual-phone/vphone.sh status   # /api/status 를 그대로 출력
#   tools/virtual-phone/vphone.sh logs     # 서버 로그 (폰의 /api/log 와 같은 내용 + 기동 로그)
#   tools/virtual-phone/vphone.sh down     # 서버 종료(킬 스위치) → 에뮬레이터 종료
#
# 환경 변수: ANDROID_HOME, API(기본 36), AVD(기본 carcast-vphone), PORT(기본 3333), APK, EMULATOR_ARGS
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/../.." && pwd)"

ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
API="${API:-36}"
AVD="${AVD:-carcast-vphone}"
PORT="${PORT:-3333}"
APK="${APK:-$root/app/build/outputs/apk/debug/app-debug.apk}"
# google_apis(플레이스토어 아님) 이미지라야 adb root/shell 이 자유롭고, x86_64 라야 KVM 가속을 받는다.
IMAGE="${IMAGE:-system-images;android-$API;google_apis;x86_64}"
PKG=com.carcast
DEVICE_LOG=/data/local/tmp/carcast/vphone.log
BOOT_TIMEOUT="${BOOT_TIMEOUT:-600}"

adb() { "$ANDROID_HOME/platform-tools/adb" ${SERIAL:+-s "$SERIAL"} "$@"; }
say() { printf '\033[36m[vphone]\033[0m %s\n' "$*"; }
die() { printf '\033[31m[vphone] %s\033[0m\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------------------- sdk

cmd_sdk() {
  local sdkmanager="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
  if [ ! -x "$sdkmanager" ]; then
    say "cmdline-tools 설치 → $ANDROID_HOME"
    mkdir -p "$ANDROID_HOME/cmdline-tools"
    local zip; zip="$(mktemp -d)/cmdline-tools.zip"
    curl -fsSL -o "$zip" https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
    unzip -q "$zip" -d "$ANDROID_HOME/cmdline-tools"
    mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
  fi
  yes 2>/dev/null | "$sdkmanager" --licenses >/dev/null || true
  say "platform-tools / emulator / $IMAGE 설치 (처음이면 몇 분)"
  "$sdkmanager" "platform-tools" "emulator" "$IMAGE" >/dev/null
  say "완료. 다음: vphone.sh up"
}

# ---------------------------------------------------------------------------- up

create_avd() {
  local avdmanager="$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager"
  [ -x "$avdmanager" ] || die "cmdline-tools 가 없다. 먼저: vphone.sh sdk"
  if "$avdmanager" list avd 2>/dev/null | grep -q "Name: $AVD$"; then return; fi
  say "AVD 생성: $AVD ($IMAGE)"
  echo no | "$avdmanager" create avd -n "$AVD" -k "$IMAGE" -d pixel_7 --force >/dev/null
  local cfg="$HOME/.android/avd/$AVD.avd/config.ini"
  # 폰과 비슷한 화면(가상 디스플레이와는 별개지만, 앱이 폰 화면에서 열릴 때의 크기)과, 인코딩이 굶지 않을 만큼의 램.
  {
    echo "hw.lcd.density=420"
    echo "hw.lcd.width=1080"
    echo "hw.lcd.height=2400"
    echo "hw.ramSize=4096"
    echo "hw.keyboard=yes"
    echo "disk.dataPartition.size=6G"
  } >> "$cfg"
}

boot_emulator() {
  if adb devices | grep -q "^emulator-.*device$"; then
    say "이미 떠 있는 에뮬레이터를 재사용한다"
    return
  fi
  [ -x "$ANDROID_HOME/emulator/emulator" ] || die "emulator 가 없다. 먼저: vphone.sh sdk"
  if [ ! -e /dev/kvm ]; then
    say "경고: /dev/kvm 이 없다 — 가속 없이는 부팅이 매우 느리거나 실패한다 (CI 러너는 KVM이 있다)"
  fi
  create_avd
  say "부팅: $AVD"
  # -no-snapshot: 매번 같은 상태에서 시작한다(테스트가 앞 실행의 찌꺼기를 보지 않도록).
  # swiftshader_indirect: 헤드리스에서도 GL 서피스가 있어야 가상 디스플레이 → 인코더 경로가 산다.
  nohup "$ANDROID_HOME/emulator/emulator" -avd "$AVD" \
    -no-window -no-audio -no-boot-anim -no-snapshot -no-metrics \
    -gpu swiftshader_indirect -camera-back none -camera-front none \
    -memory 4096 ${EMULATOR_ARGS:-} >"${TMPDIR:-/tmp}/vphone-emulator.log" 2>&1 &
  adb wait-for-device
  local waited=0
  until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
    sleep 3; waited=$((waited + 3))
    [ "$waited" -lt "$BOOT_TIMEOUT" ] || die "부팅 타임아웃(${BOOT_TIMEOUT}s). 로그: ${TMPDIR:-/tmp}/vphone-emulator.log"
  done
  say "부팅 완료 (${waited}s)"
  adb shell wm dismiss-keyguard >/dev/null 2>&1 || true
  # 애니메이션은 가상 디스플레이의 프레임 타이밍만 흔든다. 실기기에서는 건드리지 않지만 여기서는 끈다.
  for s in window_animation_scale transition_animation_scale animator_duration_scale; do
    adb shell settings put global "$s" 0 >/dev/null 2>&1 || true
  done
}

install_apk() {
  [ -f "$APK" ] || die "APK 가 없다: $APK  (./gradlew :app:assembleDebug)"
  say "설치: $(basename "$APK")"
  timeout 300 "$ANDROID_HOME/platform-tools/adb" install -r -g "$APK" >/dev/null \
    || die "설치 실패/타임아웃"
}

# APK 안의 SERVER_BUILD_ID. 서버는 인자로 받은 build id 가 다르면 거부하고 기대값을 stderr 에 적으므로,
# 일부러 틀린 값으로 한 번 찔러 그 값을 읽는다. 작업트리가 빌드 이후에 바뀌었어도 항상 맞는다.
server_build_id() {
  local out
  out="$(timeout 120 "$ANDROID_HOME/platform-tools/adb" shell "CLASSPATH=\$(pm path $PKG | cut -d: -f2) app_process / com.carcast.server.Server __probe__" 2>&1 || true)"
  local id; id="$(printf '%s' "$out" | sed -n 's/.*build id mismatch, expected \([0-9a-z]*\).*/\1/p' | head -1)"
  [ -n "$id" ] || die "서버 build id 를 읽지 못했다. app_process 출력: $out"
  printf '%s' "$id"
}

start_server() {
  local id="$1"
  adb shell "mkdir -p $(dirname $DEVICE_LOG)" >/dev/null 2>&1 || true
  # 폰에서 앱이 하는 것과 같은 분리 실행. daemon=true 는 stdin EOF 로 죽지 않는다는 뜻이고,
  # 종료는 loopback 의 POST /api/stop(킬 스위치) 또는 pkill 이다.
  # stdin 까지 /dev/null 로 떼어 놓는다 — 세 fd 중 하나라도 adb 파이프에 남아 있으면
  # `adb shell "... &"` 가 원격 셸이 끝난 뒤에도 돌아오지 않는다(CI 에서 여기서 멈췄다).
  timeout 60 "$ANDROID_HOME/platform-tools/adb" shell \
    "CLASSPATH=\$(pm path $PKG | cut -d: -f2) setsid nohup app_process / com.carcast.server.Server $id port=$PORT daemon=true >$DEVICE_LOG 2>&1 </dev/null &" \
    >/dev/null || die "서버 기동 명령이 돌아오지 않았다"
  adb forward --remove tcp:$PORT >/dev/null 2>&1 || true
  adb forward tcp:$PORT tcp:$PORT >/dev/null
  local waited=0
  until curl -fsS --max-time 2 "http://127.0.0.1:$PORT/api/status" >/dev/null 2>&1; do
    sleep 1; waited=$((waited + 1))
    if [ "$waited" -ge 60 ]; then
      say "서버가 응답하지 않는다. 기동 로그:"; adb shell "cat $DEVICE_LOG" || true
      die "서버 기동 실패"
    fi
  done
  say "서버 응답 (${waited}s)"
}

cmd_up() {
  boot_emulator
  say "기기: $(adb devices | sed -n 2p)"
  install_apk
  say "build id 읽는 중"
  local id; id="$(server_build_id)"
  say "build id: $id"
  stop_server_quietly
  say "서버 기동"
  start_server "$id"
  local status; status="$(curl -fsS "http://127.0.0.1:$PORT/api/status")"
  local uid source; uid="$(json_field "$status" uid)"; source="$(json_field "$status" source)"
  say "uid=$uid source=$source"
  [ "$uid" = "2000" ] || say "경고: uid 가 2000(shell)이 아니다 — 실기기라면 차에서 못 붙는 상태다"
  if [ "$source" != "display" ]; then
    say "경고: 가상 디스플레이를 못 만들어 클립으로 대체됐다. 로그:"
    adb shell "cat $DEVICE_LOG" | tail -40 || true
  fi
  say "준비됨 →  BASE_URL=http://127.0.0.1:$PORT"
}

# 아주 작은 JSON 필드 추출기(문자열/숫자/불리언). 테스트 쪽은 node 가 제대로 파싱하므로 여기서는 안내용으로만 쓴다.
json_field() {
  printf '%s' "$1" | sed -n "s/.*\"$2\":[[:space:]]*\"\{0,1\}\([^,\"}]*\)\"\{0,1\}.*/\1/p" | head -1
}

# ---------------------------------------------------------------------------- 그 외

stop_server_quietly() {
  curl -fsS -X POST --max-time 3 "http://127.0.0.1:$PORT/api/stop" >/dev/null 2>&1 || true
  adb shell "pkill -f '^app_process / com.carcast.server.Server'" >/dev/null 2>&1 || true
}

cmd_status() { curl -fsS "http://127.0.0.1:$PORT/api/status"; echo; }

cmd_logs() {
  adb shell "cat $DEVICE_LOG" 2>/dev/null || true
  # 서버가 살아 있으면 /api/log 에 더 최근 줄이 있다(폰 앱이 보여주는 것과 같은 목록).
  curl -fsS "http://127.0.0.1:$PORT/api/log" 2>/dev/null | sed 's/^\[//; s/\]$//; s/","/"\n"/g' || true
  echo
}

cmd_down() {
  say "서버 종료(킬 스위치)"
  stop_server_quietly
  adb forward --remove tcp:$PORT >/dev/null 2>&1 || true
  if adb devices | grep -q "^emulator-.*device$"; then
    say "에뮬레이터 종료"
    adb emu kill >/dev/null 2>&1 || true
  fi
}

case "${1:-}" in
  sdk) cmd_sdk ;;
  up) cmd_up ;;
  status) cmd_status ;;
  logs) cmd_logs ;;
  down) cmd_down ;;
  *) sed -n '2,20p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 2 ;;
esac

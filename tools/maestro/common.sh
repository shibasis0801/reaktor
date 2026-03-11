#!/bin/zsh
set -euo pipefail

resolve_engine() {
  if [[ -n "${MAESTRO_ENGINE:-}" ]]; then
    echo "${MAESTRO_ENGINE}"
    return
  fi

  echo "maestro-runner"
}

resolve_maestro_runner_bin() {
  if [[ -n "${MAESTRO_RUNNER_BIN:-}" ]]; then
    echo "${MAESTRO_RUNNER_BIN}"
    return
  fi

  if [[ -x "$HOME/.maestro-runner/bin/maestro-runner" ]]; then
    echo "$HOME/.maestro-runner/bin/maestro-runner"
    return
  fi

  if command -v maestro-runner >/dev/null 2>&1; then
    command -v maestro-runner
    return
  fi

  echo ""
}

require_maestro() {
  if ! command -v maestro >/dev/null 2>&1; then
    echo "maestro not found. Install it first: brew install mobile-dev-inc/tap/maestro" >&2
    exit 1
  fi
}

require_maestro_runner() {
  local runner_bin
  runner_bin="$(resolve_maestro_runner_bin)"
  if [[ -z "${runner_bin}" ]]; then
    echo "maestro-runner not found. Install it first or set MAESTRO_RUNNER_BIN." >&2
    exit 1
  fi

  echo "${runner_bin}"
}

resolve_output_root() {
  local default_root="${1}"
  local timestamp
  timestamp="$(date +%Y-%m-%d/%H%M%S)"
  echo "${MAESTRO_OUTPUT_DIR:-$(pwd)/${default_root}/${timestamp}}"
}

resolve_android_device() {
  local adb_bin="$1"
  local device_id

  device_id="${ANDROID_SERIAL:-${MAESTRO_DEVICE:-$(${adb_bin} devices | awk 'NR > 1 && $2 == "device" { print $1; exit }')}}"
  echo "${device_id}"
}

prepare_android_device() {
  local adb_bin="$1"
  local device_id="$2"

  "${adb_bin}" -s "${device_id}" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
  "${adb_bin}" -s "${device_id}" shell wm dismiss-keyguard >/dev/null 2>&1 || true
  "${adb_bin}" -s "${device_id}" shell input swipe 540 1800 540 400 200 >/dev/null 2>&1 || true
  "${adb_bin}" -s "${device_id}" shell input keyevent KEYCODE_HOME >/dev/null 2>&1 || true
}

resolve_ios_device() {
  if [[ -n "${IOS_DEVICE_ID:-}" ]]; then
    echo "${IOS_DEVICE_ID}"
    return
  fi

  if [[ -n "${MAESTRO_DEVICE:-}" ]]; then
    echo "${MAESTRO_DEVICE}"
    return
  fi

  if [[ -x "/Users/ovd/Library/Python/3.9/bin/idb" && -x "/opt/homebrew/bin/idb_companion" ]]; then
    local device_id
    device_id="$(IDB_COMPANION_PATH=/opt/homebrew/bin/idb_companion /Users/ovd/Library/Python/3.9/bin/idb list-targets 2>/dev/null | awk -F'|' '$4 ~ /device/ { gsub(/^ +| +$/, "", $2); print $2; exit }')"
    if [[ -n "${device_id}" ]]; then
      echo "${device_id}"
      return
    fi
  fi

  local trace_device
  trace_device="$(xcrun xctrace list devices 2>/dev/null | awk 'match($0, /\(([0-9A-F-]{10,})\)$/, matches) && $0 !~ /Simulator/ { print matches[1]; exit }')"
  echo "${trace_device}"
}

#!/bin/zsh
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "$0")" && pwd)"
source "${SCRIPT_DIR}/common.sh"

if [[ $# -lt 1 ]]; then
  echo "Usage: $0 <flow-or-folder> [runner args...]" >&2
  exit 1
fi

FLOW_TARGET="$1"
shift || true
ENGINE="$(resolve_engine android)"

ADB_BIN="${ADB_BIN:-${ANDROID_HOME:-}/platform-tools/adb}"
if [[ -z "${ADB_BIN}" || ! -x "${ADB_BIN}" ]]; then
  ADB_BIN="$(command -v adb || true)"
fi

if [[ -z "${ADB_BIN}" ]]; then
  echo "adb not found. Install Android platform-tools or set ADB_BIN." >&2
  exit 1
fi

DEVICE_ID="$(resolve_android_device "${ADB_BIN}")"
if [[ -z "${DEVICE_ID}" ]]; then
  echo "No connected Android device found." >&2
  exit 1
fi

prepare_android_device "${ADB_BIN}" "${DEVICE_ID}"

OUTPUT_ROOT="$(resolve_output_root tmp/maestro-results/android)"
FLOW_EXEC_TARGET="${FLOW_TARGET}"

if [[ "${MAESTRO_EVERY_STEP_SCREENSHOT:-false}" == "true" ]]; then
  FLOW_EXEC_TARGET="$(instrument_maestro_target "${FLOW_TARGET}" "${OUTPUT_ROOT}/instrumented")"
fi

echo "Running ${ENGINE} on Android device: ${DEVICE_ID}"
echo "Flow target: ${FLOW_TARGET}"
if [[ "${FLOW_EXEC_TARGET}" != "${FLOW_TARGET}" ]]; then
  echo "Instrumented flow target: ${FLOW_EXEC_TARGET}"
fi
echo "Artifacts: ${OUTPUT_ROOT}"

case "${ENGINE}" in
  maestro)
    require_maestro
    DEBUG_ROOT="${MAESTRO_DEBUG_DIR:-${OUTPUT_ROOT}/debug}"
    mkdir -p "${OUTPUT_ROOT}" "${DEBUG_ROOT}"

    maestro test \
      --platform android \
      --device "${DEVICE_ID}" \
      --test-output-dir "${OUTPUT_ROOT}" \
      --debug-output "${DEBUG_ROOT}" \
      --flatten-debug-output \
      "$@" \
      "${FLOW_EXEC_TARGET}"
    ;;
  maestro-runner)
    RUNNER_BIN="$(require_maestro_runner)"
    mkdir -p "${OUTPUT_ROOT}"

    "${RUNNER_BIN}" \
      --platform android \
      --device "${DEVICE_ID}" \
      test \
      --output "${OUTPUT_ROOT}" \
      --flatten \
      "$@" \
      "${FLOW_EXEC_TARGET}"
    ;;
  *)
    echo "Unsupported MAESTRO_ENGINE for Android: ${ENGINE}" >&2
    exit 1
    ;;
esac

export_successful_screenshots android "${OUTPUT_ROOT}"

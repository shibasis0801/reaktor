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
ENGINE="$(resolve_engine ios)"
DEVICE_ID="$(resolve_ios_device)"
OUTPUT_ROOT="$(resolve_output_root tmp/maestro-results)"

if [[ -z "${DEVICE_ID}" ]]; then
  echo "No connected iOS device found. Set IOS_DEVICE_ID or MAESTRO_DEVICE explicitly." >&2
  exit 1
fi

case "${ENGINE}" in
  maestro-runner)
    RUNNER_BIN="$(require_maestro_runner)"
    TEAM_ID="${MAESTRO_TEAM_ID:-${DEVELOPMENT_TEAM:-}}"
    if [[ -z "${TEAM_ID}" ]]; then
      echo "MAESTRO_TEAM_ID is required for iOS maestro-runner execution." >&2
      exit 1
    fi

    mkdir -p "${OUTPUT_ROOT}"

    ARGS=(
      "${RUNNER_BIN}"
      --platform ios
      --device "${DEVICE_ID}"
      --team-id "${TEAM_ID}"
    )

    if [[ -n "${MAESTRO_APP_FILE:-}" ]]; then
      if [[ ! -e "${MAESTRO_APP_FILE}" ]]; then
        echo "MAESTRO_APP_FILE does not exist: ${MAESTRO_APP_FILE}" >&2
        exit 1
      fi
      ARGS+=(--app-file "${MAESTRO_APP_FILE}")
    fi

    if [[ "${MAESTRO_NO_DRIVER_INSTALL:-false}" == "true" ]]; then
      ARGS+=(--no-driver-install)
    fi

    echo "Running ${ENGINE} on iOS device: ${DEVICE_ID}"
    echo "Flow target: ${FLOW_TARGET}"
    echo "Artifacts: ${OUTPUT_ROOT}"

    exec "${ARGS[@]}" test --output "${OUTPUT_ROOT}" --flatten "$@" "${FLOW_TARGET}"
    ;;
  maestro)
    require_maestro
    DEBUG_ROOT="${MAESTRO_DEBUG_DIR:-${OUTPUT_ROOT}/debug}"
    mkdir -p "${OUTPUT_ROOT}" "${DEBUG_ROOT}"

    echo "Running ${ENGINE} on iOS device: ${DEVICE_ID}"
    echo "Flow target: ${FLOW_TARGET}"
    echo "Artifacts: ${OUTPUT_ROOT}"

    exec maestro test \
      --platform ios \
      --device "${DEVICE_ID}" \
      --test-output-dir "${OUTPUT_ROOT}" \
      --debug-output "${DEBUG_ROOT}" \
      --flatten-debug-output \
      "$@" \
      "${FLOW_TARGET}"
    ;;
  *)
    echo "Unsupported MAESTRO_ENGINE for iOS: ${ENGINE}" >&2
    exit 1
    ;;
 esac

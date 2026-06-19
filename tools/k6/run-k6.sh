#!/usr/bin/env bash
set -euo pipefail

OUTPUT_DIR="${K6_OUTPUT_DIR:-$PWD/tmp/k6-results/$(date +%Y%m%d-%H%M%S)}"
K6_BIN="${K6_BIN:-}"

if [[ -z "$K6_BIN" ]]; then
  if command -v k6 >/dev/null 2>&1; then
    K6_BIN="$(command -v k6)"
  else
    echo "k6 not found. Install k6 or set K6_BIN." >&2
    echo "macOS: brew install k6" >&2
    echo "docs: https://grafana.com/docs/k6/latest/set-up/install-k6/" >&2
    exit 1
  fi
fi

ARGS=("$@")
if [[ ${#ARGS[@]} -eq 0 ]]; then
  shopt -s nullglob
  TARGET_DIRS=(tests/*/k6)
  shopt -u nullglob
  if [[ ${#TARGET_DIRS[@]} -gt 0 ]]; then
    ARGS=("${TARGET_DIRS[@]}")
  else
    echo "No target-owned k6 directories found. Expected tests/<target>/k6." >&2
    exit 1
  fi
fi

case "${ARGS[0]}" in
  -h|--help|-v|--version|version)
    exec "$K6_BIN" "${ARGS[@]}"
    ;;
  run)
    ARGS=("${ARGS[@]:1}")
    ;;
esac

mkdir -p "$OUTPUT_DIR"

has_arg() {
  local needle="$1"
  shift
  for arg in "$@"; do
    if [[ "$arg" == "$needle" || "$arg" == "$needle="* ]]; then
      return 0
    fi
  done
  return 1
}

script_name_for() {
  local fallback="$1"
  shift
  for arg in "$@"; do
    if [[ "$arg" == *.js ]]; then
      basename "$arg" .js
      return
    fi
  done
  echo "$fallback"
}

run_one() {
  local fallback="$1"
  shift
  local -a run_args=("$@")
  local script_name
  script_name="$(script_name_for "$fallback" "${run_args[@]}")"
  local summary="$OUTPUT_DIR/${script_name}-summary.json"

  if ! has_arg "--summary-export" "${run_args[@]}"; then
    run_args=("--summary-export" "$summary" "${run_args[@]}")
  fi
  if [[ -n "${K6_OUT:-}" ]] && ! has_arg "--out" "${run_args[@]}"; then
    run_args=("--out" "$K6_OUT" "${run_args[@]}")
  fi

  echo "-> $K6_BIN run ${run_args[*]}" >&2
  "$K6_BIN" run "${run_args[@]}"
}

all_paths() {
  for arg in "$@"; do
    if [[ ! -d "$arg" && "$arg" != *.js ]]; then
      return 1
    fi
  done
  return 0
}

run_path() {
  local target="$1"
  if [[ -d "$target" ]]; then
    local found=0
    for script in "$target"/*.js; do
      [[ -e "$script" ]] || continue
      found=1
      run_one "$(basename "$script" .js)" "$script"
    done
    if [[ "$found" == "0" ]]; then
      echo "No k6 scripts found in $target." >&2
      exit 1
    fi
  else
    run_one "$(basename "$target" .js)" "$target"
  fi
}

if [[ ${#ARGS[@]} -gt 0 ]] && all_paths "${ARGS[@]}"; then
  found=0
  for target in "${ARGS[@]}"; do
    found=1
    run_path "$target"
  done
  if [[ "$found" == "0" ]]; then
    echo "No k6 scripts found." >&2
    exit 1
  fi
else
  run_one "k6" "${ARGS[@]}"
fi

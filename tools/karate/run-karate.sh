#!/usr/bin/env bash
set -euo pipefail

VERSION="${KARATE_VERSION:-2.0.0}"
CACHE_DIR="${KARATE_CACHE_DIR:-$HOME/.reaktor/karate}"
JAR="${KARATE_JAR:-$CACHE_DIR/karate-$VERSION.jar}"
REPORT_DIR="${KARATE_REPORT_DIR:-$PWD/tmp/karate-reports/$(date +%Y%m%d-%H%M%S)}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOGBACK_CONFIG="${KARATE_LOGBACK_CONFIG:-$SCRIPT_DIR/logback-quiet.xml}"

if ! command -v java >/dev/null 2>&1; then
  echo "java is required to run Karate. Install Java 21+ or set KARATE_JAR to a local Karate standalone jar." >&2
  exit 1
fi

if [[ -z "${KARATE_JAR:-}" && ! -f "$JAR" ]]; then
  mkdir -p "$CACHE_DIR"
  URL="https://github.com/karatelabs/karate/releases/download/v$VERSION/karate-$VERSION.jar"
  echo "Downloading Karate $VERSION -> $JAR" >&2
  curl -fL "$URL" -o "$JAR"
fi

if [[ ! -f "$JAR" ]]; then
  echo "Karate jar not found at $JAR. Set KARATE_JAR or allow the runner to download it." >&2
  exit 1
fi

HAS_OUTPUT=0
HAS_RUNTIME_LOG_LEVEL=0
HAS_REPORT_LOG_LEVEL=0
for arg in "$@"; do
  if [[ "$arg" == "--output" || "$arg" == "-o" || "$arg" == --output=* ]]; then
    HAS_OUTPUT=1
  fi
  if [[ "$arg" == "--runtime-log-level" || "$arg" == --runtime-log-level=* ]]; then
    HAS_RUNTIME_LOG_LEVEL=1
  fi
  if [[ "$arg" == "--report-log-level" || "$arg" == --report-log-level=* ]]; then
    HAS_REPORT_LOG_LEVEL=1
  fi
done

ARGS=("$@")
if [[ ${#ARGS[@]} -eq 0 ]]; then
  shopt -s nullglob
  TARGET_DIRS=(tests/*/karate)
  shopt -u nullglob
  if [[ ${#TARGET_DIRS[@]} -gt 0 ]]; then
    ARGS=()
    if [[ -d tests/support/karate ]]; then
      ARGS+=("--configdir" "tests/support/karate")
    fi
    ARGS+=("${TARGET_DIRS[@]}")
  else
    echo "No target-owned Karate directories found. Expected tests/<target>/karate." >&2
    exit 1
  fi
fi

case "${ARGS[0]}" in
  run|mock|clean|-h|--help|-V|--version)
    ;;
  *)
    ARGS=("run" "${ARGS[@]}")
    ;;
esac

if [[ "${ARGS[0]}" == "run" ]]; then
  if [[ "$HAS_RUNTIME_LOG_LEVEL" == "0" ]]; then
    ARGS=("run" "--runtime-log-level=warn" "${ARGS[@]:1}")
  fi
  if [[ "$HAS_REPORT_LOG_LEVEL" == "0" ]]; then
    ARGS=("run" "--report-log-level=warn" "${ARGS[@]:1}")
  fi
fi

echo "-> java -jar $JAR ${ARGS[*]}" >&2
JAVA_ARGS=()
if [[ -f "$LOGBACK_CONFIG" ]]; then
  JAVA_ARGS+=("-Dlogback.configurationFile=$LOGBACK_CONFIG")
fi

if [[ "$HAS_OUTPUT" == "1" ]]; then
  exec java "${JAVA_ARGS[@]}" -jar "$JAR" "${ARGS[@]}"
else
  mkdir -p "$REPORT_DIR"
  if [[ "${ARGS[0]}" == "run" ]]; then
    exec java "${JAVA_ARGS[@]}" -jar "$JAR" run --output "$REPORT_DIR" "${ARGS[@]:1}"
  else
    exec java "${JAVA_ARGS[@]}" -jar "$JAR" "${ARGS[@]}"
  fi
fi

#!/usr/bin/env bash
#
# Generate flamecharts for all Kotlin Multiplatform targets.
#
# Each target has different performance characteristics:
#   JVM     — HotSpot C2 JIT, G1 GC, escape analysis, scalar replacement
#   Android — Same HotSpot for unit tests; ART + Bionic for device (separate script)
#   Native  — Kotlin/Native (LLVM backend), no GC pauses, no JIT warmup
#   JS      — V8 TurboFan JIT, BigInt longs, UTF-16 strings, ArrayBuffer bytes
#
# Usage:
#   cd reaktor-flexbuffer
#   ./flamechart/generate-all.sh           # Generate all flamecharts
#   ./flamechart/generate-all.sh jvm       # Only JVM
#   ./flamechart/generate-all.sh js        # Only JS
#   ./flamechart/generate-all.sh native    # Only Native (iOS simulator)
#   ./flamechart/generate-all.sh android   # Only Android unit tests
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
ROOT_DIR="$(cd "$PROJECT_DIR/.." && pwd)"
OUTPUT_DIR="$SCRIPT_DIR/output"

mkdir -p "$OUTPUT_DIR"

cd "$ROOT_DIR"

TARGET="${1:-all}"

echo "╔══════════════════════════════════════════════════════════╗"
echo "║  FlexBuffer / JSON Flamechart Generator                  ║"
echo "║  Output: $OUTPUT_DIR"
echo "╚══════════════════════════════════════════════════════════╝"
echo ""

run_jvm() {
    echo "━━━ [JVM] HotSpot flamechart ━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    bash "$SCRIPT_DIR/generate-jvm.sh"
    echo ""
}

run_android() {
    echo "━━━ [Android] Unit test JVM flamechart ━━━━━━━━━━━━━━━━━━"
    bash "$SCRIPT_DIR/generate-android.sh"
    echo ""
}

run_native() {
    echo "━━━ [Native] Kotlin/Native (iOS simulator) flamechart ━━"
    bash "$SCRIPT_DIR/generate-native.sh"
    echo ""
}

run_js() {
    echo "━━━ [JS] V8/Node.js flamechart ━━━━━━━━━━━━━━━━━━━━━━━━━"
    bash "$SCRIPT_DIR/generate-js.sh"
    echo ""
}

case "$TARGET" in
    jvm)     run_jvm ;;
    android) run_android ;;
    native)  run_native ;;
    js)      run_js ;;
    all)
        run_jvm
        run_android
        run_native
        run_js
        ;;
    *)
        echo "Unknown target: $TARGET"
        echo "Usage: $0 [jvm|android|native|js|all]"
        exit 1
        ;;
esac

echo "╔══════════════════════════════════════════════════════════╗"
echo "║  Flamechart generation complete!                         ║"
echo "║  Output files:                                           ║"
for f in "$OUTPUT_DIR"/*.html "$OUTPUT_DIR"/*.cpuprofile "$OUTPUT_DIR"/*.txt 2>/dev/null; do
    [ -f "$f" ] && echo "║    $(basename "$f")"
done
echo "╚══════════════════════════════════════════════════════════╝"

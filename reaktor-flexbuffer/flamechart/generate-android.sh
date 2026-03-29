#!/usr/bin/env bash
#
# Android Flamechart — profiles FlexBuffer & JSON encode/decode on a real Android device
# using simpleperf for true ART runtime profiling.
#
# Prerequisites:
#   - Connected Android device (adb devices shows it)
#   - App installed: ./gradlew :reaktor-flexbuffer:installDebugAndroidTest
#   - NDK installed (for report_html.py)
#
# What this captures:
#   - Real ART JIT-compiled code (not HotSpot JVM)
#   - JNI trampolines, GC pressure, interpreter fallbacks
#   - arm64 native code paths
#
# Output:
#   flamechart/output/android-flamechart.html   (interactive HTML flamegraph)
#   flamechart/output/android-simpleperf-report.txt (text report of top functions)
#   flamechart/output/android-perf.data         (raw simpleperf data)
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
ROOT_DIR="$(cd "$PROJECT_DIR/.." && pwd)"
OUTPUT_DIR="$SCRIPT_DIR/output"
mkdir -p "$OUTPUT_DIR"

cd "$ROOT_DIR"

# ── Step 1: Build & install instrumented test APK ──────────────────────
echo "[Android] Building and installing instrumented test APK..."
./gradlew :reaktor-flexbuffer:installDebugAndroidTest 2>&1 | tail -5

# ── Step 2: Start simpleperf recording on device ──────────────────────
TEST_PKG="dev.shibasis.reaktor.core.test"
TEST_RUNNER="androidx.test.runner.AndroidJUnitRunner"
TEST_CLASS="dev.shibasis.reaktor.flexbuffer.FlexBuffersV2Benchmarks"

echo "[Android] Starting simpleperf recording..."
adb shell simpleperf record \
    --app "$TEST_PKG" \
    --add-meta-info app_type=debuggable \
    --in-app \
    --tracepoint-events /data/local/tmp/tracepoint_events \
    -g --duration 30 \
    -o /data/local/tmp/perf.data &
SIMPLEPERF_PID=$!

sleep 2

echo "[Android] Running instrumented tests on device..."
adb shell am instrument -w \
    -e class "$TEST_CLASS" \
    "$TEST_PKG/$TEST_RUNNER" \
    2>&1 | tee "$OUTPUT_DIR/android-console.txt"

# Wait for simpleperf to finish
wait $SIMPLEPERF_PID 2>/dev/null || true

# ── Step 3: Pull data and generate reports ─────────────────────────────
echo "[Android] Pulling profiling data..."
adb pull /data/local/tmp/perf.data "$OUTPUT_DIR/android-perf.data"
adb shell rm /data/local/tmp/perf.data 2>/dev/null || true

echo "[Android] Generating text report..."
adb shell simpleperf report \
    -i /data/local/tmp/perf.data \
    --sort comm,dso,symbol \
    -n 40 \
    > "$OUTPUT_DIR/android-simpleperf-report.txt" 2>/dev/null || true

# ── Step 4: Generate interactive HTML flamegraph ───────────────────────
NDK_DIR=$(ls -d "$HOME/Library/Android/sdk/ndk/"* 2>/dev/null | sort -V | tail -1)
REPORT_HTML="$NDK_DIR/simpleperf/report_html.py"

if [ -f "$REPORT_HTML" ]; then
    echo "[Android] Generating HTML flamegraph..."
    python3 "$REPORT_HTML" \
        -i "$OUTPUT_DIR/android-perf.data" \
        -o "$OUTPUT_DIR/android-flamechart.html"
    echo "[Android] Flamechart: $OUTPUT_DIR/android-flamechart.html"
else
    echo "[Android] WARNING: report_html.py not found in NDK. Install NDK for HTML flamegraph."
    echo "[Android] Raw data available at: $OUTPUT_DIR/android-perf.data"
fi

echo "[Android] Done."

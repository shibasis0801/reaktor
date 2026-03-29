#!/usr/bin/env bash
#
# JVM Flamechart — profiles FlexBuffer & JSON encode/decode on HotSpot.
#
# Profiler: async-profiler (asprof)
# Output:   flamechart/output/jvm-flamechart.html   (interactive flamegraph)
#           flamechart/output/jvm-flamechart.jfr     (JFR for IntelliJ import)
#
# What to look for in the flamechart:
#   - FlexEncoderV2.encodeElement → hot encoding path
#   - FlexDecoderV2.decodeElementIndex → hot decoding path
#   - FlexBuffersBuilder.* → Google builder overhead
#   - FlexBufferPool.acquire/release → pool lock contention (should be minimal)
#   - kotlinx.serialization.internal.* → generated serializer dispatch
#   - java.util.Arrays.* / System.arraycopy → buffer resizing
#   - GC frames → allocation pressure
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
ROOT_DIR="$(cd "$PROJECT_DIR/.." && pwd)"
OUTPUT_DIR="$SCRIPT_DIR/output"
mkdir -p "$OUTPUT_DIR"

cd "$ROOT_DIR"

# Check for async-profiler
ASPROF="${ASPROF_BIN:-$(command -v asprof 2>/dev/null || echo "")}"

if [ -z "$ASPROF" ]; then
    # Try common homebrew locations
    for candidate in /opt/homebrew/bin/asprof /usr/local/bin/asprof; do
        [ -x "$candidate" ] && ASPROF="$candidate" && break
    done
fi

echo "[JVM] Building JVM target..."
./gradlew :reaktor-flexbuffer:jvmMainClasses --quiet 2>&1 || true

# Resolve classpath
CLASSPATH=$(./gradlew :reaktor-flexbuffer:jvmFlameChart --dry-run 2>&1 | head -1 || echo "")

# Use Gradle task directly if async-profiler lib is available
ASPROF_LIB="${ASPROF_LIB:-}"
if [ -z "$ASPROF_LIB" ]; then
    for candidate in \
        /opt/homebrew/lib/libasyncProfiler.dylib \
        /usr/local/lib/libasyncProfiler.dylib \
        "$HOME/.local/lib/libasyncProfiler.dylib"; do
        [ -f "$candidate" ] && ASPROF_LIB="$candidate" && break
    done
fi

if [ -n "$ASPROF_LIB" ]; then
    echo "[JVM] async-profiler found: $ASPROF_LIB"
    echo "[JVM] Running benchmark with inline profiling agent..."
    ASPROF_LIB="$ASPROF_LIB" ./gradlew :reaktor-flexbuffer:jvmFlameChart --no-daemon 2>&1 | tee "$OUTPUT_DIR/jvm-console.txt"

    if [ -f "$OUTPUT_DIR/jvm-flamechart.html" ]; then
        echo "[JVM] Flamechart: $OUTPUT_DIR/jvm-flamechart.html"
    else
        echo "[JVM] WARNING: HTML flamechart not generated (async-profiler agent may have failed)"
    fi
elif [ -n "$ASPROF" ]; then
    echo "[JVM] async-profiler CLI found: $ASPROF"
    echo "[JVM] Running benchmark and attaching profiler externally..."

    # Run benchmark in background via Gradle
    ./gradlew :reaktor-flexbuffer:jvmFlameChart --no-daemon &
    GRADLE_PID=$!

    # Wait for JVM to start
    sleep 3

    # Find the actual JVM process (child of Gradle)
    JVM_PID=$(pgrep -f "JvmFlameChartKt" | head -1 || echo "")
    if [ -z "$JVM_PID" ]; then
        # Fallback: profile the Gradle worker
        JVM_PID=$(pgrep -f "GradleWorkerMain" | tail -1 || echo "")
    fi

    if [ -n "$JVM_PID" ]; then
        echo "[JVM] Profiling PID $JVM_PID for 60 seconds..."
        "$ASPROF" -d 60 -f "$OUTPUT_DIR/jvm-flamechart.html" "$JVM_PID" 2>&1 || true
        # Also generate JFR for IntelliJ
        "$ASPROF" -d 30 -o jfr -f "$OUTPUT_DIR/jvm-flamechart.jfr" "$JVM_PID" 2>&1 || true
    else
        echo "[JVM] WARNING: Could not find JVM process to profile"
    fi

    wait "$GRADLE_PID" 2>/dev/null || true
else
    echo "[JVM] WARNING: async-profiler not found."
    echo "[JVM] Running benchmark without profiling (timing only)..."
    echo "[JVM] Install: brew install async-profiler"
    ./gradlew :reaktor-flexbuffer:jvmFlameChart --no-daemon 2>&1 | tee "$OUTPUT_DIR/jvm-console.txt"
fi

echo "[JVM] Done."

#!/usr/bin/env bash
#
# Native Flamechart — profiles Kotlin/Native FlexBuffer encode/decode.
#
# Profiler: macOS `sample` command (built-in, no install needed)
# Target:   iOS Simulator arm64 test binary (Kotlin/Native compiled via LLVM)
# Output:   flamechart/output/native-sample.txt   (call tree with kfun: frames)
#           flamechart/output/native-console.txt   (benchmark timing output)
#
# What to look for in the sample output:
#   - kfun:dev.shibasis.reaktor.flexbuffer.core.FlexEncoderV2 — hot encoding path
#   - kfun:dev.shibasis.reaktor.flexbuffer.core.FlexDecoderV2 — hot decoding path
#   - kfun:com.google.flatbuffers.kotlin.FlexBuffersBuilder — builder overhead
#   - kfun:kotlinx.serialization.internal — generated serializer dispatch
#   - kotlin_alloc_impl / UpdateHeapRef — GC/allocation pressure
#   - No JIT frames — all code is AOT compiled by LLVM
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
ROOT_DIR="$(cd "$PROJECT_DIR/.." && pwd)"
OUTPUT_DIR="$SCRIPT_DIR/output"
mkdir -p "$OUTPUT_DIR"

cd "$ROOT_DIR"

echo "[Native] Building iOS Simulator arm64 test binary..."
./gradlew :reaktor-flexbuffer:iosSimulatorArm64TestBinaries --quiet 2>&1 || {
    echo "[Native] Build failed."
    exit 1
}

TEST_BINARY="$PROJECT_DIR/build/bin/iosSimulatorArm64/debugTest/test.kexe"
if [ ! -f "$TEST_BINARY" ]; then
    echo "[Native] ERROR: Test binary not found at $TEST_BINARY"
    exit 1
fi

# Find a booted simulator or boot one
DEVICE_ID=$(xcrun simctl list devices booted -j | python3 -c "
import json,sys
data=json.load(sys.stdin)
for runtime,devs in data.get('devices',{}).items():
    for d in devs:
        if d.get('state')=='Booted':
            print(d['udid']); sys.exit()
" 2>/dev/null || echo "")

if [ -z "$DEVICE_ID" ]; then
    DEVICE_ID=$(xcrun simctl list devices available -j | python3 -c "
import json,sys
data=json.load(sys.stdin)
for runtime,devs in data.get('devices',{}).items():
    if 'iOS' in runtime:
        for d in devs:
            if 'iPhone' in d.get('name',''):
                print(d['udid']); sys.exit()
" 2>/dev/null || echo "")
    if [ -n "$DEVICE_ID" ]; then
        echo "[Native] Booting simulator $DEVICE_ID..."
        xcrun simctl boot "$DEVICE_ID" 2>/dev/null || true
        sleep 3
    fi
fi

if [ -z "$DEVICE_ID" ]; then
    echo "[Native] ERROR: No iOS simulator available."
    exit 1
fi

echo "[Native] Simulator: $DEVICE_ID"
echo "[Native] Running benchmark in simulator..."

# Launch the test binary inside the simulator
xcrun simctl spawn "$DEVICE_ID" "$TEST_BINARY" \
    --ktest_filter="dev.shibasis.reaktor.flexbuffer.FlexBuffersV2Benchmarks.*" \
    > "$OUTPUT_DIR/native-console.txt" 2>&1 &
XCRUN_PID=$!

sleep 1

# Find the actual test.kexe process running under launchd_sim
NATIVE_PID=$(ps aux | grep "test.kexe" | grep -v grep | grep -v simctl | awk '{print $2}' | head -1 || echo "")

if [ -z "$NATIVE_PID" ]; then
    NATIVE_PID=$(pgrep -P "$XCRUN_PID" 2>/dev/null | head -1 || echo "")
fi

if [ -n "$NATIVE_PID" ]; then
    echo "[Native] Sampling PID $NATIVE_PID for 30 seconds..."
    sample "$NATIVE_PID" 30 -file "$OUTPUT_DIR/native-sample.txt" 2>&1 || true

    FRAME_COUNT=$(grep -c "kfun:" "$OUTPUT_DIR/native-sample.txt" 2>/dev/null || echo "0")
    echo "[Native] Sample saved — $FRAME_COUNT Kotlin/Native frames captured"
else
    echo "[Native] WARNING: Could not find test process for sampling"
fi

wait $XCRUN_PID 2>/dev/null || true

echo "[Native] Benchmark results:"
cat "$OUTPUT_DIR/native-console.txt"
echo "[Native] Done."

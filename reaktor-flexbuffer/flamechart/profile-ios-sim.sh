#!/usr/bin/env bash
# Profile the iOS sim FlexBuffers bench with macOS `sample`.
#
# Usage:
#   ./flamechart/profile-ios-sim.sh [CASE] [OP] [ITERS] [SAMPLE_SEC]
#
# Args:
#   CASE       — userprofile | apiresponse | chatthread | timeseries | all (default)
#   OP         — encode | decode | both (default)
#   ITERS      — iterations per round (default 5000000 — long enough to span sampling)
#   SAMPLE_SEC — sampling duration in seconds (default 10)
#
# Output: flamechart/output/ios/ios-sim-sample.txt with sample's tree-form output.
# The "Sort by top of stack" section near the bottom is the flat self-time profile.

set -euo pipefail

CASE="${1:-all}"
OP="${2:-both}"
ITERS="${3:-5000000}"
SAMPLE_SEC="${4:-10}"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
BIN="$PROJECT_DIR/build/bin/iosSimulatorArm64/benchReleaseExecutable/bench.kexe"
OUT_DIR="$SCRIPT_DIR/output/ios"
OUT_FILE="$OUT_DIR/ios-sim-sample.txt"

if [[ ! -x "$BIN" ]]; then
    echo "Bench binary not found at $BIN"
    echo "Build with: ./gradlew :reaktor-flexbuffer:linkBenchReleaseExecutableIosSimulatorArm64"
    exit 1
fi

mkdir -p "$OUT_DIR"

# Find any booted iPhone simulator
SIM_ID=$(xcrun simctl list devices booted | awk -F '[()]' '/iPhone/ {print $(NF-3); exit}')
if [[ -z "${SIM_ID:-}" ]]; then
    # Boot first iPhone if none is booted
    SIM_ID=$(xcrun simctl list devices available | awk -F '[()]' '/iPhone 16 Pro \(/ {print $(NF-3); exit}')
    [[ -z "$SIM_ID" ]] && { echo "No iPhone simulator found"; exit 1; }
    echo "Booting simulator $SIM_ID..."
    xcrun simctl boot "$SIM_ID"
    xcrun simctl bootstatus "$SIM_ID" -b
fi

echo "Sim: $SIM_ID"
echo "Bench: $BIN"
echo "Args:  case=$CASE op=$OP iters=$ITERS  sampling=${SAMPLE_SEC}s"
echo

# Spawn the bench in the sim with env vars (simctl prefixes child env with SIMCTL_CHILD_)
SIMCTL_CHILD_BENCH_CASE="$CASE" \
SIMCTL_CHILD_BENCH_OP="$OP" \
SIMCTL_CHILD_BENCH_ITERS="$ITERS" \
xcrun simctl spawn "$SIM_ID" "$BIN" &
WRAPPER_PID=$!

# Give the process time to start
sleep 3

# Find the actual bench.kexe PID (child of launchd_sim)
BENCH_PID=$(ps -ef | grep "bench.kexe" | grep -v grep | awk '{print $2}' | head -1)
if [[ -z "${BENCH_PID:-}" ]]; then
    echo "Could not find bench.kexe PID"
    kill "$WRAPPER_PID" 2>/dev/null || true
    exit 1
fi
echo "Sampling PID $BENCH_PID for ${SAMPLE_SEC}s..."

sample "$BENCH_PID" "$SAMPLE_SEC" 1 -file "$OUT_FILE"

# Clean up
kill "$BENCH_PID" 2>/dev/null || true
wait "$WRAPPER_PID" 2>/dev/null || true

echo
echo "==============================="
echo "Top-of-stack self-time profile:"
echo "==============================="
grep -A 60 "Sort by top of stack" "$OUT_FILE" | head -50 || true

echo
echo "Full output: $OUT_FILE"
echo
echo "Tip: for an Instruments-grade Time Profiler trace:"
echo "  xctrace record --template 'Time Profiler' --launch-process $BIN --output trace.xctrace"
echo "  open trace.xctrace"

#!/usr/bin/env bash
#
# JS Flamechart — profiles Kotlin/JS FlexBuffer encode/decode on Node.js (V8).
#
# Profiler: Node.js built-in --cpu-prof (V8 CPU profiler)
# Output:   flamechart/output/js-*.cpuprofile  (import into Chrome DevTools)
#           flamechart/output/js-console.txt    (timing results)
#
# What to look for in the flamechart (Chrome DevTools → Performance tab → Load profile):
#   - Kotlin IR-generated code patterns (mangled function names)
#   - V8 TurboFan optimized vs deoptimized functions
#   - BigInt operations (Kotlin Long → JS BigInt via -Xes-long-as-bigint)
#   - ArrayBuffer / DataView operations (FlexBuffer byte manipulation)
#   - String encoding overhead (Kotlin uses UTF-8, JS uses UTF-16 internally)
#   - GC pauses (minor GC = Scavenge, major GC = Mark-Compact)
#
# Key differences from JVM:
#   - V8 JIT is tiered: Sparkplug → Maglev → TurboFan (vs HotSpot C1 → C2)
#   - No value types — everything is heap-allocated objects or SMIs
#   - Maps and objects use V8 hidden classes (shape transitions)
#   - BigInt arithmetic is significantly slower than native Long on JVM
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
ROOT_DIR="$(cd "$PROJECT_DIR/.." && pwd)"
OUTPUT_DIR="$SCRIPT_DIR/output"
mkdir -p "$OUTPUT_DIR"

cd "$ROOT_DIR"

echo "[JS] Building Kotlin/JS (IR) Node.js distribution..."
./gradlew :reaktor-flexbuffer:jsNodeProductionLibraryDistribution --quiet 2>&1 || {
    echo "[JS] Production build failed, trying development build..."
    ./gradlew :reaktor-flexbuffer:jsNodeDevelopmentLibraryDistribution --quiet 2>&1 || {
        echo "[JS] JS build failed. Check that 'web {}' target is configured."
        exit 1
    }
}

# Find the compiled JS output
JS_OUTPUT=""
for candidate in \
    "$PROJECT_DIR/build/dist/js/productionLibrary/reaktor-flexbuffer.mjs" \
    "$PROJECT_DIR/build/dist/js/productionLibrary/reaktor-flexbuffer.js" \
    "$PROJECT_DIR/build/dist/js/developmentLibrary/reaktor-flexbuffer.mjs" \
    "$PROJECT_DIR/build/dist/js/developmentLibrary/reaktor-flexbuffer.js" \
    "$PROJECT_DIR/build/compileSync/js/main/productionLibrary/kotlin/reaktor-flexbuffer.mjs" \
    "$PROJECT_DIR/build/compileSync/js/main/productionLibrary/kotlin/reaktor-flexbuffer.js"; do
    [ -f "$candidate" ] && JS_OUTPUT="$candidate" && break
done

if [ -z "$JS_OUTPUT" ]; then
    echo "[JS] WARNING: Could not find compiled JS output."
    echo "[JS] Searching for any .mjs/.js output..."
    JS_OUTPUT=$(find "$PROJECT_DIR/build" -name "*.mjs" -path "*/js/*" 2>/dev/null | head -1 || echo "")
    if [ -z "$JS_OUTPUT" ]; then
        JS_OUTPUT=$(find "$PROJECT_DIR/build" -name "reaktor-flexbuffer*.js" -path "*/js/*" 2>/dev/null | head -1 || echo "")
    fi
fi

if [ -z "$JS_OUTPUT" ]; then
    echo "[JS] ERROR: No JS output found. Build may have failed."
    exit 1
fi

echo "[JS] Found JS output: $JS_OUTPUT"

# Create a Node.js runner script that imports and runs the benchmark
JS_DIR="$(dirname "$JS_OUTPUT")"
RUNNER="$OUTPUT_DIR/bench-runner.mjs"

cat > "$RUNNER" << 'RUNNER_EOF'
// Auto-generated Node.js benchmark runner for FlexBuffer flamechart profiling.
// Import the Kotlin/JS compiled module and run the benchmark.

import { createRequire } from 'module';
import { pathToFileURL } from 'url';
import path from 'path';

const args = process.argv.slice(2);
const jsOutputPath = args[0];

if (!jsOutputPath) {
    console.error('Usage: node bench-runner.mjs <path-to-kotlin-js-output>');
    process.exit(1);
}

try {
    // Dynamic import of the Kotlin/JS module
    const moduleUrl = pathToFileURL(path.resolve(jsOutputPath)).href;
    const mod = await import(moduleUrl);

    // The @JsExport function should be accessible on the module
    // Kotlin/JS IR exports to the module's default or named exports
    const lib = mod.default || mod;

    // Try to find the exported benchmark function
    const benchFn =
        lib?.dev?.shibasis?.reaktor?.flexbuffer?.bench?.runJsFlameChart ||
        lib?.runJsFlameChart ||
        lib?.reaktor_flexbuffer?.dev?.shibasis?.reaktor?.flexbuffer?.bench?.runJsFlameChart;

    if (typeof benchFn === 'function') {
        console.log('[JS Runner] Found exported benchmark function, executing...');
        benchFn();
    } else {
        console.log('[JS Runner] Exported function not found. Available exports:');
        console.log(Object.keys(lib || {}).slice(0, 20));

        // Fallback: try to find any benchmark-related export
        function findBenchmark(obj, prefix = '') {
            if (!obj || typeof obj !== 'object') return null;
            for (const key of Object.keys(obj)) {
                if (key.includes('FlameChart') || key.includes('flamechart') || key.includes('runJs')) {
                    const val = obj[key];
                    if (typeof val === 'function') return val;
                }
                if (typeof obj[key] === 'object') {
                    const found = findBenchmark(obj[key], prefix + key + '.');
                    if (found) return found;
                }
            }
            return null;
        }

        const fallback = findBenchmark(lib);
        if (fallback) {
            console.log('[JS Runner] Found fallback benchmark function, executing...');
            fallback();
        } else {
            console.error('[JS Runner] ERROR: No benchmark function found in module exports.');
            console.error('[JS Runner] Module structure:', JSON.stringify(Object.keys(lib || {})));
            process.exit(1);
        }
    }
} catch (err) {
    console.error('[JS Runner] Failed to load or run benchmark:', err.message);
    console.error(err.stack);
    process.exit(1);
}
RUNNER_EOF

echo "[JS] Running benchmark with V8 CPU profiler..."
echo "[JS] (This generates a .cpuprofile file — open in Chrome DevTools → Performance tab)"

# Run with CPU profiling enabled
node \
    --cpu-prof \
    --cpu-prof-dir="$OUTPUT_DIR" \
    --cpu-prof-name="js-flamechart.cpuprofile" \
    --max-old-space-size=4096 \
    "$RUNNER" "$JS_OUTPUT" \
    2>&1 | tee "$OUTPUT_DIR/js-console.txt"

# Check for output
CPUPROFILE=$(find "$OUTPUT_DIR" -name "*.cpuprofile" -newer "$RUNNER" 2>/dev/null | head -1 || echo "")
if [ -n "$CPUPROFILE" ]; then
    # Rename to a predictable name if needed
    if [ "$(basename "$CPUPROFILE")" != "js-flamechart.cpuprofile" ]; then
        mv "$CPUPROFILE" "$OUTPUT_DIR/js-flamechart.cpuprofile" 2>/dev/null || true
    fi
    echo "[JS] CPU profile: $OUTPUT_DIR/js-flamechart.cpuprofile"
    echo "[JS] Open in Chrome DevTools: DevTools → Performance → Load profile..."
    echo "[JS] Or use: npx speedscope $OUTPUT_DIR/js-flamechart.cpuprofile"
else
    echo "[JS] WARNING: CPU profile not generated."
fi

echo "[JS] Done."

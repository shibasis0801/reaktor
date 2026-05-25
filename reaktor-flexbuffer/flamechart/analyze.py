#!/usr/bin/env python3
"""
Analyze async-profiler collapsed-format flamegraph files to find hot frames.

Usage: python3 analyze.py [--top 20] [--mode self|inclusive] dir

For each collapsed file in `dir`, prints:
  - Total samples (excluding C2 compiler threads)
  - Top N frames by self-time (samples where this frame is the leaf)
  - Top N frames by inclusive time (samples where this frame appears anywhere in stack)
  - Top N allocation sites (for *-alloc.collapsed.txt files)

Filters out the C2 compiler threads (CompileBroker::*, thread_start, etc.) which
just represent JIT compilation noise overlapping the benchmark, not real time.
"""
import os, re, sys
from collections import defaultdict

NOISE_PREFIXES = (
    "thread_start", "_pthread_start", "thread_native_entry",
    "CompileBroker", "C2Compiler", "Compile::", "Compile ", "PhaseIdealLoop",
    "PhaseCFG", "PhaseChaitin", "PhaseLive", "ConnectionGraph", "PhaseOutput",
    "G1BarrierSetC2", "BarrierSetC2", "JavaThread::", "Thread::call_run",
    "BarrierSetAssembler", "Matcher",
)

def is_noise(stack):
    """Skip stacks that are pure JIT compiler activity."""
    # If ANY of the noise prefixes appears as a frame, skip
    for frame in stack:
        for prefix in NOISE_PREFIXES:
            if frame.startswith(prefix):
                return True
    return False

def short(frame):
    """Shorten dev/shibasis/... to ds/... and collapse $$Lambda."""
    f = frame
    f = re.sub(r"^dev/shibasis/reaktor/flexbuffer/", "rfb/", f)
    f = re.sub(r"^kotlinx/serialization/", "ks/", f)
    f = re.sub(r"\$\$Lambda\.0x[0-9a-f]+", "$Lambda", f)
    return f

def analyze(path, top=20):
    self_counts = defaultdict(int)
    incl_counts = defaultdict(int)
    total = 0
    with open(path) as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            *frames_str, count_str = line.rsplit(" ", 1)
            try:
                count = int(count_str)
            except ValueError:
                continue
            stack = frames_str[0].split(";") if frames_str else []
            if not stack:
                continue
            if is_noise(stack):
                continue
            total += count
            self_counts[short(stack[-1])] += count
            for frame in set(stack):
                incl_counts[short(frame)] += count
    return total, self_counts, incl_counts

def print_table(title, items, total, top=20):
    print(f"  {title}")
    print(f"  {'samples':>10} {'pct':>6}  frame")
    for frame, count in items[:top]:
        pct = 100.0 * count / total if total else 0
        print(f"  {count:>10} {pct:>5.1f}%  {frame}")
    print()

def main():
    args = sys.argv[1:]
    top = 15
    if "--top" in args:
        i = args.index("--top")
        top = int(args[i+1])
        args = args[:i] + args[i+2:]
    target = args[0] if args else "."
    files = []
    if os.path.isdir(target):
        for f in sorted(os.listdir(target)):
            if f.endswith(".collapsed.txt"):
                files.append(os.path.join(target, f))
    else:
        files = [target]
    for path in files:
        name = os.path.basename(path).replace(".collapsed.txt", "")
        total, self_c, incl_c = analyze(path, top)
        if total == 0:
            print(f"\n=== {name} ===  (no app samples)")
            continue
        print(f"\n=== {name} ===  total={total}")
        items_self = sorted(self_c.items(), key=lambda kv: -kv[1])
        items_incl = sorted(incl_c.items(), key=lambda kv: -kv[1])
        print_table(f"Top {top} by SELF samples", items_self, total, top)
        print_table(f"Top {top} by INCLUSIVE samples", items_incl, total, top)

if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Continue worker telemetry after the initial sampler's two-hour window ends."""
import json,time
import benchmark as b

trace=b.HERE/'worker-samples.jsonl'
stop=b.HERE/'stop-sampling'
deadline=time.monotonic()+4*3600
while time.monotonic()<deadline and not stop.exists():
    if not trace.exists() or time.time()-trace.stat().st_mtime>25:break
    time.sleep(5)
while time.monotonic()<deadline and not stop.exists():
    try:
        sample=b.system()
        with trace.open('a') as out:out.write(json.dumps(sample)+'\n')
    except Exception as error:
        print(type(error).__name__,str(error)[:300],flush=True)
    time.sleep(8)

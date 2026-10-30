#!/usr/bin/env python3
"""Profile one frozen, cache-disabled cold build; no timing treatment claim."""
import base64
import contextlib
import hashlib
import json
import subprocess
import time
from pathlib import Path
import performance_followup as p

identity = "bc1581702ae7a00ad4be18a9e37a2a4967501ab2ce563ca89823e523162bb882"
original = p.STATE
data = json.loads((p.HERE / "continuation-benchmark-snapshots.json").read_text())
with contextlib.ExitStack() as stack:
    stack.enter_context(p.workspace_storage.snapshot_pin(original, identity))
    stack.enter_context(p.workspace_storage.graph_activity(original))
    p.h.ROOT = p.materialize(identity)
    observer = p.h.ROOT / "reaktor/benchmark.init.gradle"
    observer.write_text(observer.read_text() + '''
def projectTimings = Collections.synchronizedList([])
def projectBegins = new ConcurrentHashMap<String, Long>()
gradle.beforeProject { project -> projectBegins.put(project.path, System.currentTimeMillis()) }
gradle.afterProject { project -> projectTimings.add([path:project.path, root:project.rootDir.name,
  start:projectBegins.remove(project.path), end:System.currentTimeMillis()]) }
gradle.buildFinished {
  new File(gradle.rootProject.buildDir, 'reports/benchmark/project-times.json').text = JsonOutput.toJson(projectTimings)
}
''')
    diagnostic = p.frozen.freeze(p.h)
    stack.enter_context(p.workspace_storage.snapshot_pin(original, diagnostic))
    data['variants'] = {'before': {'base': diagnostic}}
    started = time.monotonic()
    stack.enter_context(p.frozen.lease(original / 'worker-slot-1.lock'))
    stack.enter_context(p.h.worker_slot(1))
    p.STATE = p.TEMP / 'state'
    p.h.STATE = p.STATE
    for category in ('logs', 'artifacts', 'runs'):
        (p.STATE / category).mkdir(parents=True, exist_ok=True)
    reservation = {'slot': 1, 'queueSeconds': time.monotonic() - started,
                   'baseSnapshot': identity, 'diagnosticSnapshot': diagnostic,
                   'remote': p.h.REMOTE, 'scope': 'JDK21 profiled cold diagnostic; no before/after timing claim'}
    (p.HERE / 'cold-diagnostic-reservation.json').write_text(json.dumps(reservation, indent=2) + '\n')
    print(json.dumps(reservation), flush=True)
    print(json.dumps(p.remote('''import json,subprocess,sys
from pathlib import Path
home=Path(sys.argv[1]);source=Path(sys.argv[2]);home.mkdir(parents=True,exist_ok=True)
assert home.parent.name=='performance-followup-20261028' and home.name=='before'
for rel in ('wrapper','caches/modules-2'):
 src=source/rel;dest=home/rel
 if src.exists() and not dest.exists():
  dest.parent.mkdir(parents=True,exist_ok=True)
  subprocess.run(['/bin/cp','-cRp',str(src),str(dest)],check=True)
print(json.dumps({'seededDependencyArtifacts':True}))
''', p.h.BASE_REMOTE+'/.remote-dev/performance-followup-20261028/before', '/Users/ovd/.gradle')), flush=True)
    build = p.h.build
    def recorded_build(project, directory, args, **kwargs):
        p.remote('''import json,sys
from pathlib import Path
root=Path(sys.argv[1]);assert root.parent.name=='job-1' and root.name=='reaktor'
(root/'build').mkdir(parents=True,exist_ok=True)
print(json.dumps({'createdOwnedRecordingDirectory':True}))
''', p.h.REMOTE+'/reaktor')
        args = [arg + ' -XX:StartFlightRecording=filename=' + p.h.REMOTE + '/reaktor/build/cold-diagnostic.jfr,settings=profile,dumponexit=true'
                if arg.startswith('-Dorg.gradle.jvmargs=') else arg for arg in args]
        return build(project, directory, args, **kwargs)
    p.h.build = recorded_build
    row = p.run(data, 'before', 'cold', 90, cc=False, profile=True)
    (p.HERE / 'cold-diagnostic-result.json').write_text(json.dumps(row, indent=2) + '\n')
    payload = p.remote('''import base64,hashlib,json,subprocess,sys
from pathlib import Path
f=Path(sys.argv[1])/'build/cold-diagnostic.jfr'
assert f.is_file() and f.stat().st_size < 32_000_000
print(json.dumps({'bytes':f.stat().st_size,'sha256':hashlib.sha256(f.read_bytes()).hexdigest(),
 'data':base64.b64encode(f.read_bytes()).decode()}))
''', p.h.REMOTE + '/reaktor')
    raw = p.TEMP / 'cold-diagnostic-raw.jfr'
    raw.write_bytes(base64.b64decode(payload.pop('data')))
    kept = ['jdk.ExecutionSample', 'jdk.NativeMethodSample', 'jdk.ThreadPark',
            'jdk.JavaMonitorEnter', 'jdk.GarbageCollection', 'jdk.GCHeapSummary',
            'jdk.CPULoad', 'jdk.ThreadCPULoad']
    try:
        subprocess.run(['/Users/ovd/Library/Java/JavaVirtualMachines/temurin-25.0.2/Contents/Home/bin/jfr',
                        'scrub', '--include-events', ','.join(kept), str(raw),
                        str(p.HERE / 'cold-diagnostic.jfr')], check=True, capture_output=True)
    finally:
        raw.unlink(missing_ok=True)
    payload['originalRecordingSha256'] = payload.pop('sha256')
    payload['originalBytes'] = payload.pop('bytes')
    retained = p.HERE / 'cold-diagnostic.jfr'
    payload.update(sha256=hashlib.sha256(retained.read_bytes()).hexdigest(), bytes=retained.stat().st_size,
                   retainedEvents=kept, retention='JDK jfr scrub allowlist; environment, system properties, process and other event families excluded')
    (p.HERE / 'cold-diagnostic-recording.json').write_text(json.dumps(payload, indent=2) + '\n')
    print(json.dumps(payload), flush=True)

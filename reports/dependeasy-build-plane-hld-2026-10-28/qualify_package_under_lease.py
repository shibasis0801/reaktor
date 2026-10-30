#!/usr/bin/env python3
"""Build, check and collect the real ZIP before its shared slot can be trimmed."""
import contextlib
import hashlib
import json
import subprocess
import sys
import time
from pathlib import Path
import performance_followup as p

original_remote = p.remote
def remote_with_diagnostics(*args):
    try:
        return original_remote(*args)
    except subprocess.CalledProcessError as failure:
        print(failure.stderr or '', file=sys.stderr, flush=True)
        raise
p.remote = remote_with_diagnostics

p.h.PROJECTS = ['bestbuds', 'reaktor']
identity = sys.argv[1] if len(sys.argv) > 1 else p.frozen.freeze(p.h)
(p.HERE / 'qualified-package-source.json').write_text(json.dumps({'snapshot': identity}, indent=2) + '\n')
started = time.monotonic()
with contextlib.ExitStack() as stack:
    stack.enter_context(p.workspace_storage.snapshot_pin(p.GLOBAL_STATE, identity))
    stack.enter_context(p.workspace_storage.graph_activity(p.GLOBAL_STATE))
    stack.enter_context(p.frozen.lease(p.GLOBAL_STATE / 'worker-slot-1.lock'))
    stack.enter_context(p.h.worker_slot(1))
    admission = time.monotonic() - started
    p.workspace_storage.automatic(p.h, held_locks={'worker-slot-1'})
    p.h.ROOT = p.materialize(identity)
    p.h.sync(['bestbuds', 'reaktor'], frozen=True)
    p.frozen.verify_worker(p.h, identity)
    private = p.h.BASE_REMOTE + '/.remote-dev/performance-followup-20261028/package'
    p.h.ssh(p.h.shlex.join(['mkdir', '-p', private]))
    result = p.remote(r'''import hashlib,json,os,subprocess,sys,time,zipfile
from pathlib import Path
root=Path(sys.argv[1]); private=Path(sys.argv[2]); project=root/'bestbuds'
tasks=[':reaktorCli:test',':reaktor:reaktor-tooling:jvmTest',':kernel:verifyKernelBoundary',':reaktorCli:distZip','--continue','--max-workers=6','--console=plain']
start=time.monotonic()
with (private/'build.log').open('w') as log:
 result=subprocess.run([str(project/'gradlew'),*tasks],cwd=project,stdout=log,stderr=subprocess.STDOUT,timeout=1200)
summary={'exitCode':result.returncode,'workerBuildWallSeconds':time.monotonic()-start,'tasks':tasks}
if result.returncode==0:
 archives=list((project/'targets/reaktorCli/build/distributions').glob('*.zip'))
 assert len(archives)==1,archives
 archive=archives[0]
 qualified={f.name:hashlib.sha256(f.read_bytes()).hexdigest() for f in root.glob('reaktor/*/build/libs/*.jar')}
 for rel in ['targets/reaktorCli/build/libs/reaktorCli.jar','modules/kernel/build/libs/kernel.jar']:
  file=project/rel;qualified[file.name]=hashlib.sha256(file.read_bytes()).hexdigest()
 with zipfile.ZipFile(archive) as z:
  actual={Path(n).name:hashlib.sha256(z.read(n)).hexdigest() for n in z.namelist() if '/lib/' in n and n.endswith('.jar')}
 qualified={n:h for n,h in qualified.items() if n in actual}
 assert len(qualified)>=16 and all(actual.get(n)==h for n,h in qualified.items()),'Packaged library bytes differ from checked producers'
 summary.update(source=str(archive),sha256=hashlib.sha256(archive.read_bytes()).hexdigest(),bytes=archive.stat().st_size,verifiedLibraryHashes=qualified)
 with zipfile.ZipFile(private/'test-results.zip','w',zipfile.ZIP_DEFLATED) as z:
  for base in [project/'targets/reaktorCli/build/test-results/test',root/'reaktor/reaktor-tooling/build/test-results/jvmTest']:
   for file in base.glob('TEST-*.xml'):z.write(file,str(file.relative_to(root)))
(private/'result.json').write_text(json.dumps(summary,indent=2)+'\n')
print(json.dumps(summary))
''', p.h.REMOTE, private)
    for name in ['build.log', 'result.json'] + (['test-results.zip'] if result['exitCode'] == 0 else []):
        subprocess.run(['scp', '-q', p.h.HOST + ':' + private + '/' + name,
                        str(p.HERE / ('qualified-package-' + name))], check=True)
    assert result['exitCode'] == 0, result
    destination = p.DEV / '.remote-dev/artifacts/20261028-qualified-cli-worker/reaktor.zip'
    destination.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(['scp', '-q', p.h.HOST + ':' + result['source'], str(destination)], check=True)
    assert hashlib.sha256(destination.read_bytes()).hexdigest() == result['sha256']
    p.frozen.verify_worker(p.h, identity)
    result.update(destination=str(destination), snapshot=identity, workerSlot=1,
                  admissionSeconds=admission,
                  scope='Fresh checked Gradle distZip built and collected while holding the same worker lease. Previous shared-slot outputs had already been trimmed; no stale archive was substituted.')
    (p.HERE / 'qualified-archive-retrieval.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps({k:v for k,v in result.items() if k != 'verifiedLibraryHashes'}), flush=True)

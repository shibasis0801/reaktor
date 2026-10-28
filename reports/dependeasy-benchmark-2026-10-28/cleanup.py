#!/usr/bin/env python3
"""Remove only this experiment's owned scratch sources, caches and duplicated evidence."""
import contextlib,json,os,shutil,subprocess,time
from pathlib import Path
import benchmark as b
import workspace_storage

owned=set()
for name in ('snapshots.json','fixed-source/snapshots.json'):
    for values in json.loads((b.HERE/name).read_text())['snapshots'].values():owned.update(values.values())
rows=[]
for name in ('results.jsonl','fixed-source/results.jsonl'):
    rows.extend(json.loads(l) for l in (b.HERE/name).read_text().splitlines())
jobs=[]
for row in rows:
    path=row['receipt'].get('transportReceipt')
    if path:jobs.append(Path(path).stem)
report={'startedEpoch':time.time(),'localRemoved':[]}
with b.frozen.lease(b.h.STATE/'worker-gradle.lock'):
    report['worker']=b.remote('''import hashlib,json,os,shutil,signal,subprocess,sys
from pathlib import Path
owned=set(json.loads(sys.argv[1]));jobs=json.loads(sys.argv[2]);removed=[];stopped=[]
base=Path('/Users/ovd/dev/offload/.remote-dev/slots/worker')
cache=Path('/Users/ovd/dev/offload/.remote-dev/benchmark-20261028')
for line in subprocess.check_output(['/bin/ps','-ww','-axo','pid=,command='],text=True).splitlines():
 if 'org.jetbrains.kotlin.daemon.KotlinCompileDaemon' in line and str(cache)+'/' in line:
  pid=int(line.split(None,1)[0]);os.kill(pid,signal.SIGTERM);stopped.append(pid)
if cache.exists():shutil.rmtree(cache);removed.append(str(cache))
manifest=base/'.remote-dev/frozen-source.json'
identity=None
if manifest.exists():
 identity=hashlib.sha256(json.dumps(json.loads(manifest.read_text()),sort_keys=True,separators=(',',':')).encode()).hexdigest()
 if identity in owned:
  root=base/'reaktor'
  if root.exists():shutil.rmtree(root);removed.append(str(root))
  manifest.unlink();removed.append(str(manifest))
for name in jobs:
 assert '/' not in name and name.startswith('20261028-')
 p=base/'.remote-dev/jobs'/name
 if p.exists():shutil.rmtree(p);removed.append(str(p))
print(json.dumps({'removed':removed,'stoppedOwnedCompilerPids':stopped,'lastFrozenIdentity':identity,'ownedIdentity':identity in owned}))
''',json.dumps(sorted(owned)),json.dumps(jobs))
    slot=b.h.STATE/'slots/worker';manifest=slot/'source-manifest.json'
    if manifest.exists() and b.frozen.digest(json.loads(manifest.read_text())) in owned:
        target=slot/'reaktor'
        if target.exists():shutil.rmtree(target);report['localRemoved'].append(str(target))
        manifest.unlink();report['localRemoved'].append(str(manifest))
for identity in sorted(owned):
    path=b.h.STATE/'snapshots'/identity
    if path.exists():
        with workspace_storage.eviction_pin(path) as available:
            if not available:raise RuntimeError('Owned snapshot still pinned: '+identity)
            assert b.frozen.digest(json.loads((path/'manifest.json').read_text()))==identity
            shutil.rmtree(path);report['localRemoved'].append(str(path))
for row in rows:
    run=row['receipt']['run_id'];assert run.startswith('bench28-')
    path=b.h.STATE/'runs'/run
    if path.exists():shutil.rmtree(path);report['localRemoved'].append(str(path))
for job in jobs:
    for suffix in ('.json','.log'):
        path=b.h.STATE/'logs'/(job+suffix)
        if path.exists():path.unlink();report['localRemoved'].append(str(path))
if b.SOURCE.exists():
    subprocess.run(['git','-C',str(b.DEV/'reaktor'),'worktree','remove','--force',str(b.SOURCE)],check=True)
if b.SCRATCH.exists():shutil.rmtree(b.SCRATCH);report['localRemoved'].append(str(b.SCRATCH))
for path in b.HERE.rglob('__pycache__'):
    shutil.rmtree(path);report['localRemoved'].append(str(path))
report['finishedEpoch']=time.time();report['freeBytes']=shutil.disk_usage(b.DEV).free
(b.HERE/'cleanup.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(report,indent=2))

"""Collect the checked installDist under its original worker-slot lease."""
import contextlib,hashlib,json,shlex,subprocess,time
from pathlib import Path
import performance_followup as p
REPORT=Path(__file__).parent
RUN='20261029-114543-89cba13d-bestbuds'
collected=p.DEV/'.remote-dev/artifacts'/RUN
expected={}
for folder in [collected/'bestbuds/modules/kernel/build/libs',collected/'bestbuds/targets/reaktorCli/build/libs',collected/'reaktor/reaktor-conductor/build/libs']:
 for f in folder.glob('*.jar'):expected[f.name]=hashlib.sha256(f.read_bytes()).hexdigest()
assert len(expected)==3,expected
started=time.monotonic()
with p.frozen.lease(p.GLOBAL_STATE/'worker-slot-1.lock'), p.h.worker_slot(1,prepare=False):
 result=p.remote('''import hashlib,json,sys,zipfile
from pathlib import Path
root=Path(sys.argv[1]);expected=json.loads(sys.argv[2]);base=root/'bestbuds/targets/reaktorCli/build'
bins=list(base.glob('install/*/bin/reaktor'));assert len(bins)==1,[str(x)for x in bins]
dist=bins[0].parent.parent
libs={f.name:hashlib.sha256(f.read_bytes()).hexdigest()for f in (dist/'lib').glob('*.jar')}
assert all(libs.get(k)==v for k,v in expected.items()),'Checked producer/library mismatch; do not install'
archive=root/'.remote-dev/jobs/compatibility-deadline-primary-dist.zip'
with zipfile.ZipFile(archive,'w',zipfile.ZIP_STORED)as z:
 for f in sorted(dist.rglob('*')):
  if f.is_file():z.write(f,str(Path('reaktor')/f.relative_to(dist)))
print(json.dumps({'source':str(archive),'sha256':hashlib.sha256(archive.read_bytes()).hexdigest(),'bytes':archive.stat().st_size,'libs':libs,'checkedProducers':expected}))
''',p.h.REMOTE,json.dumps(expected))
 target=p.DEV/'tmp/dependeasy-deadline-install/targets/reaktorCli/build/distributions/reaktor.zip'
 target.parent.mkdir(parents=True,exist_ok=True)
 subprocess.run(['scp','-q',p.h.HOST+':'+result['source'],str(target)],check=True)
 assert hashlib.sha256(target.read_bytes()).hexdigest()==result['sha256']
 p.h.ssh(shlex.join(['rm','-f',result['source']]))
 result.update(destination=str(target),routerId=RUN,workerSlot=1,wallSeconds=time.monotonic()-started,scope='Collected existing checked installDist only; three checked producer hashes verified under original slot lease; no Gradle or source write.')
 (REPORT/'compatibility-deadline-primary-dist-proof.json').write_text(json.dumps(result,indent=2)+'\n')
 print(json.dumps({k:v for k,v in result.items()if k!='libs'}),flush=True)

#!/usr/bin/env python3
"""Private benchmark adapter around the unmodified frozen remote-dev execution path."""
import argparse, contextlib, datetime, hashlib, importlib.machinery, importlib.util
import json, os, re, shutil, subprocess, sys, time
from pathlib import Path

HERE = Path(__file__).resolve().parent
DEV = Path('/Users/ovd/dev')
SCRATCH = DEV/'tmp/dependeasy-bench-20261028'
SOURCE = SCRATCH/'source/reaktor'
REFS = {'september':'b7b0370346b46d6bc915a05fb61ffdef93436235',
        'current':'423e607f10b29aae104e39879be962e0e9bb5131'}
EDIT = 'reaktor-core/src/commonMain/kotlin/dev/shibasis/reaktor/core/utils/time.kt'
JDK21 = '/Users/ovd/Library/Java/JavaVirtualMachines/corretto-21.0.8/Contents/Home'
os.environ['REMOTE_DEV_WORKSPACE_ROOT'] = str(SOURCE.parent)
os.environ['REMOTE_DEV_CACHE'] = 'off'
sys.path.insert(0, str(DEV/'tools'))
loader = importlib.machinery.SourceFileLoader('benchmark_remote_dev', str(DEV/'tools/remote-dev'))
spec = importlib.util.spec_from_loader(loader.name, loader)
h = importlib.util.module_from_spec(spec); loader.exec_module(h)
import remote_build as frozen
h.CONFIG = dict(h.CONFIG, java_home=JDK21, minimum_free_disk_mib=14306,
                forward_env=[*h.CONFIG.get('forward_env', []), 'GRADLE_USER_HOME'])
worker_slot = h.REMOTE+'/.remote-dev/slots/worker'
cache_root = h.REMOTE+'/.remote-dev/benchmark-20261028'

original_hosts = h.read_hosts_contract
def consistent_hosts(path):
    policy = original_hosts(path)
    # Keep the exact requested task list independent of a previous model publication.
    return dict(policy, taskFamilies={k:v for k,v in policy.get('taskFamilies',{}).items()
                                      if k != ':exportBuildModel'}) if policy else policy
h.read_hosts_contract = consistent_hosts
original_build = h.build
active = {}

def remote(code, *args):
    result = h.remote_python(code, *map(str,args), capture_output=True, text=True)
    return json.loads(result.stdout)

def system():
    return remote('''import os,json,subprocess,platform,shutil
print(json.dumps({'epoch':__import__('time').time(),'host':platform.node(),'machine':platform.machine(),
 'load':os.getloadavg(),'diskFreeBytes':shutil.disk_usage('/Users/ovd').free,
 'hardware':subprocess.check_output(['/usr/sbin/sysctl','hw.model','hw.ncpu','hw.memsize'],text=True),
 'memory':subprocess.check_output(['/usr/bin/memory_pressure','-Q'],text=True),
 'cpuSamples':subprocess.check_output(['/usr/bin/top','-l','2','-s','1','-n','0'],text=True),
 'jvmProcesses':[line for line in subprocess.check_output(['/bin/ps','-axo','pid,pcpu,rss,etime,comm'],text=True).splitlines() if 'java' in line.lower()],
 'compilerProcesses':[line for line in subprocess.check_output(['/bin/ps','-axo','pid,time,rss,command'],text=True).splitlines() if 'org.jetbrains.kotlin.daemon.KotlinCompileDaemon' in line and 'benchmark-20261028' in line]}))
''')

def clean_roots(base, roots):
    removed=[]
    for relative in sorted(roots, key=len, reverse=True):
        for name in ('build','.gradle','.kotlin'):
            path=base/relative/name
            if path.is_dir() and not path.is_symlink():
                shutil.rmtree(path); removed.append(str(path))
    return removed

def wrapped_build(name, relative, args, **kwargs):
    # isolated_build holds the shared worker lease here; no other frozen build can race reset.
    active['before'] = system()
    started=time.monotonic()
    if active['phase'] in ('cold','prepare'):
        roots=json.loads((HERE/'snapshots.json').read_text())['projectRoots']
        clean_roots(h.ROOT/name, roots)
        active['reset'] = remote('''import json,shutil,sys,os,signal,subprocess,time
from pathlib import Path
base=Path(sys.argv[1]); home=Path(sys.argv[2]); roots=json.loads(sys.argv[3]); removed=[]
assert '/.remote-dev/slots/worker/reaktor' in str(base)
assert home.name in {'september','current','current-native'} and home.parent.name=='benchmark-20261028'
stopped=[]
for line in subprocess.check_output(['/bin/ps','-axo','pid=,command='],text=True).splitlines():
 if 'org.jetbrains.kotlin.daemon.KotlinCompileDaemon' in line and str(home.parent)+'/' in line:
  pid=int(line.split(None,1)[0])
  if pid!=os.getpid():os.kill(pid,signal.SIGTERM);stopped.append(pid)
deadline=time.monotonic()+10
while stopped and time.monotonic()<deadline:
 alive=[]
 for pid in stopped:
  try:os.kill(pid,0);alive.append(pid)
  except ProcessLookupError:pass
 if not alive:break
 time.sleep(.2)
for relative in sorted(roots,key=len,reverse=True):
 for name in ('build','.gradle','.kotlin'):
  p=base/relative/name
  if p.is_dir() and not p.is_symlink(): shutil.rmtree(p); removed.append(str(p))
if home.exists():
 for p in home.iterdir():
  if p.name=='wrapper': continue
  if p.name=='caches':
   for child in p.iterdir():
    if child.name=='modules-2': continue
    if child.is_dir():shutil.rmtree(child)
    else:child.unlink()
  elif p.is_dir():shutil.rmtree(p)
  else:p.unlink()
print(json.dumps({'removed':removed,'stoppedOwnedCompilerPids':stopped,'preserved':['wrapper','caches/modules-2']}))
''', h.REMOTE+'/'+name, os.environ['GRADLE_USER_HOME'], json.dumps(roots))
    active['resetSeconds']=time.monotonic()-started
    try:
        return original_build(name, relative, args, **kwargs)
    finally:
        active['after']=system()
h.build=wrapped_build

def capture():
    snapshots={}; inventories={}; roots=set()
    for variant,ref in REFS.items():
        subprocess.run(['git','-C',str(SOURCE),'reset','--hard',ref],check=True)
        criteria=SOURCE/'gradle/gradle-daemon-jvm.properties'
        if criteria.exists():criteria.write_text(criteria.read_text().replace('toolchainVersion=25','toolchainVersion=21'))
        (SOURCE/'benchmark.init.gradle').write_bytes((HERE/'benchmark.init.gradle').read_bytes())
        files=h.source_files('reaktor')
        for file in files:
            if Path(file).name in ('build.gradle.kts','settings.gradle.kts','build.gradle','settings.gradle'):
                roots.add(str(Path(file).parent))
        inv={'head':ref,'files':len(files),'kotlinFiles':len([p for p in files if p.endswith('.kt')]),
             'coreKotlinFiles':len([p for p in files if p.startswith('reaktor-core/src/') and p.endswith('.kt')]),
             'buildLogicKotlinFiles':len([p for p in files if p.startswith('dependeasy/') and p.endswith('.kt')]),
             'buildLogicKotlinLines':sum(len((SOURCE/p).read_text().splitlines()) for p in files if p.startswith('dependeasy/') and p.endswith('.kt')),
             'wrapper':(SOURCE/'gradle/wrapper/gradle-wrapper.properties').read_text()}
        snapshots[variant]={'base':frozen.freeze(h)}
        target=SOURCE/EDIT; content=target.read_text()
        assert content.count('Clock.System.now().toEpochMilliseconds()')==1
        edited=content.replace('Clock.System.now().toEpochMilliseconds()', '(Clock.System.now().toEpochMilliseconds() + 1L)')
        inv['incrementalEdit']={'path':EDIT,'before':hashlib.sha256(content.encode()).hexdigest(),
                                'after':hashlib.sha256(edited.encode()).hexdigest()}
        target.write_text(edited)
        snapshots[variant]['incremental']=frozen.freeze(h)
        target.write_text(content)
        if variant=='current':
            criteria.write_text(criteria.read_text().replace('toolchainVersion=21','toolchainVersion=25'))
            snapshots['current-native']={'base':frozen.freeze(h)}
        inventories[variant]=inv
    data={'snapshots':snapshots,'inventory':inventories,'projectRoots':sorted(roots),
          'instrumentationSha256':hashlib.sha256((HERE/'benchmark.init.gradle').read_bytes()).hexdigest()}
    (HERE/'snapshots.json').write_text(json.dumps(data,indent=2)+'\n')
    print(json.dumps(data,indent=2),flush=True)

def evidence(variant, phase, repetition, receipt, elapsed):
    runid=receipt['run_id']; dest=HERE/'runs'/runid; dest.mkdir(parents=True,exist_ok=True)
    source=h.STATE/'runs'/runid
    for p in source.iterdir():
        if p.is_file():shutil.copy2(p,dest/p.name)
    if 'transportReceipt' not in receipt:
        row={'variant':variant,'phase':phase,'repetition':repetition,'receipt':receipt,
             'totalMeasuredSeconds':elapsed,'endToEndSeconds':elapsed-active.get('resetSeconds',0),
             'diagnostics':dict(active),'artifactVerification':[]}
        (dest/'result.json').write_text(json.dumps(row,indent=2)+'\n')
        with (HERE/'results.jsonl').open('a') as out:out.write(json.dumps(row)+'\n')
        return row
    transport=Path(receipt['transportReceipt'])
    shutil.copy2(transport,dest/'transport.json')
    logfile=transport.with_suffix('.log')
    if logfile.exists():shutil.copy2(logfile,dest/'worker.log')
    # Collect direct metadata independently of differences in native publication policies.
    payload=remote('''import json,sys,hashlib,base64,zipfile
from pathlib import Path
base=Path(sys.argv[1]); found={}; artifacts=[]
for pattern in ('**/build/reports/benchmark/metrics.json','**/build/reports/profile/*.html',
                '**/build/reports/kotlin-build/*.txt'):
 for p in base.glob(pattern):
  if p.is_file() and p.stat().st_size<4*1024*1024:
   found[str(p.relative_to(base))]=base64.b64encode(p.read_bytes()).decode()
for p in (base/'reaktor-core/build/libs').glob('*.jar'):
 raw=p.read_bytes()
 with zipfile.ZipFile(p) as z: entries={i.filename:hashlib.sha256(z.read(i)).hexdigest() for i in z.infolist() if i.filename.endswith('.class')}
 artifacts.append({'path':str(p.relative_to(base)),'bytes':len(raw),'sha256':hashlib.sha256(raw).hexdigest(),'classes':entries})
native=base/'build/dependeasy'
for category in ('receipts','runs'):
 for p in sorted((native/category).glob('*.json'))[-4:]:
  if p.stat().st_size<8*1024*1024:found[str(p.relative_to(base))]=base64.b64encode(p.read_bytes()).decode()
print(json.dumps({'files':found,'artifacts':artifacts}))
''', worker_slot+'/reaktor')
    import base64
    for relative,raw in payload['files'].items():
        p=dest/'outputs'/relative; p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(base64.b64decode(raw))
    payload.pop('files')
    native=Path(receipt['artifactDirectory'])
    files=[p for p in native.rglob('*') if p.is_file()]
    row={'variant':variant,'phase':phase,'repetition':repetition,'receipt':receipt,
         'totalMeasuredSeconds':elapsed,'endToEndSeconds':elapsed-active['resetSeconds'],
         'diagnostics':dict(active),'artifactVerification':payload['artifacts'],
         'collectedFiles':len(files),'collectedBytes':sum(p.stat().st_size for p in files)}
    (dest/'result.json').write_text(json.dumps(row,indent=2)+'\n')
    with (HERE/'results.jsonl').open('a') as out:out.write(json.dumps(row)+'\n')
    print(json.dumps({'run':runid,'variant':variant,'phase':phase,'rep':repetition,
                      'status':receipt['status'],'endToEndSeconds':row['endToEndSeconds'],
                      'resetSeconds':active['resetSeconds']}),flush=True)
    # No large retained artifact snapshots: keep small profiles, class hashes and receipts only.
    if native.is_dir() and native.parent==h.STATE/'artifacts':shutil.rmtree(native)
    return row

def run(variant,phase,rep):
    if shutil.disk_usage(DEV).free < 15_000_000_000:
        raise RuntimeError('Storage admission: less than the required 15 GB local free space')
    active.clear();active.update(variant=variant,phase=phase,resetSeconds=0)
    data=json.loads((HERE/'snapshots.json').read_text())
    identity=data['snapshots'][variant]['incremental' if phase=='incremental' else 'base']
    os.environ['GRADLE_USER_HOME']=cache_root+'/'+variant
    jdk=h.CONFIG['java_home'] if variant!='current-native' else '/Users/ovd/Library/Java/JavaVirtualMachines/temurin-25.0.2/Contents/Home'
    args=[':reaktor-core:jvmJar','--max-workers=6','--no-daemon','--no-configuration-cache',
          '--no-build-cache','--profile','--info','-Dorg.gradle.java.home='+jdk,
          '-Dorg.gradle.jvmargs=-Xmx8g -XX:MaxMetaspaceSize=2g -Dfile.encoding=UTF-8',
          '-Pkotlin.compiler.execution.strategy=daemon','-Pkotlin.daemon.jvmargs=-Xmx8g',
          '--init-script','benchmark.init.gradle']
    runid=f'bench28-{variant}-{phase}-{rep}-{datetime.datetime.now():%H%M%S}'
    started=time.monotonic()
    receipt=frozen.isolated_build(h,identity,'worker','reaktor',args,run_id=runid)
    elapsed=time.monotonic()-started
    row=evidence(variant,phase,rep,receipt,elapsed)
    if receipt['status']!='succeeded':raise RuntimeError(f'Build failed: {runid}')
    if not row['artifactVerification']:raise RuntimeError(f'No JVM artifact: {runid}')
    return row

def matrix():
    for variant in ('september','current'):run(variant,'prepare',0)
    for rep,order in enumerate((('september','current'),('current','september'),('september','current')),1):
        for variant in order:
            for phase in ('cold','warm','incremental'):run(variant,phase,rep)

def measured():
    # Reserve the same primary worker slot for the full paired matrix. Setup is already complete.
    original_lease=frozen.lease
    lock_path=h.STATE/'worker-gradle.lock'
    began=time.monotonic()
    with contextlib.ExitStack() as reservation:
        owned=reservation.enter_context(original_lease(lock_path))
        # Atlas uses slot 2 independently of the configured number of build slots.
        for index in range(1,max(3,h.worker_slot_count())):
            reservation.enter_context(original_lease(h.STATE/(h.slot_lock_name(index)+'.lock')))
        import workspace_storage
        # Cleanup cannot evict remote outputs while another worker build is active.
        # Admit storage after reserving the workers, before taking our graph lease.
        held=('worker-gradle','worker-slot-1','worker-slot-2')
        cleanup=workspace_storage.cleanup(wait=True,held_locks=held)
        (HERE/'reservation-cleanup.json').write_text(json.dumps(cleanup,indent=2)+'\n')
        if shutil.disk_usage(DEV).free < 15_000_000_000:
            raise RuntimeError('Storage admission after reserved cleanup: below 15 GB free')
        reservation.enter_context(workspace_storage.graph_activity(h.STATE))
        wait=time.monotonic()-began
        (HERE/'reservation.json').write_text(json.dumps({'queueSeconds':wait,'acquiredEpoch':time.time(),
            'harnessSha256':hashlib.sha256((HERE/'benchmark.py').read_bytes()).hexdigest(),
            'scope':'18 measured runs, reserving worker build and Atlas slots 0/1/2; execution stays in the same frozen slot'},indent=2)+'\n')
        @contextlib.contextmanager
        def reentrant(path):
            if path==lock_path:yield owned
            else:
                with original_lease(path) as lock:yield lock
        frozen.lease=reentrant
        try:
            for rep,order in enumerate((('september','current'),('current','september'),('september','current')),1):
                exclusions=json.loads((HERE/'excluded-runs.json').read_text())
                rows=[json.loads(line) for line in (HERE/'results.jsonl').read_text().splitlines()]
                complete={(r['variant'],r['phase']) for r in rows if r['repetition']==rep and
                          r['receipt']['status']=='succeeded' and r['receipt']['run_id'] not in exclusions}
                if all((v,p) in complete for v in order for p in ('cold','warm','incremental')):continue
                for row in rows:
                    if row['repetition']==rep and row['phase'] in ('cold','warm','incremental') and row['receipt']['run_id'] not in exclusions:
                        exclusions[row['receipt']['run_id']]='Incomplete paired block before reservation restart; full block rerun.'
                (HERE/'excluded-runs.json').write_text(json.dumps(exclusions,indent=2)+'\n')
                for variant in order:
                    for phase in ('cold','warm','incremental'):run(variant,phase,rep)
        finally:frozen.lease=original_lease

if __name__=='__main__':
    ap=argparse.ArgumentParser();ap.add_argument('action',choices=['capture','matrix','measured','run'])
    ap.add_argument('--variant',default='september');ap.add_argument('--phase',default='prepare');ap.add_argument('--rep',type=int,default=0)
    ns=ap.parse_args()
    import workspace_storage
    with contextlib.ExitStack() as stack:
        if ns.action!='measured':stack.enter_context(workspace_storage.graph_activity(h.STATE))
        if (HERE/'snapshots.json').exists():
            for variants in json.loads((HERE/'snapshots.json').read_text())['snapshots'].values():
                for identity in variants.values():stack.enter_context(workspace_storage.snapshot_pin(h.STATE,identity))
        if ns.action=='capture':capture()
        elif ns.action=='matrix':matrix()
        elif ns.action=='measured':measured()
        else:run(ns.variant,ns.phase,ns.rep)

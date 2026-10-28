#!/usr/bin/env python3
"""Private paired experiment using the existing router and one secondary-slot lease."""
import argparse, contextlib, datetime, hashlib, importlib.machinery, importlib.util
import json, os, re, shutil, subprocess, sys, time, base64, zipfile
from pathlib import Path
HERE=Path(__file__).resolve().parent
DEV=Path('/Users/ovd/dev')
TEMP=DEV/'tmp/dependeasy-perf-followup-20261028'
JDK='/Users/ovd/Library/Java/JavaVirtualMachines/corretto-21.0.8/Contents/Home'
os.environ['REMOTE_DEV_WORKSPACE_ROOT']=str(DEV)
os.environ['REMOTE_DEV_CACHE']='off'
sys.path.insert(0,str(DEV/'tools'))
loader=importlib.machinery.SourceFileLoader('performance_followup_router',str(DEV/'tools/remote-dev'))
spec=importlib.util.spec_from_loader(loader.name,loader)
h=importlib.util.module_from_spec(spec);sys.modules[loader.name]=h;loader.exec_module(h)
import remote_build as frozen
import workspace_storage
h.PROJECTS=['reaktor']
h.CONFIG=dict(h.CONFIG,java_home=JDK,forward_env=[*h.CONFIG.get('forward_env',[]),'GRADLE_USER_HOME'])
STATE=h.STATE
GLOBAL_STATE=STATE
EDIT='reaktor-core/src/commonMain/kotlin/dev/shibasis/reaktor/core/utils/time.kt'
OWN=[
'dependeasy/model/src/main/kotlin/dev/shibasis/dependeasy/model/KernelSpec.kt',
'dependeasy/model/src/main/kotlin/dev/shibasis/dependeasy/model/BuildHandles.kt',
'dependeasy/model/src/main/kotlin/dev/shibasis/dependeasy/model/BuildModelKernel.kt',
'dependeasy/gradle/src/main/kotlin/dev/shibasis/dependeasy/analysis/KotlinAnalysisCompile.kt',
'dependeasy/gradle/src/main/kotlin/dev/shibasis/dependeasy/workspace/DeclarationSites.kt',
'dependeasy/gradle/src/main/kotlin/dev/shibasis/dependeasy/components/Library.kt',
'dependeasy/gradle/src/test/kotlin/dev/shibasis/dependeasy/DslEntryPointsTest.kt']

def remote(code,*args):
    return json.loads(h.remote_python(code,*map(str,args),capture_output=True,text=True).stdout)

def materialize(identity):
    private=TEMP/'state';private.mkdir(parents=True,exist_ok=True)
    link=private/'snapshots'
    if not link.exists():link.symlink_to(STATE/'snapshots',target_is_directory=True)
    h.STATE=private
    try:return frozen.materialize(h,identity,'worker')
    finally:h.STATE=STATE

def prepare():
    data=json.loads((HERE/'performance-snapshots.json').read_text())
    original=data['baseline'];original_root=h.ROOT
    with workspace_storage.snapshot_pin(STATE,original),workspace_storage.graph_activity(STATE):
        slot=materialize(original);h.ROOT=slot
        criteria=slot/'reaktor/gradle/gradle-daemon-jvm.properties'
        criteria.write_text(criteria.read_text().replace('toolchainVersion=25','toolchainVersion=21'))
        (slot/'reaktor/benchmark.init.gradle').write_bytes((DEV/'reaktor/reports/dependeasy-benchmark-2026-10-28/benchmark.init.gradle').read_bytes())
        roots=sorted({str(Path(p).parent) for p in h.source_files('reaktor') if Path(p).name in {'build.gradle','build.gradle.kts','settings.gradle','settings.gradle.kts'}})
        data['projectRoots']=roots;data['variants']={}
        for variant in ('before','after'):
            if variant=='after':
                for rel in OWN:frozen.copy_source(original_root/'reaktor'/rel,slot/'reaktor'/rel)
            source=slot/'reaktor'/EDIT;text=source.read_text()
            assert text.count('Clock.System.now().toEpochMilliseconds()')==1
            data['variants'][variant]={'base':frozen.freeze(h)}
            source.write_text(text.replace('Clock.System.now().toEpochMilliseconds()','(Clock.System.now().toEpochMilliseconds() + 1L)'))
            data['variants'][variant]['incremental']=frozen.freeze(h)
            source.write_text(text)
        data['ownedSourcePaths']=OWN
        (HERE/'performance-snapshots.json').write_text(json.dumps(data,indent=2)+'\n')
        print(json.dumps(data,indent=2),flush=True)
    h.ROOT=original_root

def system():
    return remote('''import json,os,platform,subprocess,shutil,time
print(json.dumps({'epoch':time.time(),'host':platform.node(),'load':os.getloadavg(),
 'hardware':subprocess.check_output(['/usr/sbin/sysctl','hw.model','hw.ncpu','hw.memsize'],text=True),
 'freeBytes':shutil.disk_usage('/Users/ovd').free,
 'memory':subprocess.check_output(['/usr/bin/memory_pressure','-Q'],text=True),
 'cpu':subprocess.check_output(['/usr/bin/top','-l','2','-s','1','-n','0'],text=True),
 'processes':subprocess.check_output(['/bin/ps','-axo','pid,pcpu,rss,etime,comm'],text=True)}))
''')

def reset(roots,home):
    for relative in roots:
        for name in ('build','.gradle','.kotlin'):
            p=h.ROOT/'reaktor'/relative/name
            if p.is_dir() and not p.is_symlink():shutil.rmtree(p)
    return remote('''import json,os,shutil,signal,subprocess,sys,time
from pathlib import Path
base=Path(sys.argv[1]); home=Path(sys.argv[2]); roots=json.loads(sys.argv[3]); stopped=[]
assert base.parent.name=='job-1' and base.name=='reaktor'
assert home.parent.name=='performance-followup-20261028' and home.name in {'before','after'}
for line in subprocess.check_output(['/bin/ps','-axo','pid=,command='],text=True).splitlines():
 if 'org.jetbrains.kotlin.daemon.KotlinCompileDaemon' in line and str(home.parent)+'/' in line:
  pid=int(line.split(None,1)[0]);os.kill(pid,signal.SIGTERM);stopped.append(pid)
for relative in sorted(roots,key=len,reverse=True):
 for name in ('build','.gradle','.kotlin'):
  path=base/relative/name
  if path.is_dir() and not path.is_symlink():shutil.rmtree(path)
if home.exists():
 for path in home.iterdir():
  if path.name=='wrapper':continue
  if path.name=='caches':
   for child in path.iterdir():
    if child.name=='modules-2':continue
    if child.is_dir():shutil.rmtree(child)
    else:child.unlink()
  elif path.is_dir():shutil.rmtree(path)
  else:path.unlink()
print(json.dumps({'stoppedOwnedCompilerPids':stopped,'preserved':['wrapper','modules-2']}))
''',h.REMOTE+'/reaktor',home,json.dumps(roots))

def duration(text):
    return sum(float(n)*{'h':3600,'m':60,'s':1}[unit] for n,unit in re.findall(r'([\d.]+)([hms])',text))

def evidence(destination):
    payload=remote('''import base64,hashlib,json,sys,zipfile
from pathlib import Path
base=Path(sys.argv[1]);files={};jars=[]
for pattern in ('build/reports/profile/*.html','build/reports/benchmark/*.json','reaktor-core/build/reports/kotlin-build/*.txt'):
 for path in base.glob(pattern):
  if path.is_file() and path.stat().st_size<4000000:files[str(path.relative_to(base))]=base64.b64encode(path.read_bytes()).decode()
for path in (base/'reaktor-core/build/libs').glob('*.jar'):
 with zipfile.ZipFile(path) as z:classes={i.filename:hashlib.sha256(z.read(i)).hexdigest() for i in z.infolist() if i.filename.endswith('.class')}
 jars.append({'path':str(path.relative_to(base)),'bytes':path.stat().st_size,'sha256':hashlib.sha256(path.read_bytes()).hexdigest(),'classes':classes})
print(json.dumps({'files':files,'jars':jars}))
''',h.REMOTE+'/reaktor')
    for rel,raw in payload.pop('files').items():
        p=destination/'outputs'/rel;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(base64.b64decode(raw))
    return payload

def run(data,variant,phase,rep,cc=False,profile=True):
    if shutil.disk_usage(DEV).free<15_000_000_000:raise RuntimeError('Local free space below 15GB')
    identity=data['variants'][variant]['incremental' if phase.endswith('incremental') else 'base']
    h.ROOT=materialize(identity)
    home=h.BASE_REMOTE+'/.remote-dev/performance-followup-20261028/'+variant
    os.environ['GRADLE_USER_HOME']=home
    reset_receipt=reset(data['projectRoots'],home) if phase=='cold' else None
    before=system();started=time.monotonic()
    args=[':reaktor-core:jvmJar',':exportBuildModel','--max-workers=6','--no-daemon','--no-build-cache','--info','--stacktrace',
          '-Dorg.gradle.java.home='+JDK,'-Dorg.gradle.jvmargs=-Xmx8g -XX:MaxMetaspaceSize=2g -Dfile.encoding=UTF-8',
          '-Pkotlin.compiler.execution.strategy=daemon','-Pkotlin.daemon.jvmargs=-Xmx8g']
    if profile: args.append('--profile')
    args+=[] if cc is None else (['--configuration-cache','--configuration-cache-problems=fail'] if cc else ['--no-configuration-cache','--init-script','benchmark.init.gradle'])
    label=f'{variant}-{phase}-{rep}-{datetime.datetime.now():%H%M%S}'
    dest=HERE/'performance-runs'/label;dest.mkdir(parents=True)
    with (dest/'console.log').open('w') as log,contextlib.redirect_stdout(log):
        code=h.build('reaktor',Path('.'),args,frozen=True,verify=lambda:frozen.verify_worker(h,identity))
    elapsed=time.monotonic()-started
    frozen.verify_worker(h,identity)
    receipt=json.loads((STATE/'logs'/(h.LAST_RUN_ID+'.json')).read_text())
    shutil.copy2(STATE/'logs'/(h.LAST_RUN_ID+'.json'),dest/'transport.json')
    shutil.copy2(STATE/'logs'/(h.LAST_RUN_ID+'.log'),dest/'worker.log')
    payload=evidence(dest);after=system()
    worker_log=(dest/'worker.log').read_text()
    console_times=re.findall(r'BUILD SUCCESSFUL in ([^\n]+)',worker_log)
    metrics={}
    profiles=sorted((dest/'outputs/build/reports/profile').glob('*.html'))
    if profile and profiles:
        html=profiles[-1].read_text()
        for name,value in re.findall(r'<td>([^<]+)</td>\s*<td class="numeric">([^<]+)</td>',html):
            if name in {'Total Build Time','Startup','Settings and buildSrc','Loading Projects','Configuring Projects','Task Execution'}:metrics[name]=duration(value)
    artifacts=STATE/'artifacts'/h.LAST_RUN_ID
    files=[p for p in artifacts.rglob('*') if p.is_file()]
    row={'variant':variant,'phase':phase,'rep':rep,'snapshot':identity,'code':code,'endToEndSeconds':elapsed,
         'transport':receipt,'profileSeconds':metrics,'reset':reset_receipt,'before':before,'after':after,
         'jars':payload['jars'],'collectedFiles':len(files),'collectedBytes':sum(p.stat().st_size for p in files),
         'gradleConsoleSeconds':duration(console_times[-1]) if console_times else None,'configurationCache':cc,'profileEnabled':profile,'workerInputsUnchanged':True}
    (dest/'result.json').write_text(json.dumps(row,indent=2)+'\n')
    with (HERE/'performance-results.jsonl').open('a') as out:out.write(json.dumps(row)+'\n')
    print(json.dumps({'label':label,'code':code,'gradle':metrics.get('Total Build Time'),'endToEnd':elapsed,
                      'files':len(files),'bytes':row['collectedBytes'],'family':receipt.get('artifact_family')}),flush=True)
    shutil.rmtree(artifacts)
    if code:raise RuntimeError('Failed '+label)
    if not payload['jars']:raise RuntimeError('No jar '+label)
    return row

def matrix():
    global STATE
    data=json.loads((HERE/'performance-snapshots.json').read_text())
    started=time.monotonic()
    with contextlib.ExitStack() as stack:
        for variant in data['variants'].values():
            for identity in variant.values():stack.enter_context(workspace_storage.snapshot_pin(STATE,identity))
        stack.enter_context(frozen.lease(STATE/'worker-slot-1.lock'))
        queue=time.monotonic()-started
        stack.enter_context(workspace_storage.graph_activity(STATE))
        stack.enter_context(h.worker_slot(1))
        h.STATE=TEMP/'state';STATE=h.STATE
        for category in ('logs','artifacts','runs'):(STATE/category).mkdir(parents=True,exist_ok=True)
        (HERE/'performance-reservation.json').write_text(json.dumps({'queueSeconds':queue,'slot':1,'remote':h.REMOTE,
           'scriptSha256':hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),'epoch':time.time()},indent=2)+'\n')
        print(json.dumps(remote('''import json,shutil,sys,subprocess
from pathlib import Path
root=Path(sys.argv[1]);source=Path(sys.argv[2]);seeded=[]
assert root.name=="performance-followup-20261028"
assert (source/"caches/modules-2").is_dir()
for name in ("before","after"):
 home=root/name;home.mkdir(parents=True,exist_ok=True)
 for rel in ("wrapper","caches/modules-2"):
  src=source/rel;dest=home/rel
  if src.exists() and not dest.exists():
   dest.parent.mkdir(parents=True,exist_ok=True)
   subprocess.run(["/bin/cp","-cRp",str(src),str(dest)],check=True);seeded.append(str(dest))
print(json.dumps({"seeded":seeded,"source":str(source)}))
''',h.BASE_REMOTE+'/.remote-dev/performance-followup-20261028','/Users/ovd/.gradle')),flush=True)
        for rep in range(1,4):
            for variant in (('before','after') if rep%2 else ('after','before')):
                for phase in ('cold','warm','incremental'):run(data,variant,phase,rep)
        for variant in ('before','after'):
            run(data,variant,'cc-store',0,cc=True)
            for rep in range(1,4):run(data,variant,'cc-warm',rep,cc=True)
            run(data,variant,'cc-incremental',1,cc=True)

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('mode',choices=['prepare','matrix']);options=parser.parse_args()
    prepare() if options.mode=='prepare' else matrix()

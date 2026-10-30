#!/usr/bin/env python3
"""Qualify the packaged client and an isolated owner under the existing slot lease."""
import contextlib
import io
import zipfile
import hashlib
import json
import subprocess
import sys
import time
from pathlib import Path
import performance_followup as p

HERE = p.HERE
archive = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else p.DEV / 'bestbuds/targets/reaktorCli/build/distributions/reaktor.zip'
if not archive.exists() and (HERE / 'qualified-archive-retrieval.json').exists():
    archive = Path(json.loads((HERE / 'qualified-archive-retrieval.json').read_text())['destination'])
identity = json.loads((HERE / 'continuation-benchmark-snapshots.json').read_text())['variants']['after']['base']
before_archive = p.DEV / '.remote-dev/artifacts/20261028-214315-880975b4-bestbuds/bestbuds/targets/reaktorCli/build/distributions/reaktor.zip'
with zipfile.ZipFile(before_archive) as old, zipfile.ZipFile(archive) as new:
    name = next(n for n in old.namelist() if '/lib/reaktor-tooling-' in n and n.endswith('.jar'))
    old_jar, new_jar = old.read(name), new.read(name)
    def contents(data):
        with zipfile.ZipFile(io.BytesIO(data)) as jar:
            return {n: hashlib.sha256(jar.read(n)).hexdigest() for n in jar.namelist() if not n.endswith('/')}
    old_entries, new_entries = contents(old_jar), contents(new_jar)
    changed = [n for n in sorted(old_entries.keys() | new_entries.keys()) if old_entries.get(n) != new_entries.get(n)]
    pair = {'eligible': bool(changed) and all(n.startswith('dev/shibasis/reaktor/tooling/GradleBuildPlanner') for n in changed),
            'changedJarEntries': changed, 'jarName': Path(name).name,
            'beforeJarSha256': hashlib.sha256(old_jar).hexdigest(), 'afterJarSha256': hashlib.sha256(new_jar).hexdigest()}
started = time.monotonic()
with contextlib.ExitStack() as stack:
    stack.enter_context(p.workspace_storage.snapshot_pin(p.GLOBAL_STATE, identity))
    stack.enter_context(p.workspace_storage.graph_activity(p.GLOBAL_STATE))
    stack.enter_context(p.frozen.lease(p.GLOBAL_STATE / 'worker-slot-1.lock'))
    stack.enter_context(p.h.worker_slot(1))
    admission = time.monotonic() - started
    p.h.ROOT = p.materialize(identity)
    p.h.sync(['reaktor'], frozen=True)
    p.frozen.verify_worker(p.h, identity)
    private = p.h.BASE_REMOTE + '/.remote-dev/performance-followup-20261028/cli'
    p.h.ssh(p.h.shlex.join(['mkdir', '-p', private]))
    subprocess.run(['scp', '-q', str(archive), p.h.HOST + ':' + private + '/reaktor.zip'], check=True)
    old_path = p.DEV / 'tmp/dependeasy-perf-followup-20261028/before-planner-tooling.jar'
    old_path.write_bytes(old_jar)
    try:
        subprocess.run(['scp', '-q', str(old_path), p.h.HOST + ':' + private + '/before-tooling.jar'], check=True)
    finally:
        old_path.unlink()

    (HERE / 'packaged-cli-reservation.json').write_text(json.dumps({
        'slot': 1, 'admissionSeconds': admission, 'snapshot': identity,
        'distributionSha256': hashlib.sha256(archive.read_bytes()).hexdigest(),
        'pairedPlanner': pair,
        'scope': 'Actual packaged CLI/isolated-owner acceptance and three alternating planner pairs; frozen source, slot 1, JDK 25 and inherited environment held constant. Admission and bootstrap excluded from timed CLI calls.',
    }, indent=2) + '\n')
    code = r'''
import hashlib,json,os,signal,statistics,subprocess,sys,threading,time,zipfile
from pathlib import Path
root=Path(sys.argv[1]); private=Path(sys.argv[2]); evidence=private/'evidence-after-inherited-environment'
pair=json.loads(sys.argv[3])
assert root.name=='reaktor' and root.parent.name=='job-1'
assert private.name=='cli' and private.parent.name=='performance-followup-20261028'
evidence.mkdir(parents=True,exist_ok=True)
with zipfile.ZipFile(private/'reaktor.zip') as z: z.extractall(private/'distribution')
dist=private/'distribution/reaktor'; home=private/'home';home.mkdir(exist_ok=True)
java='/Users/ovd/Library/Java/JavaVirtualMachines/temurin-25.0.2/Contents/Home/bin/java'
common=[java,'-Duser.home='+str(home),'-cp',str(dist/'lib/*')]
cli=common+['ai.bestbuds.reaktor.cli.MainKt']
env=dict(os.environ,PATH=str(Path(java).parent)+':/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin')
rows=[]
def invoke(label,args,workspace=True,main='ai.bestbuds.reaktor.cli.MainKt'):
    command=common+[main]+(['--dir',str(root),'--json'] if workspace else [])+args
    wrappers={}; stop=threading.Event()
    def sample():
        while not stop.is_set():
            output=subprocess.check_output(['/bin/ps','-axo','pid=,command='],text=True)
            for line in output.splitlines():
                if 'gradle-wrapper.jar' in line and str(root) in line:
                    pid,argv=line.strip().split(None,1); wrappers[pid]=argv
            stop.wait(.2)
    monitor=threading.Thread(target=sample,daemon=True);monitor.start();t=time.monotonic()
    try:
        with (evidence/(label+'.stdout')).open('w') as out,(evidence/(label+'.stderr')).open('w') as err:
            result=subprocess.run(command,cwd=private,env=env,stdout=out,stderr=err,timeout=900)
    finally:stop.set();monitor.join()
    text=(evidence/(label+'.stdout')).read_text()
    row={'label':label,'code':result.returncode,'seconds':time.monotonic()-t,'wrappers':wrappers}
    if workspace:
        value=json.loads(text);row['outcome']=value
        assert value['type']=='completed',value
    else:row['text']=text
    rows.append(row);(evidence/'results.json').write_text(json.dumps(rows,indent=2)+'\n')
    assert result.returncode==0,row
    return row.get('outcome',{}).get('result')

# The frozen source replaces a shared slot's source, not its previously exported model.
# Bootstrap its real producer before asking the owner to accept that model.
with (evidence/'bootstrap-model.log').open('w') as log:
    bootstrap=subprocess.run([str(root/'gradlew'),'exportBuildModel','--console=plain'],cwd=root,env=env,stdout=log,stderr=subprocess.STDOUT,timeout=900)
assert bootstrap.returncode==0,'Frozen-source model bootstrap failed'
(evidence/'bootstrap-model.json').write_text(json.dumps({'sha256':hashlib.sha256((root/'build/dependeasy/model.json').read_bytes()).hexdigest(),'source':'real exportBuildModel for the frozen root before owner startup'},indent=2)+'\n')
invoke('version',['--version'],False)
invoke('help',['--help'],False)
invoke('agent-version',['--version'],False,'dev.shibasis.reaktor.conductor.cli.ConductorCliKt')
invoke('agent-help',['--help'],False,'dev.shibasis.reaktor.conductor.cli.ConductorCliKt')
launcher=dist/'bin/reaktor';launcher.chmod(0o755)
with (evidence/'launcher-version.stdout').open('w') as out,(evidence/'launcher-version.stderr').open('w') as err:
    launcherResult=subprocess.run([str(launcher),'--version'],cwd=private,env=dict(env,JAVA_HOME=str(Path(java).parent.parent),JAVA_OPTS='-Duser.home='+str(home)),stdout=out,stderr=err,timeout=30)
assert launcherResult.returncode==0,'Packaged launcher failed'
assert (evidence/'launcher-version.stdout').read_text()==(evidence/'version.stdout').read_text(),'Launcher and entry point identities differ'
ownerlog=(evidence/'owner.log').open('w')
owner=subprocess.Popen(common+['ai.bestbuds.reaktor.kernel.KernelAgentServiceKt',str(root)],cwd=root,env=env,stdout=ownerlog,stderr=subprocess.STDOUT)
try:
    ready=False
    for _ in range(120):
        if owner.poll() is not None: raise RuntimeError('Isolated owner exited: '+str(owner.returncode))
        if 'Reaktor background agent service ready for' in (evidence/'owner.log').read_text():ready=True;break
        time.sleep(.5)
    assert ready,'Owner startup timed out'
    invoke('kernel-status',['kernel','status'])
    node='reaktor:reaktor-core:outputJvmLibrary'
    first=invoke('plan-first',['build','plan',node])
    second=invoke('plan-repeat',['build','plan',node])
    run=invoke('run',['build','run','--args',json.dumps({'node':node,'plan':second})])
    assert run['status']=='Succeeded',run
    invoke('receipts',['build','receipts','--args',json.dumps({'node':node})])
    jars=[{'path':str(f.relative_to(root)),'bytes':f.stat().st_size,'sha256':hashlib.sha256(f.read_bytes()).hexdigest()}
          for f in (root/'reaktor-core/build/libs').glob('*.jar')]
    assert jars,'No produced archive'
    results=[]
    for file in (root/'build/dependeasy/runs').glob('*.json'):
        result=json.loads(file.read_text())
        if result.get('operationRunId')==run['id']:
            receipts=[json.loads((root/location).read_text()) for location in result['receipts']]
            assert receipts and all(r['operationRunId']==run['id'] and r['requestDigest']==result['requestDigest'] for r in receipts)
            results.append({'path':str(file.relative_to(root)),'result':result,'receipts':receipts})
    assert results,'No matching Gradle operation/result/receipt join'
    summary={'launcherVersionVerified':True,'rows':rows,'jars':jars,'ownerPid':owner.pid,'node':node,'joinedResults':results}
    assert len(next(row for row in rows if row['label']=='plan-repeat')['wrappers'])==1,'Default repeat planning still uses extra wrappers'
    if pair['eligible']:
        before_lib=private/'before-distribution/lib';before_lib.mkdir(parents=True,exist_ok=True)
        for lib in (dist/'lib').iterdir():
            link=before_lib/lib.name
            if link.exists() or link.is_symlink():link.unlink()
            link.symlink_to(private/'before-tooling.jar' if lib.name==pair['jarName'] else lib)
        assert hashlib.sha256((private/'before-tooling.jar').read_bytes()).hexdigest()==pair['beforeJarSha256']
        after_common=list(common);before_common=common[:-1]+[str(before_lib/'*')]
        def restart(label,command):
            global owner,ownerlog,common
            owner.terminate()
            try:owner.wait(timeout=30)
            except subprocess.TimeoutExpired:owner.kill();owner.wait()
            ownerlog.close();common=command
            path=evidence/('owner-'+label+'.log');ownerlog=path.open('w')
            owner=subprocess.Popen(common+['ai.bestbuds.reaktor.kernel.KernelAgentServiceKt',str(root)],cwd=root,env=env,stdout=ownerlog,stderr=subprocess.STDOUT)
            for _ in range(120):
                if owner.poll() is not None:raise RuntimeError('Isolated benchmark owner exited')
                if 'Reaktor background agent service ready for' in path.read_text():return
                time.sleep(.5)
            raise RuntimeError('Isolated benchmark owner startup timed out')
        for variant in ['before','after']:
            restart('warmup-'+variant,before_common if variant=='before' else after_common)
            invoke('warmup-'+variant,['build','plan',node])
        trials=[]
        for index,order in enumerate([['before','after'],['after','before'],['before','after']]):
            for variant in order:
                label='planning-'+str(index)+'-'+variant
                restart(label,before_common if variant=='before' else after_common)
                load=os.getloadavg()
                invoke(label,['build','plan',node])
                row=rows[-1]
                assert len(row['wrappers'])==(2 if variant=='before' else 1),(variant,row['wrappers'])
                plan=row['outcome']['result']['gradlePlan']
                trials.append({'pair':index,'variant':variant,'seconds':row['seconds'],'wrappers':len(row['wrappers']),
                    'loadAverageAtCall':load,'modelDigest':plan['modelDigest'],'contextDigest':plan['contextDigest'],'requestDigest':plan['requestDigest']})
        assert len({(r['modelDigest'],r['contextDigest'],r['requestDigest']) for r in trials})==1,'Measured context changed between variants'
        summary['pairedPlanningBenchmark']={'controls':pair,'trials':trials,'medianSeconds':{v:statistics.median(r['seconds'] for r in trials if r['variant']==v) for v in ['before','after']},
            'scope':'Three alternating pairs on the same frozen source/slot/JDK25/environment/cache; only GradleBuildPlanner class entries differ. Each owner starts before its timed CLI call; both variants primed. CLI wall time and wrapper count only; no inferred Gradle-only timing or production percentile.'}
    else:
        summary['pairedPlanningBenchmark']={'skipped':'Tooling archive changes exceed the planner class boundary','controls':pair}
    (evidence/'summary.json').write_text(json.dumps(summary,indent=2)+'\n')
finally:
    owner.terminate()
    try:owner.wait(timeout=30)
    except subprocess.TimeoutExpired:owner.kill();owner.wait()
    ownerlog.close()
print(json.dumps({'evidence':str(evidence),'labels':[r['label'] for r in rows],'codes':[r['code'] for r in rows]}))
'''
    destination = HERE / 'packaged-cli'
    destination.mkdir(exist_ok=True)
    try:
        try:
            result = p.remote(code, p.h.REMOTE + '/reaktor', private, json.dumps(pair))
        except subprocess.CalledProcessError as failure:
            print(failure.stderr or '', file=sys.stderr, flush=True)
            raise
    finally:
        subprocess.run(['rsync', '-a', p.h.HOST + ':' + private + '/evidence-after-inherited-environment/', str(destination) + '/'], check=True)
    p.frozen.verify_worker(p.h, identity)
    print(json.dumps(result), flush=True)

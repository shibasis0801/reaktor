#!/usr/bin/env python3
"""Finalize private evidence without modifying shared product source or the index."""
import datetime,difflib,hashlib,json,re,statistics,subprocess
from pathlib import Path
here=Path(__file__).resolve().parent; repo=here.parent.parent; tools=repo.parent/'tools'
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
proof=json.loads((here/'implementation-proof.json').read_text())
for name,expected in proof['ownedSources'].items():
 assert sha(repo/name)==expected,('Owned source changed since qualification',name)
names=list(dict.fromkeys([*proof['ownedSources'],'gradle.properties']))
patch=subprocess.check_output(['git','diff','--',*names],cwd=repo)
(here/'implementation.patch').write_bytes(patch)
proof['ownedSources']['gradle.properties']=sha(repo/'gradle.properties')
proof['scope']='Lazy analysis export, typed JVM archives, bounded transport and qualified Reaktor configuration-cache default'
proof['configurationCacheProof']='default-cache-proof.json'
proof['productPatchSha256']=sha(here/'implementation.patch')
changes={
 'remote-dev':[
 ("if task in observed or metadata and policy.get('taskFamilies',{}).get(task)!='jvm':","if task in observed:"),
 ("if file.is_file() and not file.is_symlink() and (task in observed or file.stat().st_size<=8*1024*1024):","if file.is_file() and not file.is_symlink() and file.stat().st_size<=8*1024*1024:")],
}
parts=[]
for name,replacements in changes.items():
 after=(tools/name).read_text();before=after
 for new,old in replacements:
  assert before.count(new)==1,(name,new);before=before.replace(new,old)
 parts.extend(difflib.unified_diff(before.splitlines(True),after.splitlines(True),fromfile='a/'+name,tofile='b/'+name))
name='test_remote_router.py';after=(tools/name).read_text()
match=re.search(r'^    def test_declared_jvm_archive_returns_without_unrelated_jars_or_compiled_trees\(self\):.*?(?=^    def |\Z)',after,re.M|re.S)
assert match,'missing router regression'
before=after[:match.start()]+after[match.end():]
parts.extend(difflib.unified_diff(before.splitlines(True),after.splitlines(True),fromfile='a/'+name,tofile='b/'+name))
(here/'tools-implementation.patch').write_text(''.join(parts))
proof['routerSources']={n:sha(tools/n) for n in ['remote-dev','test_remote_router.py']}
proof['routerPatchNote']='Only the owned two conditions and archive-ownership regression, against the preserved shared tree; the tools checkout has additional unrelated edits.'
proof['routerPatchSha256']=sha(here/'tools-implementation.patch')
rows=[json.loads(s) for s in (here/'performance-results.jsonl').read_text().splitlines()]
checks=[]
for d in sorted((here/'performance-runs').iterdir()):
 p=d/'result.json'
 if not p.exists():continue
 r=json.loads(p.read_text())
 if r['configurationCache'] is not None:continue
 log=(d/'worker.log').read_text()
 jars=[j for j in r['jars'] if '-jvm-' in j['path']];assert len(jars)==1
 checks.append({'case':d.name,'snapshot':r['snapshot'],'exit':r['code'],'gradleConsoleSecondsRounded':r.get('gradleConsoleSeconds'),
  'reservedEndToEndSeconds':r['endToEndSeconds'],'workerSourceVerified':r['workerInputsUnchanged'],
  'reused':'Reusing configuration cache.' in log,'stored':'Configuration cache entry stored.' in log,
  'invalidation':[x for x in log.splitlines() if 'configuration cache cannot be reused' in x],
  'exportTask':[x for x in log.splitlines() if x.startswith('> Task :exportBuildModel')],
  'exportInputReason':[x for x in log.splitlines() if "Input property 'declarationFiles'" in x],
  'jvmJarSha256':jars[0]['sha256'],'workerLogSha256':sha(d/'worker.log')})
assert len(checks)==6 and all(x['exit']==0 and x['workerSourceVerified'] for x in checks)
assert sum(x['reused'] for x in checks)==2
assert len({x['jvmJarSha256'] for x in checks})==1
assert all(any("'build.gradle.kts' has changed" in s for s in x['invalidation']) for x in checks if 'declaration-incremental' in x['case'] or 'default-restored' in x['case'])
probe=[r for r in rows if r['phase']=='cc-no-profile'];assert len(probe)==4 and all(r['code']==0 for r in probe)
source_edit=next(r for r in rows if r['phase']=='cc-no-profile-incremental')
b=next(j for j in probe[0]['jars'] if '-jvm-' in j['path']);a=next(j for j in source_edit['jars'] if '-jvm-' in j['path'])
changed=[k for k in sorted(set(a['classes'])|set(b['classes'])) if a['classes'].get(k)!=b['classes'].get(k)]
assert changed==['dev/shibasis/reaktor/core/utils/TimeKt.class']
cache_proof={'default':'org.gradle.configuration-cache=true in Reaktor root only',
 'invocation':'Routed :reaktor-core:jvmJar :exportBuildModel; no cache CLI switches, no init observer, no HTML profiler',
 'controls':'Same reserved secondary slot, frozen verified sources, JDK21, six workers and prior campaign resource/cache controls. Other task families and roots are not certified by this scope.',
 'checks':checks,'defaultAccepted':True,
 'ordinaryProbe':{'successfulWarmReuses':4,'roundedGradleSeconds':[r['gradleConsoleSeconds'] for r in probe],
  'reservedEndToEndSeconds':[r['endToEndSeconds'] for r in probe],
  'medianReservedEndToEndSeconds':statistics.median(r['endToEndSeconds'] for r in probe),
  'sourceEdit':{'exit':source_edit['code'],'roundedGradleSeconds':source_edit['gradleConsoleSeconds'],
    'reservedEndToEndSeconds':source_edit['endToEndSeconds'],'changedClasses':changed,'jvmJarSha256':a['sha256']}},
 'limitation':'One --profile cache reuse exited 1 after BUILD SUCCESSFUL with null TaskExecution state. It remains a failed command, excluded from successful timing summaries. javap of pinned Gradle 9.7 TaskExecution.getStatus matches the exact dereference; the failing run did not capture a full stack trace. This is a profiler diagnosis, not a proven all-task Gradle fix. No exception was suppressed.'}
(here/'default-cache-proof.json').write_text(json.dumps(cache_proof,indent=2)+'\n')
server=json.loads((here/'server-attempt-proof.json').read_text())
cli=[r for r in server['results'] if r['module']=='reaktorCli']
assert len(cli)==3 and sum(int(r['suite']['tests']) for r in cli)==17
assert not any(int(r['suite'][k]) for r in cli for k in ['failures','errors','skipped'])
proof['freshCliTests']={'cases':17,'failures':0,'errors':0,'skipped':0,'evidence':'server-attempt-proof.json','scope':'Fresh during real :serverImage prerequisites; this is not a fresh full-kernel suite.'}
(here/'implementation-proof.json').write_text(json.dumps(proof,indent=2)+'\n')
print(json.dumps({'ownedProductFiles':len(names),'routerPatchLines':len(parts),'defaultCacheChecks':len(checks),'warmUnprofiledReuses':len(probe),'freshCliTests':17}))

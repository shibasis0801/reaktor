from pathlib import Path
import subprocess,json,os,time,hashlib,shutil,zipfile
r=Path(__file__).parent
runtime=json.loads((r/'navigation-flow-frozen-runtime.json').read_text())
root=Path('/Users/ovd/dev/bestbuds/modules/engine')
java='/Users/ovd/Library/Java/JavaVirtualMachines/temurin-25.0.2/Contents/Home/bin/java'
bp='/Users/ovd/dev/tmp/dependeasy-navigation-runtime/13-reaktor-blueprint-jvm-2026.1.6-SNAPSHOT.jar'
def digest(path):
 p=Path(path)
 if p.is_file():return hashlib.sha256(p.read_bytes()).hexdigest()
 h=hashlib.sha256()
 for f in sorted(p.rglob('*')):
  if f.is_file():h.update(str(f.relative_to(p)).encode());h.update(hashlib.sha256(f.read_bytes()).digest())
 return h.hexdigest()
rows=[]
reference={p:digest(p) for p in runtime['classpath'] if p!=bp}
for pair in range(2):
 for variant in (['old','new'] if pair==0 else ['new','old']):
  assert shutil.disk_usage(r).free>=15_000_000_000,'Storage floor: wait for room before another JVM'
  common={p:digest(p) for p in runtime['classpath'] if p!=bp}
  assert common==reference,'Frozen runtime changed between samples'
  cp=[str(r/('navigation-routing-baseline.jar' if variant=='old' else 'navigation-routing-treatment.jar')) if p==bp else p for p in runtime['classpath']]
  prefix=r/f'navigation-flow-frozen-pair-{pair}-{variant}'
  before=time.time();load=os.getloadavg()
  with Path(str(prefix)+'.log').open('w') as log:
   run=subprocess.run([java,*runtime['jvmArgs'],'-Xmx512m','-cp',':'.join(cp),'org.junit.runner.JUnitCore','ai.bestbuds.reaktor.workbench.studio.StudioNavigationPerformanceTest'],cwd=root,stdout=log,stderr=subprocess.STDOUT)
  elapsed=time.time()-before
  report=root/'build/reports/studio-navigation'
  fresh=(report/'navigation.json').stat().st_mtime>=before
  changed=[p for p,sha in common.items() if digest(p)!=sha]
  with zipfile.ZipFile(str(prefix)+'.zip','w',zipfile.ZIP_DEFLATED) as z:
   if fresh:
    for f in report.iterdir():
     if f.is_file():z.write(f,f.name)
  row=dict(pair=pair,variant=variant,exitCode=run.returncode,seconds=elapsed,freshReport=fresh,changedRuntime=changed,loadBefore=load,loadAfter=os.getloadavg())
  if fresh:row['performance']=json.loads((report/'navigation.json').read_text())
  rows.append(row);(r/'navigation-flow-frozen-comparison-progress.json').write_text(json.dumps(rows,indent=2));print(json.dumps({k:v for k,v in row.items() if k!='performance'}),flush=True)
  assert not changed,'Runtime changed; discard this sample'
  assert fresh,'Historical report; discard this sample'
(r/'navigation-flow-frozen-comparison.json').write_text(json.dumps(dict(scope='Existing real StudioNavigationPerformanceTest unchanged;512MB as Gradle;four fresh JVMs;only NavigationLayoutKt class differs;all captured pictures/source witnesses/interaction/frame budget assertions preserved',rows=rows),indent=2))

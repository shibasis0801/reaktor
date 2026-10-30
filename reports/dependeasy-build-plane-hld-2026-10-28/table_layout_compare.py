from pathlib import Path
import subprocess,json,os,time,re,statistics,hashlib,shutil
r=Path(__file__).parent
runtime=json.loads((r/'table-test-runtime.json').read_text())
root=Path('/Users/ovd/dev/bestbuds/modules/engine')
java='/Users/ovd/Library/Java/JavaVirtualMachines/temurin-25.0.2/Contents/Home/bin/java'
surface='/Users/ovd/dev/reaktor/reaktor-surface-compose/build/libs/reaktor-surface-compose-jvm-2026.1.6-SNAPSHOT.jar'
rows=[]
shutil.copy2(surface,r/'table-treatment-surface.jar')
def digest(path):
 p=Path(path)
 if p.is_file():return hashlib.sha256(p.read_bytes()).hexdigest()
 h=hashlib.sha256()
 for f in sorted(p.rglob('*')):
  if f.is_file():h.update(str(f.relative_to(p)).encode());h.update(hashlib.sha256(f.read_bytes()).digest())
 return h.hexdigest()
common={p:digest(p) for p in runtime['classpath'] if p!=surface}
for pair in range(4):
 for variant in (['old','new'] if pair%2==0 else ['new','old']):
  for p,sha in common.items():assert digest(p)==sha,'Runtime changed: '+p
  cp=[str(r/('table-baseline-surface.jar' if variant=='old' else 'table-treatment-surface.jar')) if p==surface else p for p in runtime['classpath']]
  prefix=r/f'table-pair-{pair}-{variant}'
  args=runtime['jvmArgs']+['-Xmx2g',f'-XX:StartFlightRecording=settings=profile,filename={prefix}.jfr,dumponexit=true']
  started=time.monotonic();startedEpoch=time.time();before=os.getloadavg()
  with Path(str(prefix)+'.log').open('w') as log:
   run=subprocess.run([java,*args,'-cp',':'.join(cp),'org.junit.runner.JUnitCore','ai.bestbuds.reaktor.CollectionFrameTest'],cwd=root,stdout=log,stderr=subprocess.STDOUT)
  elapsed=time.monotonic()-started
  report=root/'build/reports/collection-frame/data-table.txt'
  assert report.stat().st_mtime>=startedEpoch,'Frame report is historical; do not interpret timings'
  text=report.read_text()
  Path(str(prefix)+'.frame.txt').write_text(text)
  match=re.search(r'median p95, wheel: reference ([0-9.]+) ms, data table ([0-9.]+) ms',text)
  ratios=re.search(r'within each round: wheel ([0-9.]+), PageDown ([0-9.]+)',text)
  pages=re.search(r'median of the mean PageDown frame: reference ([0-9.]+) ms, data table ([0-9.]+) ms;.*key p95 ([0-9.]+) ms',text)
  assert match and ratios and pages,text
  row=dict(pair=pair,variant=variant,exitCode=run.returncode,seconds=elapsed,wheelReferenceP95ms=float(match[1]),wheelCandidateP95ms=float(match[2]),wheelRatio=float(ratios[1]),pageRatio=float(ratios[2]),pageReferenceMeanMs=float(pages[1]),pageCandidateMeanMs=float(pages[2]),keyP95ms=float(pages[3]),loadBefore=before,loadAfter=os.getloadavg())
  rows.append(row);(r/'table-layout-comparison-progress.json').write_text(json.dumps(rows,indent=2));print(json.dumps(row),flush=True)
med={v:{k:statistics.median(x[k] for x in rows if x['variant']==v) for k in ['wheelCandidateP95ms','wheelRatio','pageCandidateMeanMs','pageRatio','keyP95ms','seconds']} for v in ['old','new']}
result=dict(scope='Existing3 CollectionFrameTest cases; fixed exact runtime, only surfaceComposeJAR changes in2compiledclassentries; fresh JVM per sample;4 alternatingpairs; JFR all8; assertions/budgets unchanged; no Gradle or image time claim',rows=rows,median=med,allPass=all(x['exitCode']==0 for x in rows),commonRuntimeHashes=common)
(r/'table-layout-comparison.json').write_text(json.dumps(result,indent=2));print(json.dumps(med),flush=True)

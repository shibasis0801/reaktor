from pathlib import Path
import hashlib,json,os,subprocess,time,zipfile
r=Path(__file__).parent
root=Path('/Users/ovd/dev/bestbuds/modules/engine')
runtime=json.loads((r/'table-test-runtime.json').read_text())
java='/Users/ovd/Library/Java/JavaVirtualMachines/temurin-25.0.2/Contents/Home/bin/java'
source=root/'src/jvmTest/kotlin/ai/bestbuds/reaktor/workbench/MachineSignalTooltipTest.kt'
def digest(p):
 p=Path(p)
 if p.is_file():return hashlib.sha256(p.read_bytes()).hexdigest()
 h=hashlib.sha256()
 for f in sorted(p.rglob('*')):
  if f.is_file():h.update(str(f.relative_to(p)).encode());h.update(hashlib.sha256(f.read_bytes()).digest())
 return h.hexdigest()
manifest={p:digest(p) for p in runtime['classpath']}
source_sha=digest(source)
xmls=list((root/'build/test-results/jvmTest').glob('TEST-*.xml'))
assert len(xmls)==2
with zipfile.ZipFile(r/'tooltip-scene-and-table-test-results.zip','w',zipfile.ZIP_DEFLATED) as z:
 for f in xmls:z.write(f,f.name)
(r/'tooltip-scene-qualified-table-report.txt').write_bytes((root/'build/reports/collection-frame/data-table.txt').read_bytes())
rows=[]
for i in range(8):
 started=time.monotonic()
 with (r/f'tooltip-scene-repeat-{i}.log').open('w') as out:
  result=subprocess.run([java,*runtime['jvmArgs'],'-Xmx2g','-cp',':'.join(runtime['classpath']),'org.junit.runner.JUnitCore','ai.bestbuds.reaktor.workbench.MachineSignalTooltipTest'],cwd=root,stdout=out,stderr=subprocess.STDOUT,timeout=40)
 row={'iteration':i,'exitCode':result.returncode,'seconds':time.monotonic()-started,'load':os.getloadavg()}
 rows.append(row);print(json.dumps(row),flush=True)
 assert result.returncode==0
assert digest(source)==source_sha
assert manifest=={p:digest(p) for p in runtime['classpath']}
proof={'scope':'Eight fresh JVMs of the two real tooltip fixture cases after normal Gradle compile/check. Production timing, disabled authority, density/pixel/bounds assertions unchanged. No build or product performance claim.','rows':rows,'tests':16,'sourceSha256':source_sha,'allPass':True,'runtimeUnchanged':True,'runtimeSha256':manifest}
(r/'tooltip-scene-repeat-proof.json').write_text(json.dumps(proof,indent=2)+'\n')

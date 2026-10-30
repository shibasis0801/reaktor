import pathlib,subprocess,statistics,json,hashlib,os
r=pathlib.Path(__file__).parent
java="/Users/ovd/Library/Java/JavaVirtualMachines/temurin-25.0.2/Contents/Home/bin/java"
base=str(r)+":/Users/ovd/.reaktor/dist/conductor/lib/*"
output=pathlib.Path("/Users/ovd/dev/tmp/dependeasy-source-capture-comparison")
output.mkdir(exist_ok=True)
rows=[]
for pair in range(4):
 for variant in (["old","new"] if pair%2==0 else ["new","old"]):
  cp=str(r/("source-capture-treatment.jar" if variant=="new" else "source-capture-baseline.jar"))+":"+base
  prefix=output/f"source-capture-{pair}-{variant}"
  before=os.getloadavg()
  subprocess.run([java,"-Xmx2g","-cp",cp,"SourceCaptureCompare",variant,str(prefix)],check=True)
  values=pathlib.Path(str(prefix)+".result.txt").read_text().splitlines()
  rows.append(dict(pair=pair,variant=variant,seconds=float(values[0]),id=values[1],candidateSha256=values[2],diffSha256=values[3],complete=values[4],files=int(values[5]),loadBefore=before,loadAfter=os.getloadavg()))
  (output/"source-capture-comparison-progress.json").write_text(json.dumps(rows,indent=2))
assert len({(x['id'],x['candidateSha256'],x['diffSha256'],x['complete'],x['files']) for x in rows})==1,"Source or output changed; do not interpret timings"
med={k:statistics.median(x['seconds'] for x in rows if x['variant']==k) for k in ['old','new']}
result=dict(scope="SourceCandidates.capture only; old installed class vs checked treatment overlay; identical runtime and current source; 4 alternating pairs; current foreign dirty submodule refusal preserved",rows=rows,medianSeconds=med,reductionPercent=100*(1-med['new']/med['old']),outputsIdentical=True)
(output/"source-capture-comparison.json").write_text(json.dumps(result,indent=2));print(json.dumps(result,indent=2))

"""Retire this chat's completed cache inputs under their individual lifetime leases."""
import gzip,hashlib,json,shutil,sys
from pathlib import Path
sys.path.insert(0,'/Users/ovd/dev/tools')
import workspace_storage as w
report=Path(__file__).parent
ids=['1ebf1b435c92c8e0700176378ebac967c15ceb2df56d6af4dbe9ef42626557a0','93d47f8e39cbb2bb7d24ccb19de9c9cd942bfd5b41d6bf6b22be73524a319f1c']
result={'removed':[],'scope':'Only two completed benchmark cache inputs from this chat. Per-snapshot exclusive lifetime lease, current completed-candidate membership, open-file/CWD and idle checks. No primary source, active worktree or other cache is touched.'}
for identity in ids:
 p=w.STATE/'snapshots'/identity
 if not p.exists():continue
 with w.eviction_pin(p)as available:
  assert available,'Active snapshot lifetime lease'
  assert p in {p for _,p in w.remote_candidates()},'Unfinished build reference'
  files,working=w.open_paths()
  assert not w.indexed_in_use(p,w.use_index(files,working,[w.STATE])),'Open cached source'
  assert w.quiet(p,900),'Recent cache input'
  data=(p/'manifest.json').read_bytes();z=report/('expired-benchmark-input-'+identity+'.manifest.json.gz')
  with gzip.open(z,'wb')as f:f.write(data)
  size=w.charged_bytes([p])
  shutil.rmtree(p)
  result['removed'].append({'snapshot':identity,'bytes':size,'manifestSha256':hashlib.sha256(data).hexdigest(),'retainedManifest':z.name})
result['freeGB']=shutil.disk_usage(w.ROOT).free/1e9
(report/'storage-completed-benchmark-cache-cleanup.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps(result),flush=True)

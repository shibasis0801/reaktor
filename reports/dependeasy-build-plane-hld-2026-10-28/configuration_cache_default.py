#!/usr/bin/env python3
import contextlib,json,time,hashlib
import performance_followup as p
original=p.STATE
D=json.loads((p.HERE/'performance-snapshots.json').read_text())
with contextlib.ExitStack() as stack:
 for identity in D['variants']['after'].values():stack.enter_context(p.workspace_storage.snapshot_pin(original,identity))
 stack.enter_context(p.workspace_storage.graph_activity(original))
 p.h.ROOT=p.materialize(D['variants']['after']['base'])
 properties=p.h.ROOT/'reaktor/gradle.properties';text=properties.read_text()
 assert 'org.gradle.configuration-cache=' not in text
 properties.write_text(text.replace('org.gradle.caching=true','org.gradle.caching=true\norg.gradle.configuration-cache=true'))
 base=p.frozen.freeze(p.h);stack.enter_context(p.workspace_storage.snapshot_pin(original,base))
 declaration=p.h.ROOT/'reaktor/build.gradle.kts';original_text=declaration.read_text()
 declaration.write_text(original_text+'\n// Configuration-cache declaration freshness qualification.\n')
 edited=p.frozen.freeze(p.h);stack.enter_context(p.workspace_storage.snapshot_pin(original,edited))
 D['variants']['after-default']={'base':base,'incremental':edited}
 start=time.monotonic();stack.enter_context(p.frozen.lease(original/'worker-slot-1.lock'))
 stack.enter_context(p.h.worker_slot(1));p.STATE=p.TEMP/'state';p.h.STATE=p.STATE
 (p.HERE/'configuration-cache-default-reservation.json').write_text(json.dumps({'queueSeconds':time.monotonic()-start,'snapshots':D['variants']['after-default'],'scriptSha256':hashlib.sha256(p.Path(__file__).read_bytes()).hexdigest()},indent=2)+'\n')
 # Reuse the same campaign-owned Gradle home and reset implementation.
 D['variants']['after']=D['variants']['after-default']
 p.run(D,'after','cold',4,cc=None,profile=False)
 for rep in range(1,4):p.run(D,'after','default-warm',rep,cc=None,profile=False)
 p.run(D,'after','declaration-incremental',1,cc=None,profile=False)
 p.run(D,'after','default-restored',1,cc=None,profile=False)

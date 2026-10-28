#!/usr/bin/env python3
import contextlib,json,time,hashlib
import performance_followup as p
original=p.STATE
D=json.loads((p.HERE/'performance-snapshots.json').read_text())
with contextlib.ExitStack() as stack:
 for variant in D['variants'].values():
  for identity in variant.values():stack.enter_context(p.workspace_storage.snapshot_pin(original,identity))
 start=time.monotonic();stack.enter_context(p.frozen.lease(original/'worker-slot-1.lock'))
 stack.enter_context(p.workspace_storage.graph_activity(original));stack.enter_context(p.h.worker_slot(1))
 p.STATE=p.TEMP/'state';p.h.STATE=p.STATE
 (p.HERE/'configuration-cache-probe-reservation.json').write_text(json.dumps({'queueSeconds':time.monotonic()-start,'scriptSha256':hashlib.sha256(p.Path(__file__).read_bytes()).hexdigest()},indent=2)+'\n')
 try:p.run(D,'after','cc-profile-repro',3,cc=True)
 except RuntimeError as e:print(str(e),flush=True)
 for rep in range(1,5):p.run(D,'after','cc-no-profile',rep,cc=True,profile=False)
 p.run(D,'after','cc-no-profile-incremental',1,cc=True,profile=False)

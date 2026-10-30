#!/usr/bin/env python3
"""Retrieve an up-to-date ZIP under its worker lease and verify qualified libraries."""
import hashlib
import json
import subprocess
import time
import zipfile
from pathlib import Path
import performance_followup as p

run = '20261028-222301-0953f474-bestbuds'
artifacts = p.DEV / '.remote-dev/artifacts' / run
relative = 'bestbuds/targets/reaktorCli/build/distributions/reaktor.zip'
destination = artifacts / relative
expected = {f.name: hashlib.sha256(f.read_bytes()).hexdigest()
            for f in artifacts.rglob('*.jar') if f.parent.name == 'libs'}
assert len(expected) >= 16 and any('reaktor-tooling-' in n for n in expected)
started = time.monotonic()
with p.frozen.lease(p.GLOBAL_STATE / 'worker-slot-1.lock'), p.h.worker_slot(1, prepare=False):
    admission = time.monotonic() - started
    source = p.h.REMOTE + '/' + relative
    observed = p.remote('''import hashlib,json,sys,zipfile
from pathlib import Path
path=Path(sys.argv[1]); expected=json.loads(sys.argv[2])
with zipfile.ZipFile(path) as archive:
 actual={Path(n).name:hashlib.sha256(archive.read(n)).hexdigest() for n in archive.namelist() if '/lib/' in n and n.endswith('.jar')}
 mismatches={n:{'expected':h,'actual':actual.get(n)} for n,h in expected.items() if actual.get(n)!=h}
 assert not mismatches,'Archive does not contain the qualified library bytes: '+json.dumps(mismatches)
print(json.dumps({'sha256':hashlib.sha256(path.read_bytes()).hexdigest(),'bytes':path.stat().st_size,'verifiedLibraryHashes':expected}))
''', source, json.dumps(expected))
    destination.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(['scp', '-q', p.h.HOST + ':' + source, str(destination)], check=True)
    assert hashlib.sha256(destination.read_bytes()).hexdigest() == observed['sha256']
    with zipfile.ZipFile(destination) as archive:
        actual = {Path(n).name: hashlib.sha256(archive.read(n)).hexdigest()
                  for n in archive.namelist() if '/lib/' in n and n.endswith('.jar')}
        assert all(actual.get(n) == h for n, h in expected.items())
    observed.update(run=run, workerSlot=1, retrievalAdmissionSeconds=admission,
                    source=source, destination=str(destination),
                    scope='Manual retrieval after successful distZip UP-TO-DATE. Automatic invocation-scoped copy-back withheld the old ZIP. All 16 qualified producer JARs are byte-identical inside this archive.')
    (p.HERE / 'qualified-archive-retrieval.json').write_text(json.dumps(observed, indent=2) + '\n')
    print(json.dumps({k:v for k,v in observed.items() if k != 'verifiedLibraryHashes'}), flush=True)

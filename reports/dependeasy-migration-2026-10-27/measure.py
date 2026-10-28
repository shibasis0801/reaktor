#!/usr/bin/env python3
"""Reproduce the read-only inventory and CLI measurements in this report."""
import argparse
import collections
import hashlib
import json
import os
from pathlib import Path
import platform
import statistics
import subprocess
import time

OUT = Path(__file__).resolve().parent
DEV = Path('/Users/ovd/dev')


def command(args, root, timeout=45):
    return subprocess.run(args, cwd=root, text=True, capture_output=True,
                          timeout=timeout, check=False)


def git(root, *args):
    result = command(['git', *args], root)
    if result.returncode:
        raise RuntimeError(result.stderr)
    return result.stdout


def source_files(root, ref):
    if ref == 'working':
        names = git(root, 'ls-files', '--cached', '--others', '--exclude-standard', '-z').split('\0')
        return {n: (root / n).read_bytes() for n in set(names) if n and (root / n).is_file() and relevant(n)}
    names = git(root, 'ls-tree', '-r', '--name-only', '-z', ref).split('\0')
    names = [n for n in names if n and relevant(n)]
    request = ''.join(f'{ref}:{n}\n' for n in names).encode()
    raw = subprocess.run(['git', 'cat-file', '--batch'], input=request, cwd=root,
                         capture_output=True, check=True).stdout
    result, offset = {}, 0
    for name in names:
        end = raw.index(b'\n', offset)
        header = raw[offset:end].split()
        size = int(header[-1])
        result[name] = raw[end + 1:end + 1 + size]
        offset = end + 2 + size
    return result


def relevant(name):
    parts = Path(name).parts
    if any(p in {'build', '.gradle', 'node_modules', 'vendor', 'external', 'third_party',
                 'drivers', 'experiments', 'tmp', 'temp', '.tmp', 'reports', 'papers', 'agent'} for p in parts):
        return False
    if any(t in name for t in ('/ts/import/', '/ts/export/', '/bin/main/')):
        return False
    return name.endswith(('.gradle.kts', '.kt', '.cmake', '.rb', '/Fastfile', '/CMakeLists.txt', '/package.json')) or name in {'Fastfile', 'CMakeLists.txt', 'package.json'}


def inventory(files):
    groups = collections.defaultdict(list)
    for name, blob in sorted(files.items()):
        if name.endswith('.gradle.kts'):
            groups['gradleScripts'].append((name, blob))
        if name.endswith('.kt') and ('dependeasy/' in name or 'buildSrc/' in name):
            if '/src/main/' in name or '/src/commonMain/' in name:
                groups['kotlinBuildImplementation'].append((name, blob))
            elif '/src/test/' in name or '/src/commonTest/' in name:
                groups['kotlinBuildTests'].append((name, blob))
        if name.endswith('.cmake') or Path(name).name == 'CMakeLists.txt':
            groups['cmake'].append((name, blob))
        if (name.endswith('.rb') or Path(name).name == 'Fastfile') and not any(p in Path(name).parts for p in ('test', 'tests', 'spec')):
            groups['ruby'].append((name, blob))
    counts = {k: {'files': len(v), 'lines': sum(len(b.decode(errors='replace').splitlines()) for _, b in v)} for k, v in groups.items()}
    packages = {'manifests': 0, 'scripts': 0, 'authored': 0, 'generated': 0, 'invalid': []}
    for name, blob in files.items():
        if Path(name).name != 'package.json':
            continue
        try:
            j = json.loads(blob)
        except (ValueError, UnicodeDecodeError):
            packages['invalid'].append(name)
            continue
        scripts = j.get('scripts', {})
        generated = j.get('dependeasy', {}).get('generatedScripts', {})
        # The repository stores the alias register at top level.
        generated = j.get('generatedScripts', generated)
        packages['manifests'] += 1
        packages['scripts'] += len(scripts)
        packages['generated'] += len(set(scripts) & set(generated))
        packages['authored'] += len(set(scripts) - set(generated))
    counts['packages'] = packages
    counts['rootBuildLines'] = len(files.get('build.gradle.kts', b'').splitlines())
    return counts


def model_inventory(root):
    path = root / 'build/dependeasy/model.json'
    if not path.exists():
        return None
    blob = path.read_bytes()
    j = json.loads(blob)
    nodes = j['nodes']
    identity = lambda n: ':'.join((n['build'], n['project'].strip(':'), n['name'])).replace('::', ':')
    ids = [identity(n['id']) for n in nodes]
    kernels = [n for n in nodes if n.get('kernel')]
    native_kernels = [n for n in kernels if n['kernel']['className'].startswith('dev.shibasis.dependeasy.')]
    names = {identity(n['id']) for n in nodes}
    return {
        'bytes': len(blob), 'sha256': hashlib.sha256(blob).hexdigest(),
        'schema': j['schema'], 'builds': j['builds'], 'nodes': len(nodes), 'edges': len(j['edges']),
        'declarationInputs': len(j['declarations']), 'omissions': j.get('omissions', []),
        'effects': dict(collections.Counter(n['effect'] for n in nodes)),
        'kinds': dict(collections.Counter(n['kind'] for n in nodes)),
        'duplicateNodeIds': len(ids) - len(set(ids)),
        'nodesWithoutDeclarationSite': sum(not n.get('declaredAt') for n in nodes),
        'nodesWithKernel': len(kernels), 'ownKernelCount': len(native_kernels),
        'ownKernelsWithoutSpec': sum(not n['kernel'].get('spec') for n in native_kernels),
        'nodesWithTools': sum(bool(n.get('tools')) for n in nodes),
        'edgesByType': dict(collections.Counter(e['type'].split('.')[-1] for e in j['edges'])),
        'danglingEdges': sum(identity(e['from']) not in names or identity(e['to']) not in names for e in j['edges']),
        'verificationWithoutCoverage': sum(n['effect'] == 'Verify' and not n.get('coverage') for n in nodes),
        'receiptFiles': len(list((root / 'build/dependeasy/receipts').glob('*.json'))),
    }


def inventory_run():
    result = {'recordedAt': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()), 'repositories': {}}
    for repo in ('reaktor', 'bestbuds', 'manna'):
        root = DEV / repo
        main = git(root, 'rev-parse', 'origin/main').strip()
        september = git(root, 'rev-list', '--first-parent', '-1', '--before=2026-10-01T00:00:00+05:30', 'HEAD').strip()
        refs = {'originMain': main, 'working': 'working'}
        if september and repo != 'manna':
            refs['septemberBranch'] = september
        entry = {'head': git(root, 'rev-parse', 'HEAD').strip(),
                 'dirtyPaths': len(git(root, 'status', '--porcelain').splitlines()),
                 'snapshots': {}, 'model': model_inventory(root)}
        for label, ref in refs.items():
            info = inventory(source_files(root, ref))
            info['ref'] = ref
            if ref != 'working':
                info['commitDate'] = git(root, 'show', '-s', '--format=%cI', ref).strip()
            entry['snapshots'][label] = info
        result['repositories'][repo] = entry
    (OUT / 'inventory.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2))


def cli_run():
    root = DEV / 'bestbuds'
    before = model_inventory(root)
    commands = {
        'list': ['build', 'list', '--limit', '5'],
        'show': ['build', 'show', 'bestbuds:portableChecks'],
        'tree': ['build', 'tree', 'bestbuds:portableChecks', '--depth', '2'],
        'explain': ['build', 'explain', 'bestbuds:portableChecks'],
        'kernels': ['build', 'kernels', 'bestbuds:portableChecks'],
        'receipts': ['build', 'receipts', 'bestbuds:portableChecks'],
        'story': ['build', 'story', 'bestbuds:portableChecks', '--depth', '2'],
    }
    result = {'recordedAt': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()),
              'method': 'Five serial fresh CLI processes per operation; first sample retained, no cold cache reset; wall time includes JVM startup and local owner RPC.',
              'platform': platform.platform(), 'modelBefore': before, 'operations': {}}
    for label, args in commands.items():
        samples = []
        for repeat in range(5):
            started = time.perf_counter()
            try:
                run = command(['/Users/ovd/.reaktor/bin/reaktor', *args, '--dir', str(root), '--json'], root, 45)
                sample = {'ms': (time.perf_counter() - started) * 1000, 'exit': run.returncode,
                          'stdoutBytes': len(run.stdout.encode()), 'stderr': run.stderr[:1200]}
                if repeat == 0 or run.returncode != 0:
                    sample['response'] = json.loads(run.stdout) if run.stdout.strip().startswith('{') else run.stdout[:1500]
            except (subprocess.TimeoutExpired, ValueError) as exc:
                sample = {'ms': (time.perf_counter() - started) * 1000, 'error': str(exc)[:1200]}
            samples.append(sample)
            if sample.get('exit', 1) != 0:
                break
        values = [s['ms'] for s in samples]
        successful = [s['ms'] for s in samples if s.get('exit', 1) == 0]
        result['operations'][label] = {'samples': samples, 'medianMs': statistics.median(values),
                                       'minMs': min(values), 'maxMs': max(values),
                                       'successfulSamples': len(successful),
                                       'successfulMedianMs': statistics.median(successful) if successful else None}
        (OUT / 'cli-benchmark.json').write_text(json.dumps(result, indent=2) + '\n')
        print(label, round(statistics.median(values), 1), 'ms', [s.get('exit', 'error') for s in samples], flush=True)
    result['modelStable'] = before['sha256'] == model_inventory(root)['sha256']
    (OUT / 'cli-benchmark.json').write_text(json.dumps(result, indent=2) + '\n')


def gradle_run(followup=False):
    root = DEV / 'reaktor'
    result = {'recordedAt': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()),
              'head': git(root, 'rev-parse', 'HEAD').strip(), 'runs': []}
    cases = [('no-configuration-cache', '--no-configuration-cache'),
             ('configuration-cache-first', '--configuration-cache'),
             ('configuration-cache-repeat', '--configuration-cache')]
    if followup:
        result = json.loads((OUT / 'gradle-benchmark.json').read_text())
        cases = [('configuration-cache-followup', '--configuration-cache')]
    for label, flag in cases:
        args = ['./gradlew', ':reaktor-core:jvmJar', flag, '--no-daemon', '--console=plain']
        log = OUT / f'gradle-{label}.log'
        started = time.perf_counter()
        with log.open('w') as stream:
            process = subprocess.run(args, cwd=root, stdout=stream, stderr=subprocess.STDOUT, check=False)
        row = {'label': label, 'arguments': args, 'exit': process.returncode,
               'wallSeconds': time.perf_counter() - started, 'log': log.name}
        result['runs'].append(row)
        (OUT / 'gradle-benchmark.json').write_text(json.dumps(result, indent=2) + '\n')
        print(json.dumps(row), flush=True)
        if process.returncode:
            break


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('mode', choices=['inventory', 'cli', 'gradle', 'gradle-followup'])
    mode = parser.parse_args().mode
    {'inventory': inventory_run, 'cli': cli_run, 'gradle': gradle_run,
     'gradle-followup': lambda: gradle_run(followup=True)}[mode]()

"""Bound disposable local build payloads; retain source, receipts and diagnostics."""
import contextlib
import datetime
import fcntl
import json
import os
from pathlib import Path
import shutil
import subprocess
import time


MARKER = '.payload-pruned.json'
DIAGNOSTICS = {'reports', 'test-results', 'hangar-flows', 'hangar-shards', 'dependeasy'}
GENERATED = {'build', '.gradle', '.kotlin', '.cxx', 'node_modules'}



def configured_slot_roots(state, config=None):
    state = Path(state).absolute()
    if state != Path(os.path.normpath(state)):
        raise ValueError('State path must be normalized')
    if config is None:
        path = state / 'config.json'
        config = json.loads(path.read_text()) if path.exists() else {}
    value = config.get('local_slots_root', str(state / 'slots'))
    if not isinstance(value, str) or not value or '\n' in value or '\0' in value:
        raise ValueError('Invalid configured slot root')
    configured = Path(value)
    if not configured.is_absolute() or configured != Path(os.path.normpath(value)) or '..' in configured.parts:
        raise ValueError('Slot root must be absolute and normalized')
    if configured.parent != state or configured.name not in {'slots', 'slots.noindex'}:
        raise ValueError('Slot root must be a managed state child')
    roots = [configured] + [state / name for name in ('slots', 'slots.noindex') if os.path.lexists(state / name)]
    for path in roots:
        if any(parent.is_symlink() for parent in (path, *path.parents)):
            raise ValueError('Slot roots and their ancestors must be real directories')
        if path.exists() and not path.is_dir():
            raise ValueError('Slot root is not a directory')
    return tuple(dict.fromkeys(roots))


def receipt_root_matches(receipt, expected):
    original = receipt.get('remote_root')
    if original is None:
        return not any(part.endswith('.noindex') for part in Path(expected).parts)
    return original == expected


def require_admission(state):
    marker = Path(state) / 'maintenance.json'
    if os.path.lexists(marker):
        if marker.is_symlink() or not marker.is_file():
            raise RuntimeError('Maintenance gate is unreadable; admission refused')
        value = json.loads(marker.read_text())
        if value.get('active') is not False:
            raise RuntimeError('Reviewed maintenance window: new work is not admitted')


def read_json(path):
    try:
        value = json.loads(path.read_text())
        return value if isinstance(value, dict) else {}
    except (OSError, ValueError):
        return {}


def abandoned(receipt):
    """When storage cleanup found the run's router gone; any other value in the field is someone else's note."""
    try:
        return datetime.datetime.fromisoformat(receipt.get('abandoned'))
    except (TypeError, ValueError):
        return None


def allocated_bytes(path):
    # du counts hard links once within each tree and does not traverse symlinks.
    result = subprocess.run(['du', '-sk', str(path)], capture_output=True, text=True, check=True)
    return int(result.stdout.split()[0]) * 1024


def payloads(helper):
    archive = helper.STATE / 'artifacts'
    for root in sorted(archive.iterdir(), reverse=True) if archive.exists() else []:
        if root.is_symlink() or not root.is_dir() or read_json(root / MARKER).get('complete'):
            continue
        receipt = read_json(helper.STATE / 'logs' / (root.name + '.json'))
        if not receipt.get('finished') and not abandoned(receipt):
            continue
        family = receipt.get('artifact_family') or helper.artifact_family(receipt.get('tasks', []))
        projects = tuple(p.name for p in root.iterdir() if p.is_dir() and not p.is_symlink())
        successful = (bool(receipt.get('finished')) and receipt.get('exit_code') == 0
                      and not receipt.get('copy_error') and not (root / MARKER).exists())
        yield root, tuple((name, family) for name in projects) if successful else ()
    runs = helper.STATE / 'runs'
    for run in sorted(runs.iterdir(), reverse=True) if runs.exists() else []:
        if run.is_symlink() or not run.is_dir():
            continue
        root = run / 'artifacts'
        receipt = read_json(run / 'receipt.json')
        if (root.is_dir() and not root.is_symlink() and not read_json(root / MARKER).get('complete')
                and receipt.get('finished') and not receipt.get('workerMayContinue')):
            yield root, ()  # Isolated evidence is never an rsync reference.


def trim_payload(root):
    """Keep only declared report subtrees, without following their symlinks."""
    marker = {'pruned': datetime.datetime.now().isoformat(), 'retained': sorted(DIAGNOSTICS),
              'complete': False}
    # An interrupted cleanup must never leave a partial rsync reference eligible.
    (root / MARKER).write_text(json.dumps(marker) + '\n')
    removed = 0
    for directory, dirs, files in os.walk(root, topdown=False, followlinks=False):
        path = Path(directory)
        parts = path.relative_to(root).parts
        if any(part == 'build' and i + 1 < len(parts) and parts[i + 1] in DIAGNOSTICS
               for i, part in enumerate(parts)):
            continue
        for filename in files:
            if path == root and filename == MARKER:
                continue
            (path / filename).unlink()
            removed += 1
        for name in dirs:
            entry = path / name
            if entry.is_symlink():
                entry.unlink()
            elif not any(entry.iterdir()):
                entry.rmdir()
    marker['complete'] = True
    (root / MARKER).write_text(json.dumps(marker) + '\n')
    return removed


def slot_caches(root):
    paths = []
    for directory, dirs, _ in os.walk(root, followlinks=False):
        for name in list(dirs):
            path = Path(directory) / name
            if name == '.git' or path.is_symlink():
                dirs.remove(name)
            elif name in GENERATED:
                dirs.remove(name)
                paths.append(path)
    return paths


def cache_bytes(paths):
    if not paths:
        return 0
    result = subprocess.run(['du', '-sk', *map(str, paths)], capture_output=True, text=True, check=True)
    return sum(int(line.split()[0]) * 1024 for line in result.stdout.splitlines())


def trim_slot(root):
    paths = slot_caches(root)
    for path in paths:
        shutil.rmtree(path)
    return list(map(str, paths))


@contextlib.contextmanager
def idle_locks(state, wait=False, held_locks=()):
    while True:
        with contextlib.ExitStack() as locks:
            blocked = None
            for name in ('graph-activity', 'workspace', 'local-gradle', 'worker-gradle', 'worker-slot-1', 'worker-slot-2', 'capture'):
                if name in held_locks:
                    continue
                lock = locks.enter_context((state / (name + '.lock')).open('a'))
                try:
                    fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
                except BlockingIOError:
                    blocked = name
                    break
            if blocked is None:
                yield True
                return
            if not wait:
                yield False
                return
        # Builds can hold a worker lease while acquiring the local publication
        # lease. Release every cleanup lock before waiting for either host.
        print(f'Waiting for {blocked} lock before cleanup', flush=True)
        time.sleep(1)


def cleanup(helper, dry_run=False, trim_slots=False, wait=False, held_locks=()):
    """Skip busy workspaces; every mutating build owns at least one of these locks."""
    with idle_locks(helper.STATE, wait, held_locks) as idle:
        if not idle:
            return {'skipped': 'build or source capture in progress'}
        config = helper.CONFIG
        budget = int(config.get('artifact_retention_mib', 8192)) * 1024 * 1024
        slot_budget = int(config.get('slot_retention_mib', 4096)) * 1024 * 1024
        if budget < 0 or slot_budget < 0:
            raise ValueError('Retention budgets must be nonnegative')
        report_path = helper.STATE / 'retention.json'
        before = shutil.disk_usage(helper.STATE).free
        report = {'started': datetime.datetime.now().isoformat(), 'dry_run': dry_run,
                  'free_bytes_before': before, 'artifact_budget_bytes': budget,
                  'slot_budget_bytes': slot_budget, 'pruned_payloads': [], 'trimmed_slots': []}
        entries = sorted(payloads(helper), key=lambda item: item[0].parent.name
                         if item[0].name == 'artifacts' else item[0].name, reverse=True)
        retained, covered = 0, set()
        for root, keys in entries:
            size = allocated_bytes(root)
            needed = bool(set(keys) - covered)
            if needed and retained + size <= budget:
                retained += size
                covered.update(keys)
                continue
            report['pruned_payloads'].append({'path': str(root), 'allocated_bytes_before': size})
            if not dry_run:
                trim_payload(root)
                print(f'Pruned generated payload: {root}', flush=True)
        slots = [slot for base in configured_slot_roots(helper.STATE, helper.CONFIG) if base.exists() for slot in sorted(base.iterdir())]
        for slot in slots:
            if slot.is_symlink() or not slot.is_dir():
                continue
            if trim_slots or cache_bytes(slot_caches(slot)) > slot_budget:
                report['trimmed_slots'].append(str(slot))
                if not dry_run:
                    report.setdefault('removed_slot_directories', []).extend(trim_slot(slot))
                    print(f'Trimmed isolated build caches: {slot}', flush=True)
        report['retained_payload_bytes'] = retained
        report['free_bytes_after'] = shutil.disk_usage(helper.STATE).free
        report['finished'] = datetime.datetime.now().isoformat()
        if not dry_run:
            report_path.write_text(json.dumps(report, indent=2) + '\n')
        return report


def automatic(helper, held_locks=()):
    report = helper.STATE / 'retention.json'
    interval = int(helper.CONFIG.get('retention_interval_seconds', 0))
    if interval < 0:
        print('Local build retention deferred: interval must be nonnegative', flush=True)
        return
    if report.exists() and time.time() - report.stat().st_mtime < interval:
        return
    try:
        cleanup(helper, held_locks=held_locks)
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        print(f'Local build retention deferred: {error}', flush=True)

"""Workspace cache budgets. Never follows symlinks or evicts open/active work."""
import contextlib
from concurrent.futures import ThreadPoolExecutor
import datetime
import fcntl
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import time

import remote_retention
from cluster_locations import state_root, workspace_root


TOOLS = Path(__file__).resolve().parent
ROOT = workspace_root(__file__)
STATE = state_root(ROOT)
GENERATED = remote_retention.GENERATED | {'.github_modules', 'Pods', 'DerivedData', 'xcode-derived',
                                        'SourcePackages', 'ModuleCache.noindex', 'Intermediates.noindex',
                                        'Index.noindex', 'SDKStatCaches.noindex', '.build'}
TEMP_PREFIXES = ('tmp', '.tmp', 'included', 'konan_temp', 'gradle', 'kotlin', 'jansi',
                 'reaktor', 'bestbuds', 'manna', 'gymbuddy', 'nexergy', 'hangar',
                 'claude-', 'codex-', 'webmcp', 'chromium-webmcp', 'pnpm-verify')
PROTECTED_NAMES = {'config.json', 'config', '.env', 'dev-auth-fixtures.json',
                   'dev-auth-fixtures.dev.json', 'docker-migration', 'build-cache',
                   'workspace.lock', 'local-gradle.lock', 'local-slot-1.lock', 'worker-gradle.lock', 'worker-slot-1.lock',
                   'worker-slot-2.lock', 'publications.sqlite3', 'capture.lock',
                   'storage.lock', 'graph-activity.lock', 'storage-budget.json', 'retention.json',
                   'storage-cleanup-20261013.json', 'cleanup-20261003.json',
                   'config.toml', 'kubeconfig', 'id_rsa', 'id_ed25519', 'credentials',
                   '.netrc', '.npmrc', '.pgpass', 'e2ee-candidate-20261029'}
SECRET_SUFFIXES = {'.key', '.keystore', '.jks', '.p12', '.p8', '.mobileprovision', '.pem', '.env'}
ROUTER = re.compile(r'''(?:^|[\s/'"])remote-dev(?:$|[\s'"])''')


def inside(path, root):
    return path == root or root in path.parents


def charged_sizes(paths):
    """Count every hard-link name, so the cap also holds for Finder's apparent size."""
    paths = [Path(p) for p in paths if os.path.lexists(p)]
    if not paths:
        return {}
    def measure(options):
        sizes = {path: 0 for path in paths}
        # Compiler temp roots can contain thousands of entries; stay below ARG_MAX.
        for start in range(0, len(paths), 128):
            batch = paths[start:start + 128]
            result = subprocess.run(['du', *options, *map(str, batch)],
                                    capture_output=True, text=True)
            if result.returncode:
                # Temp files can disappear while another process finishes its work.
                if all('No such file or directory' in line for line in result.stderr.splitlines()):
                    batch = [path for path in batch if os.path.lexists(path)]
                    result = subprocess.run(['du', *options, *map(str, batch)],
                                            capture_output=True, text=True) if batch else None
                if result and result.returncode:
                    raise OSError('Cannot measure managed storage: ' + result.stderr.strip()[:300])
            if result:
                for line in result.stdout.splitlines():
                    count, name = line.split('\t', 1)
                    path = Path(name)
                    sizes[path] = max(sizes.get(path, 0), int(count) * 1024)
        return sizes

    with ThreadPoolExecutor(max_workers=2) as scans:
        apparent, allocated = scans.map(measure, (['-A', '-l', '-sk'], ['-l', '-sk']))
    return {path: max(apparent.get(path, 0), allocated.get(path, 0)) for path in apparent.keys() | allocated.keys()}


def charged_bytes(paths):
    return sum(charged_sizes(paths).values())


def open_paths():
    result = subprocess.run(['lsof', '-nP', '-u', str(os.getuid()), '-F', 'fn'],
                            capture_output=True, text=True, timeout=60)
    if result.returncode not in (0, 1) or not result.stdout:
        raise OSError('Cannot establish which temporary files are in use')
    files, working = set(), set()
    descriptor = None
    for line in result.stdout.splitlines():
        if line.startswith('f'):
            descriptor = line[1:]
        elif line.startswith('n/'):
            value = line[1:].removesuffix(' (deleted)')
            path = Path(os.path.realpath(value))
            (working if descriptor == 'cwd' else files).add(path)
    return files, working


def in_use(path, files, working, roots):
    path = Path(os.path.realpath(path))
    if any(inside(active, path) for active in files):
        return True
    for active in working:
        if any(inside(active, root) and active != root for root in roots):
            if inside(active, path) or inside(path, active):
                return True
    return False


def use_index(files, working, roots):
    file_parents = {parent for path in files for parent in (path, *path.parents)}
    cwds = {path for path in working if any(inside(path, root) and path != root for root in roots)}
    cwd_parents = {parent for path in cwds for parent in (path, *path.parents)}
    return file_parents, cwds, cwd_parents


def indexed_in_use(path, index):
    path = Path(os.path.realpath(path))
    file_parents, cwds, cwd_parents = index
    return (path in file_parents or path in cwd_parents
            or any(parent in cwds for parent in (path, *path.parents)))


def temp_roots(root=ROOT):
    roots = []
    for base in [root, *(p for p in root.iterdir() if p.is_dir() and not p.is_symlink()
                           and not p.name.startswith('.'))]:
        for name in ('tmp', 'temp', '.tmp'):
            path = base / name
            if path.is_dir() and not path.is_symlink() and not any(inside(path, r) for r in roots):
                roots.append(path)
    if root.resolve() == ROOT:
        for path in (Path.home() / 'tmp', Path.home() / '.codex/tmp', Path.home() / '.claude/tmp'):
            if path.is_dir() and not path.is_symlink():
                roots.append(path.resolve())
    return roots


def registered_worktrees(root=ROOT, state=STATE):
    registry = state / 'worktrees'
    if not registry.is_dir():
        return []
    config = json.loads((state / 'config.json').read_text())
    projects = {(root / name).resolve() for name in config['projects']}
    result = []
    for file in sorted(registry.glob('*.json')):
        if file.is_symlink() or not re.fullmatch(r'[a-f0-9]{64}', file.stem):
            continue
        try:
            value = json.loads(file.read_text())
            roots = [(Path(entry['source']).resolve(), Path(entry['path']).resolve()) for entry in value['roots']]
            if not 1 <= len(roots) <= 16 or len({p.name for p, _ in roots}) != len(roots):
                continue
            if len({p.parent for _, p in roots}) != 1:
                continue
            for source, checkout in roots:
                if source not in projects or checkout.name != source.name or checkout == source or not (checkout / '.git').is_file():
                    raise ValueError('Invalid worktree root')
                def common(directory):
                    output = subprocess.run(['git', '-C', str(directory), 'rev-parse', '--path-format=absolute', '--git-common-dir'],
                                            capture_output=True, text=True, check=True, timeout=10).stdout.strip()
                    return Path(output).resolve()
                if common(source) != common(checkout):
                    raise ValueError('Worktree belongs to another repository')
            result.append((file.stem, roots))
        except (OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError):
            continue
    return result


def managed_temps(root=ROOT, system_roots=None, state=None):
    def owned(path):
        try:
            return path.lstat().st_uid == os.getuid()
        except FileNotFoundError:
            # Another process can finish and remove its temporary entry during the scan.
            return False

    state = state or state_root(root)
    workspace = temp_roots(root)
    # Relocating the workspace's durable state does not exempt its old temporary
    # containers and moved project temporary folders from the combined budget.
    if state.parent.resolve() != Path(root).resolve():
        workspace = list(dict.fromkeys([*workspace, *temp_roots(state.parent)]))
    entries = []
    for base in workspace:
        entries.extend(p for p in base.iterdir() if owned(p))
    for _, roots in registered_worktrees(root, state):
        for _, checkout in roots:
            if owned(checkout) and not any(inside(checkout, entry) for entry in entries):
                entries.append(checkout)
                workspace.append(checkout)
    system = system_roots if system_roots is not None else [
        Path('/private/tmp'), Path(tempfile.gettempdir()).resolve(),
        Path(os.environ.get('TMPDIR', tempfile.gettempdir())).resolve()]
    for base in dict.fromkeys(system):
        if not base.is_dir():
            continue
        for path in base.iterdir():
            if path.name.lower().startswith(TEMP_PREFIXES) and owned(path):
                entries.append(path)
    return list(dict.fromkeys(entries)), workspace, list(dict.fromkeys(system))


def protected_tree(path):
    """Scratch Git checkouts and credentials survive eviction of their generated caches."""
    if protected_name(path):
        return True
    if path.is_symlink() or not path.is_dir():
        return False
    for directory, dirs, files in os.walk(path, followlinks=False):
        if '.git' in dirs or '.git' in files or ('HEAD' in files and {'objects', 'refs'} <= set(dirs)):
            return True
        if any(protected_name(Path(directory) / name) for name in dirs + files):
            return True
        dirs[:] = [name for name in dirs if name not in GENERATED
                   and not (Path(directory) / name).is_symlink()]
    return False


def protected_name(path):
    return (path.name in PROTECTED_NAMES or path.name.startswith('.env')
            or path.suffix.lower() in SECRET_SUFFIXES
            or (path.parent == Path.home() / '.codex/tmp' and path.name in {'arg0', 'path'}))


def newest_change(path):
    stat = path.lstat()
    latest = max(stat.st_mtime, stat.st_ctime)
    if path.is_dir() and not path.is_symlink():
        for directory, dirs, files in os.walk(path, followlinks=False):
            dirs[:] = [name for name in dirs if not (Path(directory) / name).is_symlink()]
            for name in dirs + files:
                try:
                    stat = (Path(directory) / name).lstat()
                except FileNotFoundError:
                    # A compiler can remove a temporary child after os.walk lists it.
                    continue
                # Deleting another hard link changes ctime without changing these inputs.
                latest = max(latest, stat.st_mtime)
    return latest


def remove(path):
    if path.is_symlink() or not path.is_dir():
        path.unlink()
    else:
        shutil.rmtree(path)


def temp_candidates(entries):
    seen = set()
    def visit(path):
        if path in seen or protected_name(path):
            return
        seen.add(path)
        if path.name in GENERATED:
            yield path
            return
        if path.is_dir() and not path.is_symlink() and protected_tree(path):
            if (os.path.lexists(path / '.git') or
                    ((path / 'HEAD').is_file() and (path / 'objects').is_dir() and (path / 'refs').is_dir())):
                yield from generated_candidates(path)
                return
            for child in path.iterdir():
                if child.name == '.git':
                    continue
                if child.name in GENERATED:
                    yield child
                else:
                    # A temp container can hold both a Git checkout and unrelated old archives.
                    yield from visit(child)
        else:
            yield path
    for path in entries:
        yield from visit(path)


def generated_candidates(root):
    for directory, dirs, _ in os.walk(root, followlinks=False):
        for name in list(dirs):
            path = Path(directory) / name
            if name == '.git' or protected_name(path) or path.is_symlink():
                dirs.remove(name)
            elif name in GENERATED:
                dirs.remove(name)
                yield path


def orphaned_artifacts(state=STATE):
    """Archives whose receipt is gone: remote-dev never reuses them and no receipt can complete them."""
    parent = state / 'artifacts'
    if not parent.is_dir():
        return []
    return [path for path in sorted(parent.iterdir()) if path.is_dir() and not path.is_symlink()
            and not os.path.lexists(state / 'logs' / (path.name + '.json'))]


def quiet(path, idle):
    try:
        return not idle or time.time() - newest_change(path) >= idle
    except FileNotFoundError:
        return False


def orphan_report(state, idle, files, working):
    orphans = orphaned_artifacts(state)
    index = use_index(files, working, [state])
    sizes = charged_sizes(orphans)
    report = []
    for path in orphans:
        busy, settled = indexed_in_use(path, index), quiet(path, idle)
        report.append({'path': str(path), 'charged_bytes': sizes.get(path, 0), 'in_use': busy,
                       'quiet': settled, 'candidate': settled and not busy})
    return report


def process_table():
    """Start time and command line of every process, or None when they cannot be read."""
    result = subprocess.run(['/bin/ps', '-axww', '-o', 'pid=,lstart=,command='], capture_output=True,
                            text=True, env=dict(os.environ, LC_ALL='C'))
    if result.returncode or not result.stdout:
        return None
    table = {}
    for line in result.stdout.splitlines():
        fields = line.split(None, 6)
        if len(fields) >= 6 and fields[0].isdigit():
            table[int(fields[0])] = (' '.join(fields[1:6]), fields[6] if len(fields) > 6 else '')
    return table


def run_owner(pid=None):
    pid = os.getpid() if pid is None else pid
    result = subprocess.run(['/bin/ps', '-o', 'lstart=', '-p', str(pid)], capture_output=True, text=True,
                            env=dict(os.environ, LC_ALL='C'))
    return {'pid': pid, 'started': ' '.join(result.stdout.split()) or None}


def process_epoch(started):
    try:
        return time.mktime(time.strptime(started, '%a %b %d %H:%M:%S %Y'))
    except ValueError:
        return 0


def owner_alive(receipt, table):
    """A recorded owner still runs under its recorded start; an older receipt may belong to any router started before it."""
    owner = receipt.get('owner')
    if isinstance(owner, dict) and isinstance(owner.get('pid'), int):
        started = table.get(owner['pid'], (None, ''))[0]
        return started is not None and owner.get('started') in (None, started)
    try:
        written = datetime.datetime.fromisoformat(receipt['started']).timestamp()
    except (KeyError, TypeError, ValueError):
        return True
    return any(ROUTER.search(command) and process_epoch(started) <= written for started, command in table.values())


def abandoned_runs(state, idle, table):
    """Unfinished worker runs whose router exited; only `remote-dev resume` could still read their staged outputs."""
    logs = state / 'logs'
    if table is None or not logs.is_dir():
        return []
    runs = []
    for path in sorted(logs.glob('*.json')):
        receipt = remote_retention.read_json(path)
        if receipt.get('run_id') != path.stem or 'abandoned' in receipt or completed(receipt) or not quiet(path, idle):
            continue
        archive = state / 'artifacts' / path.stem
        if not owner_alive(receipt, table) and (not os.path.lexists(archive) or quiet(archive, idle)):
            runs.append((path, receipt))
    return runs


def abandoned_report(state, runs):
    archives = [state / 'artifacts' / path.stem for path, _ in runs]
    sizes = charged_sizes(archives)
    return [{'run': path.stem, 'artifacts': str(archive) if os.path.lexists(archive) else None,
             'charged_bytes': sizes.get(archive, 0), 'owner': receipt.get('owner')}
            for (path, receipt), archive in zip(runs, archives)]


def abandon(path, receipt):
    path.write_text(json.dumps(dict(receipt, abandoned=datetime.datetime.now().isoformat()), indent=2) + '\n')


def claim_run(state, run_id):
    """Make this process the owner of an unfinished run, unless its router still runs or its staged outputs expired."""
    path = state / 'logs' / (run_id + '.json')
    receipt = json.loads(path.read_text())
    owner = receipt.get('owner')
    if owner and not receipt.get('finished'):
        table = process_table()
        if table is None or owner_alive(receipt, table):
            raise ValueError(f'Run {run_id} still belongs to process {owner.get("pid")}; wait for it instead of resuming')
    archive = state / 'artifacts' / run_id
    staged = any(files for _, files in receipt.get('pending_publications', [])) or receipt.get('pending_xcode')
    if remote_retention.abandoned(receipt) and staged and (not archive.is_dir() or (archive / remote_retention.MARKER).exists()):
        raise ValueError(f'The staged outputs of run {run_id} expired after its router exited; build again')
    if remote_retention.abandoned(receipt):
        del receipt['abandoned']
    receipt['owner'] = run_owner()
    path.write_text(json.dumps(receipt, indent=2) + '\n')
    return receipt


def remote_candidates(state=STATE, idle=0, abandoned=()):
    """Evict rematerializable slots, old archives/reports, then unpinned source backups."""
    for slots in remote_retention.configured_slot_roots(state):
        for path in slots.iterdir() if slots.exists() else []:
            if path.is_dir() and not path.is_symlink() and (path / 'source-manifest.json').is_file():
                yield 0, path
    orphans = set(orphaned_artifacts(state))
    for name in ('artifacts', 'runs'):
        parent = state / name
        if not parent.exists():
            continue
        for path in parent.iterdir():
            if path.is_symlink() or not path.is_dir():
                continue
            if path in orphans:
                if quiet(path, idle):
                    yield 1, path
                continue
            receipt_path = (state / 'logs' / (path.name + '.json')) if name == 'artifacts' else path / 'receipt.json'
            receipt = remote_retention.read_json(receipt_path)
            if completed(receipt) or (name == 'artifacts' and path.name in abandoned):
                yield 1, path
    snapshots = state / 'snapshots'
    pending = set()
    runs = state / 'runs'
    if runs.exists():
        for run in runs.iterdir():
            receipt = remote_retention.read_json(run / 'receipt.json')
            if not completed(receipt):
                pending.add(receipt.get('snapshot'))
    copies = sorted((p for p in snapshots.iterdir() if re.fullmatch('[0-9a-f]{64}', p.name)
                     and p.name not in pending and not p.is_symlink() and (p / 'manifest.json').is_file()),
                    key=lambda p: p.stat().st_mtime) if snapshots.exists() else []
    for index, path in enumerate(copies):
        yield (2 if index < len(copies) - 1 else 4), path
    for path in state.iterdir():
        if path.name in PROTECTED_NAMES or path.name in {'slots', 'slots.noindex', 'snapshots', 'runs', 'artifacts', 'snapshot-pins'}:
            continue
        if path.is_dir() and not path.is_symlink():
            if path.name == 'logs':
                for log in path.iterdir():
                    receipt = remote_retention.read_json(log if log.suffix == '.json' else log.with_suffix('.json'))
                    if completed(receipt) or log.stem in abandoned:
                        yield 3, log
            elif re.search(r'20\d{6}', path.name) or path.name == 'plans':
                yield 3, path


def completed(receipt):
    return (bool(receipt.get('finished') or remote_retention.abandoned(receipt))
            or receipt.get('status') in {'cancelled', 'failed', 'succeeded'}) and not receipt.get('workerMayContinue')


@contextlib.contextmanager
def graph_activity(state=STATE):
    with (state / 'graph-activity.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_SH)
        yield


@contextlib.contextmanager
def snapshot_pin(state, identity):
    if not identity:
        yield
        return
    if not re.fullmatch('[0-9a-f]{64}', identity):
        raise ValueError('Invalid snapshot identity')
    pins = state / 'snapshot-pins'
    pins.mkdir(exist_ok=True)
    with (pins / (identity + '.lock')).open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_SH)
        yield


@contextlib.contextmanager
def eviction_pin(path):
    """Hold the exclusive counterpart of a requested snapshot's lifetime lease."""
    if path.parent.name != 'snapshots' or not re.fullmatch('[0-9a-f]{64}', path.name):
        yield True
        return
    pins = path.parent.parent / 'snapshot-pins'
    pins.mkdir(exist_ok=True)
    with (pins / (path.name + '.lock')).open('a') as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            yield False
            return
        yield True


@contextlib.contextmanager
def remote_idle(state, held_locks=(), wait=False):
    while True:
        with contextlib.ExitStack() as stack:
            blocked = False
            for name in ('workspace', 'local-gradle', 'local-slot-1', 'worker-gradle', 'worker-slot-1', 'worker-slot-2', 'capture', 'graph-activity'):
                if name in held_locks:
                    continue
                lock = stack.enter_context((state / (name + '.lock')).open('a'))
                try:
                    fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
                except BlockingIOError:
                    if not wait or name == 'graph-activity':
                        yield False
                        return
                    blocked = True
                    break
            if not blocked:
                yield True
                return
        time.sleep(1)


def disk_room(root, floor):
    return shutil.disk_usage(root).free - floor if floor else None


def enforce(paths, candidates, limit, target, files, working, roots, idle, dry_run=False, room=None):
    before = charged_bytes(paths)
    report = {'limit_bytes': limit, 'bytes_before': before, 'bytes_after': before, 'removed': []}
    goal = limit if room is None else max(0, min(limit, before + room))
    if goal < limit:
        target = target * goal // limit
        report['disk_bound_bytes'] = goal
    if before <= goal:
        report['within_limit'] = True
        return report
    remaining = before
    active_index = use_index(files, working, roots)
    dated = []
    for priority, path in candidates:
        try:
            dated.append((priority, path.lstat().st_mtime, path))
        except FileNotFoundError:
            continue
    candidates = [(priority, path) for priority, _, path in sorted(dated, key=lambda item: item[:2])]
    sizes = charged_sizes([path for _, path in candidates])
    for priority, path in candidates:
        if remaining <= target:
            break
        if not os.path.lexists(path) or indexed_in_use(path, active_index):
            continue
        if path.parent.name == 'logs' and os.path.lexists(path.parent.parent / 'artifacts' / path.stem):
            continue
        if not quiet(path, idle):
            continue
        with eviction_pin(path) as available:
            if not available:
                continue
            size = sizes.get(path, 0)
            if not dry_run:
                try:
                    remove(path)
                except FileNotFoundError:
                    continue
                except PermissionError as error:
                    report.setdefault('skipped', []).append({'path': str(path), 'reason': str(error)})
                    continue
            report['removed'].append({'path': str(path), 'charged_bytes': size})
            remaining -= size
    report['bytes_after'] = remaining if dry_run else charged_bytes(paths)
    report['within_limit'] = report['bytes_after'] <= limit
    if not report['within_limit']:
        report['reason'] = 'Active/recent files, source checkouts or protected data require space; no unsafe deletion'
    return report


def cleanup(root=ROOT, state=STATE, config=None, dry_run=False, wait=False, held_locks=()):
    try:
        remote_retention.require_admission(state)
    except RuntimeError:
        return {'skipped': 'reviewed maintenance window'}
    config = config or json.loads((TOOLS / 'storage-limits.json').read_text())
    remote_limit = int(config['remote_limit_bytes'])
    tmp_limit = int(config['tmp_limit_bytes'])
    fraction = float(config.get('cleanup_target_fraction', 0.8))
    idle = int(config.get('minimum_idle_seconds', 900))
    floor = int(config.get('minimum_free_bytes', 0))
    if min(remote_limit, tmp_limit) <= 0 or not 0 < fraction <= 1 or idle < 0 or floor < 0:
        raise ValueError('Invalid storage budget')
    state.mkdir(parents=True, exist_ok=True)
    with (state / 'storage.lock').open('a') as mutex:
        try:
            fcntl.flock(mutex, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            if not wait:
                return {'skipped': 'storage cleanup already running'}
            fcntl.flock(mutex, fcntl.LOCK_EX)
        files, working = open_paths()
        entries, workspace, system = managed_temps(root, state=state)
        report = {'started_epoch': time.time(), 'dry_run': dry_run,
                  'free_bytes_before': shutil.disk_usage(root).free, 'minimum_free_bytes': floor,
                  'tmp_workspace_roots': list(map(str, workspace)),
                  'tmp_system_roots': list(map(str, system))}
        # Temporary cleanup can proceed independently of a worker build.
        report['tmp'] = enforce(entries, ((0, p) for p in temp_candidates(entries)),
                                tmp_limit, int(tmp_limit * fraction), files, working,
                                workspace + system, idle, dry_run, disk_room(root, floor))
        with remote_idle(state, held_locks, wait) as available:
            if available:
                orphans = orphan_report(state, idle, files, working)
                runs = abandoned_runs(state, idle, process_table())
                abandoned = abandoned_report(state, runs)
                if not dry_run:
                    for path, receipt in runs:
                        abandon(path, receipt)
                report['remote'] = enforce([state], remote_candidates(state, idle, {path.stem for path, _ in runs}),
                                           remote_limit, int(remote_limit * fraction), files, working, [state],
                                           0, dry_run, disk_room(root, floor))
                report['remote']['orphans'] = orphans
                report['remote']['abandoned'] = abandoned
            else:
                size = charged_bytes([state])
                report['remote'] = {'skipped': 'active build or graph', 'limit_bytes': remote_limit,
                                    'bytes_after': size, 'within_limit': size <= remote_limit}
        report['free_bytes_after'] = shutil.disk_usage(root).free
        report['finished_epoch'] = time.time()
        if not dry_run:
            path = state / 'storage-budget.json'
            path.write_text(json.dumps(report, indent=2) + '\n')
        return report


def automatic(helper, held_locks=(), strict=True):
    try:
        result = cleanup(root=helper.ROOT if helper.ROOT == ROOT else ROOT,
                         state=helper.STATE, held_locks=held_locks)
        if strict and result.get('skipped'):
            entries, _, _ = managed_temps(ROOT, state=helper.STATE)
            config = json.loads((TOOLS / 'storage-limits.json').read_text())
            result['remote'] = {'within_limit': charged_bytes([helper.STATE]) <= config['remote_limit_bytes']}
            result['tmp'] = {'within_limit': charged_bytes(entries) <= config['tmp_limit_bytes']}
        for name in ('remote', 'tmp'):
            section = result.get(name, {})
            if strict and section.get('within_limit') is False:
                raise ValueError(f'{name} storage exceeds its budget; close active work and run {TOOLS / "dev-storage"} cleanup')
        return result
    except (OSError, subprocess.SubprocessError) as error:
        if strict:
            raise ValueError(f'Cannot check storage budgets before starting this build: {error}') from error
        print(f'Storage cleanup deferred: {error}', flush=True)
        return {'error': str(error)}

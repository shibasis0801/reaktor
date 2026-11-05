import ast
import fcntl
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import threading
import time
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import workspace_storage as storage


def file_bytes(paths):
    total = 0
    for path in map(Path, paths):
        if not os.path.lexists(path):
            continue
        if path.is_symlink():
            total += path.lstat().st_size
        elif path.is_file():
            total += path.stat().st_size
        else:
            for directory, dirs, files in os.walk(path, followlinks=False):
                for name in dirs + files:
                    child = Path(directory) / name
                    if child.is_symlink():
                        total += child.lstat().st_size
                    elif child.is_file():
                        total += child.stat().st_size
    return total


class StorageTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name).resolve()
        self.state = self.root / '.remote-dev'
        self.state.mkdir()
        self.tmp = self.root / 'tmp'
        self.tmp.mkdir()

    def tearDown(self):
        self.temp.cleanup()

    def make(self, relative, size=100):
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(b'x' * size)
        return path

    def exited_owner(self):
        process = subprocess.Popen(['/bin/sleep', '30'])
        try:
            return storage.run_owner(process.pid)
        finally:
            process.kill()
            process.wait()

    def pending_run(self, name, owner):
        self.make('.remote-dev/artifacts/' + name + '/bestbuds/build/outputs/app.apk', 5000)
        receipt = {'run_id': name, 'exit_code': 0, 'owner': owner,
                   'pending_publications': [['bestbuds', ['build/outputs/app.apk']]]}
        (self.state / 'logs').mkdir(exist_ok=True)
        (self.state / 'logs' / (name + '.json')).write_text(json.dumps(receipt))
        return self.state / 'artifacts' / name

    def receipt(self, name):
        return json.loads((self.state / 'logs' / (name + '.json')).read_text())

    def enforce(self, paths, candidates, limit=200, files=(), working=(), idle=0, dry_run=False, room=None):
        with patch.object(storage, 'charged_bytes', file_bytes), \
                patch.object(storage, 'charged_sizes', lambda paths: {p: file_bytes([p]) for p in paths}):
            return storage.enforce(paths, candidates, limit, int(limit * 0.8),
                                   set(files), set(working), [self.state, self.tmp], idle, dry_run, room)

    def test_reviewed_secret_names_and_preserved_candidate_never_become_candidates(self):
        names = ['config.toml', 'kubeconfig', 'id_rsa', 'id_ed25519', 'credentials',
                 '.netrc', '.npmrc', '.pgpass', 'client.pem', 'service.env', '.env.production',
                 'e2ee-candidate-20261029']
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder).resolve()
            for name in names:
                path = root / name
                if name == 'e2ee-candidate-20261029':
                    (path / 'build').mkdir(parents=True)
                    (path / 'build' / 'output').write_text('generated')
                else:
                    path.write_text('protected')
                self.assertTrue(storage.protected_name(path))
                self.assertEqual([], list(storage.temp_candidates([path])))

    def test_protected_directories_keep_generated_descendants_and_remain_charged(self):
        container = self.tmp / 'container'
        protected = self.make('tmp/container/config/build/output', 5000)
        sibling = self.make('tmp/container/scratch/value', 500).parent
        self.assertTrue(storage.protected_tree(container))
        candidates = list(storage.temp_candidates([container]))
        self.assertEqual([sibling], candidates)
        report = self.enforce([container], ((0, p) for p in candidates), limit=1, dry_run=True)
        self.assertEqual(5500, report['bytes_before'])
        self.assertEqual(5000, report['bytes_after'])
        self.assertFalse(report['within_limit'])
        self.assertTrue(protected.exists())
        self.assertTrue(sibling.exists())

    def test_git_generated_discovery_stops_at_protected_directory_names(self):
        container = self.tmp / 'checkout'
        self.make('tmp/checkout/.git', 1)
        kept = self.make('tmp/checkout/credentials/build/output', 5000)
        allowed = self.make('tmp/checkout/src/build/output', 500).parent
        self.assertEqual([allowed], list(storage.temp_candidates([container])))
        self.assertTrue(kept.exists())

    def test_container_expires_archives_but_keeps_scratch_git_source_and_credentials(self):
        container = self.tmp / 'task'
        source = self.make('tmp/task/checkout/source.kt')
        self.make('tmp/task/checkout/.git', 1)  # Broken worktree Git files also protect source.
        cache = self.make('tmp/task/checkout/build/app.jar', 500).parent
        archive = self.make('tmp/task/old.app/binary', 500).parent
        secret = self.make('tmp/task/.env.local', 1)
        report = self.enforce([container], ((0, p) for p in storage.temp_candidates([container])))
        self.assertTrue(report['within_limit'])
        self.assertTrue(source.exists())
        self.assertTrue(secret.exists())
        self.assertFalse(cache.exists())
        self.assertFalse(archive.exists())

    def test_registered_agent_worktree_counts_as_temp_and_only_generated_outputs_expire(self):
        source = self.root / 'bestbuds'
        source.mkdir()
        subprocess.run(['git', 'init', '-q', str(source)], check=True)
        subprocess.run(['git', '-C', str(source), '-c', 'user.name=Test', '-c', 'user.email=test@localhost',
                        'commit', '--allow-empty', '-qm', 'base'], check=True)
        checkout = self.root / 'agents/pair/bestbuds'
        subprocess.run(['git', '-C', str(source), 'worktree', 'add', '-q', '--detach', str(checkout)], check=True)
        registry = self.state / 'worktrees'
        registry.mkdir()
        (self.state / 'config.json').write_text(json.dumps({'projects': ['bestbuds']}))
        (registry / ('a' * 64 + '.json')).write_text(json.dumps({'roots': [{'source': str(source), 'path': str(checkout)}]}))
        authored = self.make('agents/pair/bestbuds/source.kt', 1)
        generated = self.make('agents/pair/bestbuds/build/output.jar', 5000)
        entries, workspace, _ = storage.managed_temps(self.root, system_roots=[])
        self.assertIn(checkout, entries)
        self.assertIn(checkout, workspace)
        self.assertTrue(self.enforce(entries, ((0, p) for p in storage.temp_candidates(entries)), limit=1000)['within_limit'])
        self.assertTrue(authored.exists())
        self.assertTrue((checkout / '.git').exists())
        self.assertFalse(generated.exists())

    def test_relocated_temporary_containers_and_project_temp_remain_charged(self):
        workspace = self.root / 'dev'
        workspace.mkdir()
        state = self.root / 'other/.remote-dev'
        state.mkdir(parents=True)
        moved_cache = self.make('other/tmp/cache/output', 500).parent
        moved_project_cache = self.make('other/gymbuddy/tmp/cache/output', 500).parent
        source = self.make('other/tmp/checkout/source.kt', 100)
        self.make('other/tmp/checkout/.git', 1)
        generated = self.make('other/tmp/checkout/build/output', 500).parent
        entries, roots, _ = storage.managed_temps(workspace, state=state, system_roots=[])
        self.assertIn(state.parent / 'tmp', roots)
        self.assertIn(state.parent / 'gymbuddy/tmp', roots)
        report = self.enforce(entries, ((0, p) for p in storage.temp_candidates(entries)), limit=200)
        self.assertEqual(1601, report['bytes_before'])
        self.assertTrue(report['within_limit'])
        self.assertFalse(moved_cache.exists())
        self.assertFalse(moved_project_cache.exists())
        self.assertFalse(generated.exists())
        self.assertTrue(source.exists())

    def test_open_file_and_entire_working_checkout_are_protected(self):
        open_file = self.make('tmp/open/output')
        active = self.make('tmp/checkout/build/output')
        old = self.make('tmp/old/output', 500)
        candidates = [(0, self.tmp / name) for name in ('open', 'checkout', 'old')]
        report = self.enforce([self.tmp], candidates, limit=150, files=[open_file], working=[active.parent.parent])
        self.assertFalse(report['within_limit'])
        self.assertTrue(open_file.exists())
        self.assertTrue(active.exists())
        self.assertFalse(old.exists())

    def test_recent_tmp_is_not_deleted(self):
        path = self.make('tmp/recent/output', 500).parent
        report = self.enforce([self.tmp], [(0, path)], idle=900)
        self.assertFalse(report['within_limit'])
        self.assertTrue(path.exists())

    def test_disappearing_child_does_not_abort_cleanup_age_scan(self):
        cache = self.make('tmp/cache/output', 500).parent
        vanished = self.make('tmp/cache/kotlin-daemon.log.lck', 100)
        walk = os.walk

        def finishing_walk(path, *args, **kwargs):
            for directory, dirs, files in walk(path, *args, **kwargs):
                if Path(directory) == cache and vanished.name in files:
                    vanished.unlink()
                yield directory, dirs, files

        with patch.object(storage.os, 'walk', finishing_walk), \
                patch.object(storage.time, 'time', return_value=time.time() + 3600):
            report = self.enforce([self.tmp], [(0, cache)], limit=200, idle=900)
        self.assertTrue(report['within_limit'])
        self.assertFalse(cache.exists())
        self.assertEqual([str(cache)], [entry['path'] for entry in report['removed']])

    def test_disappearing_candidate_does_not_abort_cleanup_ordering(self):
        vanished = self.make('tmp/vanished/output', 100).parent
        expired = self.make('tmp/expired/output', 500).parent

        def finishing_candidates():
            shutil.rmtree(vanished)
            yield 0, vanished
            yield 0, expired

        report = self.enforce([self.tmp], finishing_candidates(), limit=200)
        self.assertTrue(report['within_limit'])
        self.assertFalse(expired.exists())
        self.assertEqual([str(expired)], [entry['path'] for entry in report['removed']])

    def test_inaccessible_candidate_does_not_stop_cleanup_or_count_as_removed(self):
        blocked = self.make('tmp/blocked/output', 300).parent
        old = self.make('tmp/old/output', 500).parent
        remove = storage.remove
        def remove_available(path):
            if path == blocked:
                raise PermissionError('inaccessible candidate')
            remove(path)
        with patch.object(storage, 'remove', remove_available):
            report = self.enforce([self.tmp], [(0, blocked), (0, old)], limit=400)
        self.assertTrue(blocked.exists())
        self.assertFalse(old.exists())
        self.assertEqual(300, report['bytes_after'])
        self.assertEqual([str(old)], [entry['path'] for entry in report['removed']])
        self.assertEqual(str(blocked), report['skipped'][0]['path'])
        self.assertTrue(report['within_limit'])

    def test_low_disk_shrinks_the_budget_without_failing_admission(self):
        runs = [self.make(f'tmp/run-{index}/output').parent for index in range(4)]
        roomy = self.enforce([self.tmp], [(0, path) for path in runs], limit=1000, room=5000)
        self.assertNotIn('disk_bound_bytes', roomy)
        self.assertTrue(all(path.exists() for path in runs))
        tight = self.enforce([self.tmp], [(0, path) for path in runs], limit=1000, room=-150)
        self.assertEqual(tight['disk_bound_bytes'], 250)
        self.assertEqual(tight['bytes_after'], 200)
        self.assertTrue(tight['within_limit'])
        self.assertEqual(sum(path.exists() for path in runs), 2)

    def test_bare_scratch_git_is_preserved_and_swift_dependency_cache_expires_whole(self):
        bare = self.tmp / 'scratch'
        self.make('tmp/scratch/HEAD', 1)
        self.make('tmp/scratch/objects/pack/source', 1)
        (bare / 'refs').mkdir()
        cache = self.make('tmp/SourcePackages/repositories/dependency/objects/pack/file', 500).parents[4]
        self.make('tmp/SourcePackages/repositories/dependency/config', 1)
        candidates = list(storage.temp_candidates([bare, cache]))
        self.assertNotIn(bare, candidates)
        self.assertEqual([cache], candidates)
        self.enforce([bare, cache], [(0, p) for p in candidates])
        self.assertTrue((bare / 'objects/pack/source').exists())
        self.assertFalse(cache.exists())

    def test_pending_recovery_keeps_its_snapshot(self):
        identity = 'd' * 64
        snapshot = self.make('.remote-dev/snapshots/' + identity + '/manifest.json', 500).parent
        receipt = self.state / 'runs/pending/receipt.json'
        receipt.parent.mkdir(parents=True)
        receipt.write_text(json.dumps({'snapshot': identity, 'status': 'running'}))
        self.assertNotIn(snapshot, [p for _, p in storage.remote_candidates(self.state)])

    def test_dry_run_and_symlink_target_survive(self):
        outside = self.make('source/keep', 500)
        link = self.tmp / 'cache'
        link.symlink_to(outside.parent, target_is_directory=True)
        report = self.enforce([self.tmp], [(0, link)], limit=1, dry_run=True)
        self.assertTrue(report['within_limit'])
        self.assertTrue(link.is_symlink())
        self.enforce([self.tmp], [(0, link)], limit=1)
        self.assertFalse(link.is_symlink())
        self.assertTrue(outside.exists())

    def test_pending_archives_are_excluded_and_old_snapshots_expire_before_newest(self):
        for name in ('old', 'new'):
            self.make('.remote-dev/artifacts/' + name + '/output', 300)
        logs = self.state / 'logs'
        logs.mkdir()
        (logs / 'old.json').write_text(json.dumps({'finished': 'today'}))
        (logs / 'new.json').write_text(json.dumps({'status': 'running'}))
        first = self.make('.remote-dev/snapshots/' + 'a' * 64 + '/manifest.json', 300).parent
        newest = self.make('.remote-dev/snapshots/' + 'b' * 64 + '/manifest.json', 300).parent
        os.utime(first, (1, 1))
        candidates = list(storage.remote_candidates(self.state))
        self.assertNotIn(self.state / 'artifacts/new', [p for _, p in candidates])
        report = self.enforce([self.state], candidates, limit=800)
        self.assertTrue(report['within_limit'])
        self.assertFalse((self.state / 'artifacts/old').exists())
        self.assertFalse(first.exists())
        self.assertTrue(newest.exists())

    def test_requested_snapshot_pin_prevents_eviction(self):
        identity = 'c' * 64
        snapshot = self.make('.remote-dev/snapshots/' + identity + '/manifest.json', 500).parent
        with storage.snapshot_pin(self.state, identity):
            report = self.enforce([self.state], [(0, snapshot)])
            self.assertFalse(report['within_limit'])
        self.assertTrue(snapshot.exists())
        self.enforce([self.state], [(0, snapshot)])
        self.assertFalse(snapshot.exists())

    def test_open_archive_retains_receipt_until_payload_eviction(self):
        payload = self.make('.remote-dev/artifacts/completed/output', 500)
        receipt = self.state / 'logs/completed.json'
        receipt.parent.mkdir()
        receipt.write_text(json.dumps({'finished': 'today'}))
        log = self.make('.remote-dev/logs/completed.log', 100)
        candidates = list(storage.remote_candidates(self.state))
        report = self.enforce([self.state], candidates, limit=200, files=[payload])
        self.assertFalse(report['within_limit'])
        self.assertTrue(receipt.exists())
        self.assertTrue(log.exists())
        report = self.enforce([self.state], storage.remote_candidates(self.state), limit=200)
        self.assertTrue(report['within_limit'])
        self.assertFalse(payload.exists())
        candidates = [p for _, p in storage.remote_candidates(self.state)]
        self.assertIn(receipt, candidates)
        self.assertIn(log, candidates)

    def test_receiptless_archive_expires_once_quiet_and_unused(self):
        payload = self.make('.remote-dev/artifacts/orphan/output', 500)
        archive = payload.parent
        self.assertEqual([archive], storage.orphaned_artifacts(self.state))
        self.assertNotIn(archive, [p for _, p in storage.remote_candidates(self.state, idle=900)])
        with patch.object(storage.time, 'time', return_value=time.time() + 3600):
            self.assertIn((1, archive), list(storage.remote_candidates(self.state, idle=900)))
            report = self.enforce([self.state], storage.remote_candidates(self.state, idle=900), limit=200, files=[payload])
            self.assertFalse(report['within_limit'])
            self.assertTrue(payload.exists())
            report = self.enforce([self.state], storage.remote_candidates(self.state, idle=900), limit=200)
        self.assertTrue(report['within_limit'])
        self.assertFalse(archive.exists())

    def test_cleanup_reports_receiptless_archives_in_a_dry_run(self):
        orphan = self.make('.remote-dev/artifacts/orphan/output', 500).parent
        held = self.make('.remote-dev/artifacts/held/output', 500)
        kept = self.make('.remote-dev/artifacts/kept/output', 500).parent
        (self.state / 'logs').mkdir()
        (self.state / 'logs/kept.json').write_text(json.dumps({'finished': 'today'}))
        config = {'remote_limit_bytes': 100, 'tmp_limit_bytes': 10 ** 9, 'minimum_idle_seconds': 900}
        with patch.object(storage, 'open_paths', return_value=({held}, set())), \
                patch.object(storage, 'managed_temps', return_value=([], [], [])), \
                patch.object(storage.time, 'time', return_value=time.time() + 3600):
            report = storage.cleanup(self.root, self.state, config, dry_run=True)
        orphans = {entry['path']: entry for entry in report['remote']['orphans']}
        self.assertEqual({str(orphan), str(held.parent)}, set(orphans))
        self.assertTrue(orphans[str(orphan)]['candidate'])
        self.assertGreater(orphans[str(orphan)]['charged_bytes'], 0)
        self.assertEqual((True, True, False),
                         tuple(orphans[str(held.parent)][key] for key in ('in_use', 'quiet', 'candidate')))
        removed = [entry['path'] for entry in report['remote']['removed']]
        self.assertIn(str(orphan), removed)
        self.assertNotIn(str(held.parent), removed)
        self.assertTrue(orphan.exists())
        self.assertTrue(kept.exists())

    def test_a_killed_runs_staged_outputs_expire_while_a_run_waiting_to_publish_is_kept(self):
        killed = self.pending_run('killed', self.exited_owner())
        waiting = self.pending_run('waiting', storage.run_owner())
        self.assertEqual([], storage.abandoned_runs(self.state, 900, storage.process_table()))
        with patch.object(storage.time, 'time', return_value=time.time() + 3600):
            runs = storage.abandoned_runs(self.state, 900, storage.process_table())
            self.assertEqual(['killed'], [path.stem for path, _ in runs])
            for path, receipt in runs:
                storage.abandon(path, receipt)
            report = self.enforce([self.state], storage.remote_candidates(self.state, 900), limit=8000)
        self.assertTrue(report['within_limit'])
        self.assertEqual([str(killed)], [entry['path'] for entry in report['removed']])
        self.assertTrue((waiting / 'bestbuds/build/outputs/app.apk').exists())
        self.assertIn('abandoned', self.receipt('killed'))
        self.assertNotIn('abandoned', self.receipt('waiting'))

    def test_a_note_in_the_abandoned_field_is_kept_and_never_read_as_the_mark(self):
        noted = self.pending_run('noted', self.exited_owner())
        note = 'Benchmark inputs retained; stopped pending local publication'
        (self.state / 'logs/noted.json').write_text(json.dumps(dict(self.receipt('noted'), abandoned=note)))
        with patch.object(storage.time, 'time', return_value=time.time() + 3600):
            self.assertEqual([], storage.abandoned_runs(self.state, 900, storage.process_table()))
            report = self.enforce([self.state], storage.remote_candidates(self.state, 900), limit=1000)
        self.assertEqual([], report['removed'])
        self.assertTrue((noted / 'bestbuds/build/outputs/app.apk').exists())
        self.assertEqual(note, self.receipt('noted')['abandoned'])
        self.assertFalse(storage.completed(self.receipt('noted')))

    def test_a_receipt_without_an_owner_waits_for_every_router_started_before_it(self):
        written = time.time() - 7200
        receipt = {'started': time.strftime('%Y-%m-%dT%H:%M:%S', time.localtime(written))}
        router = '/usr/bin/python3 /Users/ovd/dev/tools/remote-dev gradle --project-dir /Users/ovd/dev/bestbuds'
        def started(offset):
            return time.strftime('%a %b %d %H:%M:%S %Y', time.localtime(written + offset))
        self.assertTrue(storage.owner_alive(receipt, {1: (started(-60), router)}))
        self.assertFalse(storage.owner_alive(receipt, {1: (started(60), router)}))
        self.assertFalse(storage.owner_alive(receipt, {1: (started(-60), 'tail -f /Users/ovd/dev/.remote-dev/logs/run.log')}))
        self.assertTrue(storage.owner_alive({}, {}))

    def test_cleanup_reports_and_marks_a_killed_run_but_never_a_live_one(self):
        killed = self.pending_run('killed', self.exited_owner())
        waiting = self.pending_run('waiting', storage.run_owner())
        config = {'remote_limit_bytes': 100, 'tmp_limit_bytes': 10 ** 9, 'minimum_idle_seconds': 900}
        with patch.object(storage, 'open_paths', return_value=(set(), set())), \
                patch.object(storage, 'managed_temps', return_value=([], [], [])), \
                patch.object(storage.time, 'time', return_value=time.time() + 3600):
            report = storage.cleanup(self.root, self.state, config, dry_run=True)
            [entry] = report['remote']['abandoned']
            self.assertEqual(('killed', str(killed)), (entry['run'], entry['artifacts']))
            self.assertGreater(entry['charged_bytes'], 0)
            removed = [entry['path'] for entry in report['remote']['removed']]
            self.assertIn(str(killed), removed)
            self.assertNotIn(str(waiting), removed)
            self.assertNotIn('abandoned', self.receipt('killed'))
            report = storage.cleanup(self.root, self.state, dict(config, remote_limit_bytes=10 ** 9))
        self.assertEqual(['killed'], [entry['run'] for entry in report['remote']['abandoned']])
        self.assertEqual([], report['remote']['removed'])
        self.assertTrue(killed.exists())
        self.assertIn('abandoned', self.receipt('killed'))
        self.assertNotIn('abandoned', self.receipt('waiting'))

    def test_graph_and_host_leases_block_eviction_and_release_cleanly(self):
        with storage.graph_activity(self.state):
            with storage.remote_idle(self.state, wait=True) as available:
                self.assertFalse(available)
        with (self.state / 'worker-gradle.lock').open('a') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX)
            with storage.remote_idle(self.state) as available:
                self.assertFalse(available)
        with storage.remote_idle(self.state) as available:
            self.assertTrue(available)

    def test_waiting_for_worker_releases_local_lease_before_retry(self):
        flock = fcntl.flock
        with (self.state / 'worker-gradle.lock').open('a') as worker:
            flock(worker, fcntl.LOCK_EX)

            def nonblocking_only(lock, operation):
                self.assertTrue(operation & fcntl.LOCK_NB)
                return flock(lock, operation)

            def publish_worker_outputs(_):
                with (self.state / 'local-gradle.lock').open('a') as local:
                    flock(local, fcntl.LOCK_EX | fcntl.LOCK_NB)
                flock(worker, fcntl.LOCK_UN)

            with patch.object(storage.fcntl, 'flock', side_effect=nonblocking_only), \
                    patch.object(storage.time, 'sleep', side_effect=publish_worker_outputs) as retry:
                with storage.remote_idle(self.state, wait=True) as available:
                    self.assertTrue(available)
                retry.assert_called_once()

    def test_system_temp_scope_excludes_os_and_other_apps(self):
        system = self.root / 'system'
        system.mkdir()
        developer = self.make('system/included123/cache').parent
        app = self.make('system/com.apple.test/state').parent
        chrome = self.make('system/GoogleChrome/state').parent
        entries, _, _ = storage.managed_temps(self.root, [system])
        self.assertIn(developer, entries)
        self.assertNotIn(app, entries)
        self.assertNotIn(chrome, entries)

        lstat = Path.lstat
        vanished = {self.make('tmp/vanished'), self.make('system/tmp-vanished')}

        def disappear(path, *args, **kwargs):
            if path in vanished:
                path.unlink()
            return lstat(path, *args, **kwargs)

        with patch.object(Path, 'lstat', disappear):
            entries, _, _ = storage.managed_temps(self.root, [system])
        self.assertIn(developer, entries)
        self.assertTrue(vanished.isdisjoint(entries))

        def denied(path, *args, **kwargs):
            if path == developer:
                raise PermissionError('ownership unavailable')
            return lstat(path, *args, **kwargs)

        with patch.object(Path, 'lstat', denied), self.assertRaises(PermissionError):
            storage.managed_temps(self.root, [system])

    def test_apparent_accounting_counts_both_hardlink_names(self):
        first = self.make('tmp/output', 100000)
        os.link(first, self.tmp / 'copy')
        self.assertGreaterEqual(storage.charged_bytes([self.tmp]), 200000)

    def test_sparse_files_keep_apparent_charge_and_symlinks_do_not_charge_the_target(self):
        sparse = self.tmp / 'sparse'
        with sparse.open('wb') as output:
            output.truncate(20_000_000)
        outside = self.root / 'outside'
        with outside.open('wb') as output:
            output.truncate(60_000_000)
        (self.tmp / 'link').symlink_to(outside)
        charged = storage.charged_bytes([self.tmp])
        self.assertGreaterEqual(charged, 20_000_000)
        self.assertLess(charged, 21_000_000)

    def test_storage_measurements_overlap_and_keep_the_larger_charge_per_path(self):
        first = self.make('tmp/first')
        second = self.make('tmp/second')
        started = threading.Barrier(2)

        def measure(command, **kwargs):
            started.wait(timeout=5)
            sizes = (9, 2) if '-A' in command else (3, 8)
            return subprocess.CompletedProcess(command, 0,
                ''.join(f'{size}\t{path}\n' for size, path in zip(sizes, (first, second))), '')

        with patch.object(storage.subprocess, 'run', measure):
            self.assertEqual({first: 9 * 1024, second: 8 * 1024}, storage.charged_sizes([first, second]))

    def test_storage_measurement_preserves_batch_limit_and_disappearing_path_retry(self):
        paths = [self.make(f'tmp/entry-{i}') for i in range(130)]
        missing = paths[0]
        attempts = []
        lock = threading.Lock()

        def measure(command, **kwargs):
            batch = list(map(Path, command[command.index('-sk') + 1:]))
            self.assertLessEqual(len(batch), 128)
            with lock:
                attempts.append(batch)
                if missing in batch:
                    missing.unlink(missing_ok=True)
                    return subprocess.CompletedProcess(command, 1, '', 'No such file or directory\n')
            return subprocess.CompletedProcess(command, 0, ''.join(f'1\t{path}\n' for path in batch), '')

        with patch.object(storage.subprocess, 'run', measure):
            sizes = storage.charged_sizes(paths)
        self.assertEqual(0, sizes[missing])
        self.assertTrue(all(sizes[path] == 1024 for path in paths[1:]))
        self.assertEqual(6, len(attempts))

    def test_either_storage_measurement_failure_refuses_accounting(self):
        for failed_apparent in (True, False):
            def measure(command, **kwargs):
                if ('-A' in command) == failed_apparent:
                    return subprocess.CompletedProcess(command, 1, '', 'Permission denied')
                return subprocess.CompletedProcess(command, 0, f'1\t{self.tmp}\n', '')

            with self.subTest(apparent=failed_apparent), patch.object(storage.subprocess, 'run', measure):
                with self.assertRaisesRegex(OSError, 'Permission denied'):
                    storage.charged_sizes([self.tmp])

    def test_strict_admission_rejects_unmet_budget_or_unknown_measurement(self):
        helper = SimpleNamespace(ROOT=storage.ROOT, STATE=self.state)
        with patch.object(storage, 'cleanup', return_value={'remote': {'within_limit': False}}):
            with self.assertRaises(ValueError):
                storage.automatic(helper)
            storage.automatic(helper, strict=False)
        with patch.object(storage, 'cleanup', side_effect=OSError('measurement failed')):
            with self.assertRaises(ValueError):
                storage.automatic(helper)

    def test_real_cli_wrapper_cleans_after_failed_command(self):
        tree = ast.parse((storage.TOOLS / 'remote-dev').read_text())
        node = next(n for n in tree.body if isinstance(n, ast.FunctionDef) and n.name == 'main')
        module = ast.Module(body=[node], type_ignores=[])
        helper = SimpleNamespace(ROOT=storage.ROOT, STATE=self.state)
        namespace = {'sys': SimpleNamespace(argv=['remote-dev', 'build'], modules={'test': helper}),
                     '__name__': 'test', '_main': lambda: 7}
        exec(compile(module, 'remote-dev', 'exec'), namespace)
        with patch.object(storage, 'automatic') as cleanup:
            self.assertEqual(7, namespace['main']())
            cleanup.assert_called_once_with(helper, strict=False)


if __name__ == '__main__':
    unittest.main()

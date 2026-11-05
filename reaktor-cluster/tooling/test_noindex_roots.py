import ast
import contextlib
import fcntl
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import remote_build
import remote_retention
import workspace_storage


class NoindexRootsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name).resolve()
        self.state = self.root / '.remote-dev'
        self.state.mkdir()

    def tearDown(self):
        self.temp.cleanup()

    def test_default_preserves_the_existing_root(self):
        self.assertEqual((self.state / 'slots',), remote_retention.configured_slot_roots(self.state))

    def test_configured_root_is_first_and_interrupted_cutover_keeps_both(self):
        for name in ('slots', 'slots.noindex'):
            (self.state / name).mkdir()
        config = {'local_slots_root': str(self.state / 'slots.noindex')}
        self.assertEqual((self.state / 'slots.noindex', self.state / 'slots'), remote_retention.configured_slot_roots(self.state, config))

    def test_relative_unnormalized_and_outside_roots_are_refused(self):
        for value in ['slots.noindex', str(self.root / 'other'), str(self.state) + '/unused/../slots.noindex', str(self.state / 'slots.noindex') + '\n']:
            with self.subTest(value=value), self.assertRaises(ValueError):
                remote_retention.configured_slot_roots(self.state, {'local_slots_root': value})

    def test_symlink_root_and_ancestor_are_refused(self):
        target = self.root / 'target'
        target.mkdir()
        (self.state / 'slots.noindex').symlink_to(target, target_is_directory=True)
        with self.assertRaises(ValueError):
            remote_retention.configured_slot_roots(self.state, {'local_slots_root': str(self.state / 'slots.noindex')})
        alias = self.root / 'alias'
        alias.symlink_to(self.state, target_is_directory=True)
        with self.assertRaises(ValueError):
            remote_retention.configured_slot_roots(alias, {})

    def test_old_receipts_are_never_relabelled_to_new_roots(self):
        old = str(self.root / 'offload')
        new = old + '.noindex'
        receipt = {'remote_root': old}
        before = json.dumps(receipt, sort_keys=True)
        self.assertFalse(remote_retention.receipt_root_matches(receipt, new))
        self.assertEqual(before, json.dumps(receipt, sort_keys=True))
        self.assertTrue(remote_retention.receipt_root_matches(receipt, old))
        self.assertFalse(remote_retention.receipt_root_matches({}, new))
        self.assertTrue(remote_retention.receipt_root_matches({}, old))

    def test_new_and_old_slots_are_both_accounted_candidates(self):
        paths = []
        for name in ('slots', 'slots.noindex'):
            slot = self.state / name / 'worker'
            slot.mkdir(parents=True)
            (slot / 'source-manifest.json').write_text('{}')
            paths.append(slot)
        (self.state / 'config.json').write_text(json.dumps({'local_slots_root': str(self.state / 'slots.noindex')}))
        candidates = [path for _, path in workspace_storage.remote_candidates(self.state)]
        self.assertTrue(set(paths).issubset(candidates))
        self.assertEqual(workspace_storage.charged_bytes([self.state]), workspace_storage.charged_sizes([self.state])[self.state])

    def test_rename_refuses_existing_destination_without_mutation(self):
        source = self.root / 'old'
        destination = self.root / 'new.noindex'
        source.mkdir()
        destination.mkdir()
        with self.assertRaises(ValueError):
            remote_build.rename_preflight(source, destination)
        self.assertTrue(source.is_dir())
        self.assertTrue(destination.is_dir())

    def test_rename_refuses_cross_volume_without_mutation(self):
        source = self.root / 'old'
        source.mkdir()
        original = Path.stat
        def stat(path, *args, **kwargs):
            result = original(path, *args, **kwargs)
            if path == self.root:
                values = list(result)
                values[2] += 1
                return os.stat_result(values)
            return result
        with patch.object(Path, 'stat', stat), self.assertRaisesRegex(ValueError, 'same volume'):
            remote_build.rename_preflight(source, self.root / 'new.noindex')
        self.assertTrue(source.is_dir())

    def test_rename_preflight_records_inode_without_moving(self):
        source = self.root / 'old'
        source.mkdir()
        receipt = remote_build.rename_preflight(source, self.root / 'new.noindex')
        self.assertEqual(source.stat().st_ino, receipt['inode'])
        self.assertTrue(source.exists())
        self.assertFalse((self.root / 'new.noindex').exists())

    def test_gate_refuses_new_work_and_reopens_explicitly(self):
        remote_retention.require_admission(self.state)
        marker = self.state / 'maintenance.json'
        marker.write_text('{"active":true}')
        with self.assertRaises(RuntimeError):
            remote_retention.require_admission(self.state)
        marker.write_text('{"active":false}')
        remote_retention.require_admission(self.state)

    def test_publication_lease_is_not_blocked_by_the_admission_marker(self):
        (self.state / 'maintenance.json').write_text('{"active":true}')
        with remote_build.local_lease(self.state):
            with self.assertRaises(BlockingIOError), (self.state / 'local-gradle.lock').open('a') as lock:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)

    def test_local_declared_execution_stays_inside_its_lease(self):
        tree = ast.parse(Path(__file__).with_name('remote-dev').read_text())
        route = next(n for n in tree.body if isinstance(n, ast.FunctionDef) and n.name == 'route_gradle')
        declared = next(n for n in route.body if isinstance(n, ast.If) and isinstance(n.test, ast.Name) and n.test.id == 'local_declared')
        lease = next(n for n in declared.body if isinstance(n, ast.With))
        self.assertIsInstance(lease.body[-1], ast.Return)

    def test_contended_ordered_acquisition_releases_every_partial_lease(self):
        first, second = self.state / 'first.lock', self.state / 'second.lock'
        with second.open('a') as current:
            fcntl.flock(current, fcntl.LOCK_EX | fcntl.LOCK_NB)
            with remote_build.try_leases([first, second]) as held:
                self.assertIsNone(held)
                with first.open('a') as released:
                    fcntl.flock(released, fcntl.LOCK_EX | fcntl.LOCK_NB)

    def test_python_flock_is_excluded_in_another_process(self):
        lock = self.state / 'worker-slot-2.lock'
        program = 'import fcntl,sys;f=open(sys.argv[1],"a");fcntl.flock(f,fcntl.LOCK_EX|fcntl.LOCK_NB)'
        with lock.open('a') as handle:
            fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
            result = subprocess.run([sys.executable, '-c', program, str(lock)], capture_output=True)
            self.assertNotEqual(0, result.returncode)
        result = subprocess.run([sys.executable, '-c', program, str(lock)], capture_output=True)
        self.assertEqual(0, result.returncode)

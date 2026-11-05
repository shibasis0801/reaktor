import importlib.util
import json
from pathlib import Path
from http.server import BaseHTTPRequestHandler, HTTPServer
import threading
import os
import tempfile
import time
import sys
import subprocess
import fcntl
from types import SimpleNamespace
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('atlas_worker', Path(__file__).with_name('atlas-worker.py'))
worker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(worker)


class AtlasWorkerTest(unittest.TestCase):
    def test_busy_analysis_returns_before_the_connection_timeout(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            helper = SimpleNamespace(ROOT=root, STATE=root)
            with patch.object(worker, 'router', return_value=helper), \
                    patch.object(worker.time, 'monotonic', side_effect=[0, 0, 3]), \
                    patch.object(worker.fcntl, 'flock', side_effect=BlockingIOError):
                with self.assertRaisesRegex(TimeoutError, 'busy'):
                    worker.local(dict(workspaceRoot=str(root / 'bestbuds'), operation='atlas.read', arguments={}))

    def test_bounded_transport_preserves_stdin_output_and_failures(self):
        result = worker.bounded_run([sys.executable, '-c', 'import sys;print(sys.stdin.read().upper())'],
            time.monotonic() + 2, input='source', text=True, capture_output=True)
        self.assertEqual('SOURCE\n', result.stdout)
        with self.assertRaises(subprocess.CalledProcessError):
            worker.bounded_run([sys.executable, '-c', 'raise SystemExit(7)'], time.monotonic() + 2)

    def test_timed_out_transfer_releases_locks_held_by_its_child(self):
        with tempfile.TemporaryDirectory() as directory:
            lock = Path(directory) / 'lock'; ready = Path(directory) / 'ready'
            child = 'import fcntl,time,pathlib;f=open(' + repr(str(lock)) + ',"w");fcntl.flock(f,fcntl.LOCK_EX);pathlib.Path(' + repr(str(ready)) + ').touch();time.sleep(30)'
            parent = 'import subprocess,sys,time;subprocess.Popen([sys.executable,"-c",' + repr(child) + ']);time.sleep(30)'
            with self.assertRaises(subprocess.TimeoutExpired):
                worker.bounded_run([sys.executable, '-c', parent], time.monotonic() + 0.7, capture_output=True)
            self.assertTrue(ready.exists())
            with lock.open('a') as file:
                fcntl.flock(file, fcntl.LOCK_EX | fcntl.LOCK_NB)

    def test_expired_request_cannot_start_another_transfer(self):
        with patch.object(worker.subprocess, 'Popen') as start:
            with self.assertRaises(subprocess.TimeoutExpired):
                worker.bounded_run(['never-start'], time.monotonic() - 1)
            start.assert_not_called()

    def test_analysis_view_keeps_text_and_omits_media_and_installed_runtimes(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            files = {'A.kt': b'class A', 'icon.svg': b'<svg/>', 'screen.png': b'image',
                     'unknown': b'header\0binary', '.reaktor/libexec/run': b'installed runtime'}
            for name, content in files.items():
                path = base / name; path.parent.mkdir(parents=True, exist_ok=True); path.write_bytes(content)
            self.assertEqual(['A.kt', 'icon.svg'], list(worker.analysis_files(base, files)))

    def source_fixture(self, directory):
        root = Path(directory).resolve()
        state = root / 'state'; state.mkdir()
        for name in ['a', 'b']:
            (root / name).mkdir(); (root / name / 'A.kt').write_text('old')
        stamps = dict(a=1, b=1)
        helper = SimpleNamespace(ROOT=root, STATE=state, REMOTE='/worker',
            source_files=lambda name: sorted(p.name for p in (root / name).iterdir() if p.is_file()),
            git_repositories=lambda base: [Path('.')])
        def sync(names, **kwargs):
            for name in names: stamps[name] += 1
        from unittest.mock import Mock
        helper.sync = Mock(side_effect=sync)
        return helper, stamps

    def test_source_cache_checks_contents_even_when_size_and_timestamp_match(self):
        with tempfile.TemporaryDirectory() as directory:
            helper, stamps = self.source_fixture(directory)
            with patch.object(worker, 'source_stamps', side_effect=lambda *args: stamps.copy()):
                worker.sync_sources(helper, ['a', 'b'])
                worker.sync_sources(helper, ['a', 'b'])
                self.assertEqual(2, helper.sync.call_count)
                source = helper.ROOT / 'a/A.kt'; before = source.stat()
                source.write_text('new'); os.utime(source, ns=(before.st_atime_ns, before.st_mtime_ns))
                worker.sync_sources(helper, ['a', 'b'])
                helper.sync.assert_called_with(['a'], transfer_files={'a': ['A.kt']}, checksum_files=None)
                stamps['b'] += 1  # A different worker-slot user replaced its source view.
                worker.sync_sources(helper, ['a', 'b'])
                helper.sync.assert_called_with(['b'], transfer_files={'b': ['A.kt']}, checksum_files={'b': ['A.kt']})

    def test_source_edits_during_transfer_do_not_validate_the_cache(self):
        with tempfile.TemporaryDirectory() as directory:
            helper, stamps = self.source_fixture(directory)
            def editing_sync(names, **kwargs):
                stamps['a'] += 1
                (helper.ROOT / 'a/A.kt').write_text('changed during transfer')
            helper.sync.side_effect = editing_sync
            with patch.object(worker, 'source_stamps', side_effect=lambda *args: stamps.copy()):
                worker.sync_sources(helper, ['a'])
                cached = json.loads((helper.STATE / 'atlas-worker-sync.json').read_text())['projects']['a']
                self.assertIsNone(cached['fingerprint'])
                self.assertEqual(['A.kt'], cached['uncertain'])
                worker.sync_sources(helper, ['a'])
                self.assertEqual(2, helper.sync.call_count)

    def test_delta_transfers_only_changed_files_and_reconciles_deletions(self):
        with tempfile.TemporaryDirectory() as directory:
            helper, stamps = self.source_fixture(directory)
            (helper.ROOT / 'a/B.kt').write_text('unchanged')
            with patch.object(worker, 'source_stamps', side_effect=lambda *args: stamps.copy()):
                worker.sync_sources(helper, ['a'])
                (helper.ROOT / 'a/A.kt').write_text('changed')
                worker.sync_sources(helper, ['a'])
                helper.sync.assert_called_with(['a'], transfer_files={'a': ['A.kt']}, checksum_files=None)
                (helper.ROOT / 'a/A.kt').unlink()
                worker.sync_sources(helper, ['a'])
                helper.sync.assert_called_with(['a'], transfer_files={'a': []}, checksum_files=None)

    def test_completed_project_stays_cached_when_a_later_transfer_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            helper, stamps = self.source_fixture(directory)
            sync = helper.sync.side_effect
            def fail_second(names, **kwargs):
                if names == ['b']: raise TimeoutError('busy')
                sync(names, **kwargs)
            helper.sync.side_effect = fail_second
            with patch.object(worker, 'source_stamps', side_effect=lambda *args: stamps.copy()):
                with self.assertRaises(TimeoutError): worker.sync_sources(helper, ['a', 'b'])
                helper.sync.reset_mock(); helper.sync.side_effect = sync
                worker.sync_sources(helper, ['a', 'b'])
                helper.sync.assert_called_once_with(['b'], transfer_files={'b': ['A.kt']}, checksum_files={'b': ['A.kt']})

    def test_initial_media_uses_metadata_but_later_media_edits_are_checksummed(self):
        with tempfile.TemporaryDirectory() as directory:
            helper, stamps = self.source_fixture(directory)
            image = helper.ROOT / 'a/screen.png'; image.write_bytes(b'old image')
            with patch.object(worker, 'source_stamps', side_effect=lambda *args: stamps.copy()):
                worker.sync_sources(helper, ['a'])
                helper.sync.assert_called_with(['a'], transfer_files={'a': ['A.kt', 'screen.png']},
                    checksum_files={'a': ['A.kt']})
                image.write_bytes(b'new image')
                worker.sync_sources(helper, ['a'])
                helper.sync.assert_called_with(['a'], transfer_files={'a': ['screen.png']}, checksum_files=None)

    def test_source_membership_and_git_head_invalidate_fingerprints(self):
        with tempfile.TemporaryDirectory() as directory:
            helper, _ = self.source_fixture(directory)
            before = worker.fingerprint(helper, 'a')
            (helper.ROOT / 'a/B.kt').write_text('new source')
            self.assertNotEqual(before, worker.fingerprint(helper, 'a'))
            (helper.ROOT / 'a/B.kt').unlink()
            self.assertEqual(before, worker.fingerprint(helper, 'a'))
            (helper.ROOT / 'a/.git').mkdir()
            with patch.object(worker.subprocess, 'check_output', side_effect=[b'head-one', b'head-two']):
                self.assertNotEqual(worker.fingerprint(helper, 'a'), worker.fingerprint(helper, 'a'))

    def test_source_transport_cannot_answer_local_observations_in_a_nested_batch(self):
        for preset in ['runtime', 'routes', 'affected-checks']:
            with self.assertRaisesRegex(ValueError, 'local owner'):
                worker.source_request(dict(operation='atlas.query', arguments={'queries': [{'queries': [{'preset': preset}]}]}))

    def test_paths_move_but_source_literals_remain_exact(self):
        data = {'path': '/local/bestbuds/A.kt', 'at': '/local/bestbuds/A.kt:2',
                'text': '/local/this is source', 'snippet': '/local/exact literal',
                'reads': [{'path': '/local/reaktor/B.kt'}], 'name': 'KernelAtlas'}
        moved = worker.translate(data, '/local', '/worker')
        self.assertEqual('/worker/bestbuds/A.kt', moved['path'])
        self.assertEqual('/worker/bestbuds/A.kt:2', moved['at'])
        self.assertEqual(data['text'], moved['text'])
        self.assertEqual(data['snippet'], moved['snippet'])
        self.assertEqual(data, worker.translate(moved, '/worker', '/local'))

    def test_worker_rejects_mutations_and_roots_outside_analysis_before_starting_anything(self):
        root = str(Path.home() / 'dev/offload/.remote-dev/slots/job-2/bestbuds')
        with patch.object(worker.subprocess, 'run') as execute:
            for payload in [dict(workspaceRoot=root, operation='change.apply', arguments={}),
                            dict(workspaceRoot='/Users/ovd/dev/offload/bestbuds', operation='atlas.read', arguments={})]:
                with self.assertRaises(ValueError): worker.serve(payload)
            execute.assert_not_called()

    def test_loopback_request_preserves_operation_arguments_and_reports_failed_outcomes(self):
        received = []
        outcome = {'type': 'completed', 'result': {'path': 'A.kt', 'text': 'class A'}}
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args): pass
            def do_POST(self):
                received.append((self.path, self.headers['Authorization'], json.loads(self.rfile.read(int(self.headers['Content-Length'])))))
                self.send_response(200); self.end_headers()
                self.wfile.write(json.dumps({'data': outcome, 'isError': False}).encode())
        with HTTPServer(('127.0.0.1', 0), Handler) as server:
            thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
            try:
                endpoint = {'port': server.server_port, 'token': 'fixture'}
                payload = {'operation': 'atlas.read', 'arguments': {'path': 'A.kt'}}
                self.assertEqual(outcome['result'], worker.operation('/worker/bestbuds', endpoint, payload))
                route, auth, body = received[0]
                self.assertEqual('/operations', route); self.assertEqual('Bearer fixture', auth)
                self.assertEqual('kernel', body['operation']); self.assertEqual(payload, body['arguments'])
                outcome.clear(); outcome.update(type='failed', message='stale source handle')
                with self.assertRaisesRegex(RuntimeError, 'stale source handle'):
                    worker.operation('/worker/bestbuds', endpoint, payload)
            finally:
                server.shutdown(); thread.join()


if __name__ == '__main__': unittest.main()

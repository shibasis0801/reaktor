import fcntl
import ast
import json
import os
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import remote_retention as retention


class RetentionTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.state = Path(self.temp.name).resolve()
        self.helper = SimpleNamespace(STATE=self.state, CONFIG={'artifact_retention_mib': 1},
                                      artifact_family=lambda _: 'all')
        (self.state / 'logs').mkdir()

    def tearDown(self):
        self.temp.cleanup()

    def archive(self, name, family='all', finished=True):
        root = self.state / 'artifacts' / name
        output = root / 'project/build/libs/app.jar'
        output.parent.mkdir(parents=True)
        output.write_text(name)
        report = root / 'project/build/test-results/test/result.xml'
        report.parent.mkdir(parents=True)
        report.write_text('test evidence')
        receipt = {'exit_code': 0, 'artifact_family': family}
        if finished:
            receipt['finished'] = '2026-10-13'
        (self.state / 'logs' / (name + '.json')).write_text(json.dumps(receipt))
        return root

    def test_redundant_payload_removed_but_diagnostics_and_shared_output_survive(self):
        old = self.archive('20261012-old')
        new = self.archive('20261013-new')
        copied = self.state / 'checkout.jar'
        os.link(old / 'project/build/libs/app.jar', copied)
        receipt = (self.state / 'logs/20261012-old.json').read_bytes()
        retention.cleanup(self.helper)
        self.assertFalse((old / 'project/build/libs/app.jar').exists())
        self.assertTrue((old / 'project/build/test-results/test/result.xml').exists())
        self.assertTrue((old / retention.MARKER).exists())
        self.assertTrue((new / 'project/build/libs/app.jar').exists())
        self.assertEqual('20261012-old', copied.read_text())
        self.assertEqual(receipt, (self.state / 'logs/20261012-old.json').read_bytes())

    def test_budget_and_dry_run(self):
        root = self.archive('20261013-new')
        self.helper.CONFIG['artifact_retention_mib'] = 0
        report = retention.cleanup(self.helper, dry_run=True)
        self.assertEqual(str(root), report['pruned_payloads'][0]['path'])
        self.assertTrue((root / 'project/build/libs/app.jar').exists())
        retention.cleanup(self.helper)
        self.assertFalse((root / 'project/build/libs/app.jar').exists())

    def test_active_and_unknown_archives_are_never_pruned(self):
        root = self.archive('20261013-active', finished=False)
        unknown = self.state / 'artifacts/unknown'
        unknown.mkdir()
        (unknown / 'source.txt').write_text('unknown data')
        self.helper.CONFIG['artifact_retention_mib'] = 0
        retention.cleanup(self.helper)
        self.assertTrue((root / 'project/build/libs/app.jar').exists())
        self.assertEqual('unknown data', (unknown / 'source.txt').read_text())

    def test_an_abandoned_runs_payload_is_trimmed_and_never_kept_as_a_reference(self):
        killed = self.archive('20261014-killed', finished=False)
        receipt = self.state / 'logs/20261014-killed.json'
        receipt.write_text(json.dumps(dict(json.loads(receipt.read_text()), abandoned='2026-10-14T12:00:00')))
        finished = self.archive('20261013-finished')
        retention.cleanup(self.helper)
        self.assertFalse((killed / 'project/build/libs/app.jar').exists())
        self.assertTrue((killed / 'project/build/test-results/test/result.xml').exists())
        self.assertTrue((finished / 'project/build/libs/app.jar').exists())

    def test_a_note_in_the_abandoned_field_keeps_an_unfinished_runs_payload(self):
        noted = self.archive('20261014-noted', finished=False)
        receipt = self.state / 'logs/20261014-noted.json'
        receipt.write_text(json.dumps(dict(json.loads(receipt.read_text()), abandoned='Benchmark inputs retained')))
        retention.cleanup(self.helper)
        self.assertTrue((noted / 'project/build/libs/app.jar').exists())

    def test_busy_host_skips_all_cleanup(self):
        root = self.archive('20261013-new')
        self.helper.CONFIG['artifact_retention_mib'] = 0
        with (self.state / 'worker-gradle.lock').open('a') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            self.assertIn('skipped', retention.cleanup(self.helper))
        self.assertTrue((root / 'project/build/libs/app.jar').exists())

    def test_waiting_cleanup_releases_local_lease_for_worker_publication(self):
        with (self.state / 'worker-gradle.lock').open('a') as worker:
            fcntl.flock(worker, fcntl.LOCK_EX | fcntl.LOCK_NB)
            def worker_publishes(_):
                with (self.state / 'local-gradle.lock').open('a') as local:
                    fcntl.flock(local, fcntl.LOCK_EX | fcntl.LOCK_NB)
                fcntl.flock(worker, fcntl.LOCK_UN)
            with patch.object(retention.time, 'sleep', side_effect=worker_publishes) as pause:
                retention.cleanup(self.helper, wait=True)
            pause.assert_called_once()

    def test_slot_cleanup_preserves_source_git_and_symlink_targets(self):
        slot = self.state / 'slots/local'
        (slot / 'project/build/classes').mkdir(parents=True)
        (slot / 'project/build/classes/output').write_text('generated')
        (slot / 'project/.git').mkdir()
        (slot / 'project/.git/config').write_text('git configuration')
        (slot / 'project/source.kt').write_text('source')
        outside = self.state / 'outside'
        outside.mkdir()
        (outside / 'keep').write_text('source')
        (slot / 'project/node_modules').symlink_to(outside, target_is_directory=True)
        retention.cleanup(self.helper, trim_slots=True)
        self.assertFalse((slot / 'project/build').exists())
        self.assertTrue((slot / 'project/.git/config').exists())
        self.assertTrue((slot / 'project/source.kt').exists())
        self.assertTrue((outside / 'keep').exists())
        self.assertTrue((slot / 'project/node_modules').is_symlink())

    def test_finished_isolated_evidence_keeps_test_results(self):
        run = self.state / 'runs/20261012-run'
        root = run / 'artifacts'
        (root / 'project/build/libs').mkdir(parents=True)
        (root / 'project/build/libs/output.jar').write_text('generated')
        (root / 'project/build/reports').mkdir()
        (root / 'project/build/reports/results.html').write_text('report')
        (run / 'receipt.json').write_text(json.dumps({'finished': '2026-10-12'}))
        retention.cleanup(self.helper)
        self.assertFalse((root / 'project/build/libs/output.jar').exists())
        self.assertTrue((root / 'project/build/reports/results.html').exists())
        self.assertTrue((run / 'receipt.json').exists())

    def test_interrupted_pruning_is_ineligible_for_reuse_and_can_be_finished(self):
        root = self.archive('20261013-new')
        self.helper.CONFIG['artifact_retention_mib'] = 0
        with patch.object(Path, 'unlink', side_effect=OSError('interrupted')):
            with self.assertRaises(OSError):
                retention.cleanup(self.helper)
        self.assertFalse(retention.read_json(root / retention.MARKER)['complete'])
        retention.cleanup(self.helper)
        self.assertTrue(retention.read_json(root / retention.MARKER)['complete'])
        self.assertFalse((root / 'project/build/libs/app.jar').exists())

    def test_real_rsync_reference_selector_skips_pruned_payloads(self):
        old = self.archive('20261012-old')
        new = self.archive('20261013-new')
        (new / retention.MARKER).write_text('{}')
        source = ast.parse(Path(__file__).with_name('remote-dev').read_text())
        function = next(node for node in source.body
                        if isinstance(node, ast.FunctionDef) and node.name == 'artifact_reference')
        namespace = {'STATE': self.state, 'json': json, 'artifact_family': lambda _: 'all'}
        exec(compile(ast.Module(body=[function], type_ignores=[]), 'remote-dev', 'exec'), namespace)
        self.assertEqual(old / 'project', namespace['artifact_reference']('project', 'next', 'all'))

    def test_cleanup_inside_owned_workspace_lock_still_checks_host_locks(self):
        root = self.archive('20261013-new')
        self.helper.CONFIG['artifact_retention_mib'] = 0
        with (self.state / 'workspace.lock').open('a') as workspace:
            fcntl.flock(workspace, fcntl.LOCK_EX | fcntl.LOCK_NB)
            with (self.state / 'local-gradle.lock').open('a') as host:
                fcntl.flock(host, fcntl.LOCK_EX | fcntl.LOCK_NB)
                self.assertIn('skipped', retention.cleanup(self.helper, held_locks={'workspace'}))
            retention.cleanup(self.helper, held_locks={'workspace'})
        self.assertFalse((root / 'project/build/libs/app.jar').exists())


if __name__ == '__main__':
    unittest.main()

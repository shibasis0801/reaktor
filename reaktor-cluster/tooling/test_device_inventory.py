import contextlib
import io
import json
from pathlib import Path
import subprocess
from tempfile import TemporaryDirectory
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import build_control
import mobile_worker
from test_remote_router import router


class DeviceInventoryTest(unittest.TestCase):
    def test_native_observation_uses_bounded_read_commands_and_cleans_json_file(self):
        paths = []
        def run(command, **options):
            self.assertEqual(8, options['timeout'])
            self.assertEqual(subprocess.DEVNULL, options['stdin'])
            if command[0] == 'adb':
                self.assertEqual(['adb', 'devices', '-l'], command)
                options['stdout'].write(b'List of devices attached\nlocked unauthorized\n')
            else:
                self.assertEqual(['xcrun', 'devicectl', 'list', 'devices', '--timeout', '5', '--json-output'], command[:-1])
                path = Path(command[-1])
                self.assertNotEqual('/dev/stdout', str(path))
                paths.append(path)
                path.write_text(json.dumps({'result': {'devices': [{'identifier': 'cached-phone'}]}}))
            return SimpleNamespace(returncode=0)
        with patch('subprocess.run', side_effect=run), contextlib.redirect_stdout(io.StringIO()) as output:
            mobile_worker.read_inventory('m1-worker')
        observation = json.loads(output.getvalue())
        self.assertEqual('m1-worker', observation['host'])
        self.assertIn('unauthorized', observation['adb']['output'])
        self.assertEqual('cached-phone', observation['devicectl']['data']['result']['devices'][0]['identifier'])
        self.assertTrue(all(not path.exists() and not path.parent.exists() for path in paths))

    def test_timeout_and_oversize_are_transport_failures_not_empty_device_success(self):
        for mode in ['timeout', 'oversize', 'invalid-json', 'missing-tool']:
            paths = []
            def run(command, **options):
                if command[0] == 'adb':
                    if mode == 'missing-tool':
                        raise FileNotFoundError('adb')
                    if mode == 'timeout':
                        raise subprocess.TimeoutExpired(command, options['timeout'])
                    options['stdout'].write(b'x' * 24_577 if mode == 'oversize' else b'List of devices attached\n')
                else:
                    path = Path(command[-1])
                    paths.append(path)
                    path.write_text('invalid' if mode == 'invalid-json' else json.dumps({'result': {'devices': []}}))
                return SimpleNamespace(returncode=0)
            with patch('subprocess.run', side_effect=run), contextlib.redirect_stdout(io.StringIO()) as output:
                mobile_worker.read_inventory('m1-worker')
            observation = json.loads(output.getvalue())
            transport = 'devicectl' if mode == 'invalid-json' else 'adb'
            self.assertNotEqual(0, observation[transport]['exitCode'])
            self.assertIn('problem', observation[transport])
            self.assertTrue(all(not path.parent.exists() for path in paths))

    def test_inventory_routes_through_existing_worker_exec_and_explicit_state(self):
        helper = router()
        helper.HOST = 'm1-worker'
        with TemporaryDirectory() as directory:
            helper.STATE = Path(directory).resolve()
            (helper.STATE / 'config.json').write_text('{}')
            self.assertEqual(helper.STATE, build_control.selected_state(
                ['mobile', 'inventory', '--state-root', str(helper.STATE)], Path('/unused')))
            with patch.object(helper.subprocess, 'run', return_value=SimpleNamespace(returncode=0)) as run, \
                 patch.object(helper, 'sync', side_effect=AssertionError('Inventory must not sync')), \
                 patch.object(mobile_worker, 'release_connections', side_effect=AssertionError('Inventory must not mutate connections')):
                self.assertEqual(0, mobile_worker.main(helper, ['inventory', '--state-root', str(helper.STATE)]))
            command = run.call_args.args[0]
            self.assertEqual('m1-worker', command[-2])
            self.assertIn('read_inventory', command[-1])
            self.assertIn('TMPDIR=/tmp', command[-1])
            with self.assertRaises(ValueError):
                build_control.selected_state(['mobile', 'adb', '--state-root', str(helper.STATE)], Path('/unused'))


if __name__ == '__main__':
    unittest.main()

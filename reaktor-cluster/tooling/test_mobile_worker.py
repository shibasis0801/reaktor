from pathlib import Path
from tempfile import TemporaryDirectory
from types import SimpleNamespace
import itertools
import json
import os
import signal
import unittest
from unittest.mock import call, patch

import mobile_worker
from test_remote_router import router


class MobileWorkerTest(unittest.TestCase):
    def test_mobile_tasks_and_gradle_bridge_follow_usb_host(self):
        helper = router()
        helper.CONFIG['device_host'] = 'worker'
        options = {':engine:jvmTest': [{'name': 'tests', 'type': 'String'}]}
        with patch.object(helper, 'read_gradle_request', return_value=None), \
             patch.object(helper, 'read_hosts_contract', return_value={'taskOptions': options}):
            for task in [':deviceTests:mobileDeviceFlows', ':Manna:deviceTests:checkDeviceTests',
                         ':appAndroid:installDebug', ':app:connectedDebugAndroidTest', ':prepareIosDevice', ':maestroRunnerSetup']:
                self.assertTrue(mobile_worker.requested(helper, 'bestbuds', '.', [task]), task)
            for task in [':engine:jvmTest', ':reaktorDesktop:hangarFlows', ':appAndroid:assembleDebug', ':reaktorCli:installDist']:
                self.assertFalse(mobile_worker.requested(helper, 'bestbuds', '.', [task]), task)
            self.assertFalse(mobile_worker.requested(helper, 'bestbuds', '.', [':engine:jvmTest', '--tests', ':deviceTests:deviceFlows']))
        request = (None, None, {'node': {'project': ':deviceTests', 'name': 'loginDeviceFlows'}})
        with patch.object(helper, 'read_gradle_request', return_value=request), \
             patch.object(helper, 'read_hosts_contract', return_value={}):
            self.assertTrue(mobile_worker.requested(helper, 'bestbuds', '.', ['bridge']))
        helper.CONFIG['device_host'] = 'local'
        self.assertFalse(mobile_worker.requested(helper, 'bestbuds', '.', [':deviceTests:mobileDeviceFlows']))

    def test_mobile_work_uses_slot_zero_and_does_not_consider_local_load(self):
        helper = router()
        helper.CONFIG['device_host'] = 'worker'
        with TemporaryDirectory() as directory:
            helper.STATE = Path(directory)
            with patch.object(helper, 'read_gradle_request', return_value=None), \
                 patch.object(helper, 'read_hosts_contract', return_value={}), \
                 patch.object(helper, 'slot_build', return_value=13) as build, \
                 patch.object(mobile_worker, 'release_connections') as release, \
                 patch.object(helper, 'local_capacity', side_effect=AssertionError('Must not use this Mac')):
                self.assertEqual(13, helper.scheduled_gradle('bestbuds', '.', [':deviceTests:deviceFlows']))
                release.assert_called_once_with(helper)
            self.assertEqual(0, build.call_args.args[3])

    def test_worker_command_preserves_arguments_and_maps_workspace_paths(self):
        helper = router()
        helper.HOST = 'm1-worker'
        helper.BASE_REMOTE = '/worker/offload'
        with patch.object(helper.subprocess, 'run', side_effect=AssertionError('Retired command must not execute')), \
             patch.object(helper, 'sync', side_effect=AssertionError('Retired command must not synchronize')), \
             patch('sys.stderr'):
            with self.assertRaises(SystemExit) as retired:
                mobile_worker.main(helper, ['maestro', 'lint', 'tests/bestbudsApp/'])
            self.assertEqual(2, retired.exception.code)
        with patch.object(helper.subprocess, 'run', return_value=SimpleNamespace(returncode=7)) as run:
            self.assertEqual(7, mobile_worker.main(helper, ['adb', 'shell', 'input', 'text', "hello; $(whoami) ' world"]))
        command = run.call_args.args[0]
        self.assertEqual(['ssh', '-o', 'BatchMode=yes'], command[:3])
        self.assertEqual('m1-worker', command[-2])
        self.assertIn(helper.shlex.quote("hello; $(whoami) ' world"), command[-1])
        self.assertIn('. /worker/offload/.remote-dev/mobile-env.sh', command[-1])
        with patch.object(helper.subprocess, 'run', return_value=SimpleNamespace(returncode=0)) as direct, \
             patch.object(helper, 'sync', side_effect=AssertionError('Connection must not synchronize builds')), \
             patch.object(helper, 'local_capacity', side_effect=AssertionError('Connection must not wait for load')):
            remote_path = helper.BASE_REMOTE + '/bestbuds/driver.xctestrun'
            self.assertEqual(0, mobile_worker.main(helper, ['exec', 'python3', '-c', 'print("device probe")', remote_path]))
            self.assertIn(helper.shlex.quote('print("device probe")'), direct.call_args.args[0][-1])
            self.assertIn(remote_path, direct.call_args.args[0][-1])
        for args, code in [(['ios'], 0), (['android', '--device', 'physical-phone', '--json'], 2)]:
            with patch.object(helper.subprocess, 'run', return_value=SimpleNamespace(returncode=code)) as connect, \
                 patch.object(mobile_worker, 'gradle', side_effect=AssertionError('Connection must not invoke Gradle')):
                self.assertEqual(code, mobile_worker.main(helper, ['connect', *args]))
                self.assertEqual([str(helper.Path.home() / '.reaktor/bin/reaktor'), 'connect', *args], connect.call_args.args[0])
        with TemporaryDirectory() as directory:
            base = Path(directory)
            root = base / ('reaktor-connect-' + str(os.getuid()))
            root.mkdir()
            for pid, device in [(11, 'ours'), (12, 'unrelated')]:
                (root / (device + '.json')).write_text(json.dumps({'pid': pid, 'device': device, 'platform': 'android'}))
            def process(args, **kwargs):
                return SimpleNamespace(stdout='adb shell am instrument -e reaktorConnect ours' if args[2] == '11' else 'adb shell am instrument other-suite')
            with patch('pathlib.Path', return_value=base), \
                 patch('subprocess.run', side_effect=process), \
                 patch('os.getpgid', return_value=11), \
                 patch('os.killpg') as stop:
                mobile_worker.release_local_connections(['android'])
                stop.assert_called_once_with(11, signal.SIGTERM)
            self.assertEqual([], list(root.glob('*.json')))

    def test_release_ends_connect_xcodebuilds_found_by_command_line(self):
        products = '/worker/wda/DerivedData/Build/Products/'
        xcodebuild = '/Applications/Xcode-26.6.app/Contents/Developer/usr/bin/xcodebuild test-without-building -xctestrun '
        table = '\n'.join([
            f'  101   101 {xcodebuild}{products}reaktor-connect-00008120-A.xctestrun -destination platform=iOS,id=00008120-A',
            f'  102    90 {xcodebuild}{products}reaktor-connect-00008120-B.xctestrun',
            f'  103   103 {xcodebuild}{products}WebDriverAgentRunner_iphoneos26.5-arm64.xctestrun',
            f'  104   104 /usr/bin/tail -f {products}reaktor-connect-00008120-A.xctestrun',
        ])
        running = {101, 102, 103, 104}

        def process(args, **kwargs):
            if args[:2] == ['ps', '-ax']:
                return SimpleNamespace(stdout=table)
            return SimpleNamespace(stdout='S\n' if int(args[2]) in running else '')

        with TemporaryDirectory() as directory:
            base = Path(directory)
            (base / ('reaktor-connect-' + str(os.getuid()))).mkdir()
            with patch('pathlib.Path', return_value=base), \
                 patch('subprocess.run', side_effect=process), \
                 patch('time.monotonic', side_effect=itertools.count(0, 5)), \
                 patch('time.sleep'), \
                 patch('os.killpg', side_effect=lambda pid, sig: running.discard(pid)) as group, \
                 patch('os.kill') as single:
                mobile_worker.release_local_connections(['ios'])
        group.assert_called_once_with(101, signal.SIGTERM)
        self.assertEqual([call(102, signal.SIGTERM), call(102, signal.SIGKILL)], single.call_args_list)
        with patch('pathlib.Path', return_value=Path('/nonexistent')), \
             patch('subprocess.run', side_effect=AssertionError('Android release must not look for xcodebuild')):
            mobile_worker.release_local_connections(['android'])


if __name__ == '__main__':
    unittest.main()

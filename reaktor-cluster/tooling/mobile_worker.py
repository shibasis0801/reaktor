"""Route physical mobile work to the Mac that owns the USB devices."""
import re


def release_local_connections(platforms):
    import json
    import os
    import pathlib
    import re
    import signal
    import subprocess
    import time
    root = pathlib.Path('/tmp') / ('reaktor-connect-' + str(os.getuid()))
    for state in root.glob('*.json'):
        value = json.loads(state.read_text())
        if value.get('platform') not in platforms:
            continue
        pid = value.get('pid', 0)
        command = subprocess.run(['ps', '-p', str(pid), '-o', 'command='], capture_output=True, text=True).stdout
        marker = 'reaktor-connect-' + value.get('device', '') if value.get('platform') == 'ios' else 'reaktorConnect ' + value.get('device', '')
        if marker in command:
            try:
                if os.getpgid(pid) == pid:
                    if value.get('platform') == 'android':
                        for package in ['io.appium.uiautomator2.server', 'io.appium.uiautomator2.server.test']:
                            subprocess.run(['adb', '-s', value['device'], 'shell', 'am', 'force-stop', package],
                                           check=True, timeout=3, stdout=subprocess.DEVNULL)
                    os.killpg(pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
        state.unlink(missing_ok=True)
    if 'ios' not in platforms:
        return

    def running(pid):
        status = subprocess.run(['ps', '-p', str(pid), '-o', 'stat='], capture_output=True, text=True).stdout.strip()
        return bool(status) and not status.startswith('Z')

    leftovers = []
    table = subprocess.run(['ps', '-ax', '-o', 'pid=,pgid=,command='], capture_output=True, text=True).stdout
    for line in table.splitlines():
        fields = line.split(None, 2)
        if len(fields) == 3 and re.search(r'(^|/)xcodebuild\s.*\s-xctestrun\s+\S*/reaktor-connect-[^/\s]+\.xctestrun(\s|$)', fields[2]):
            pid, group = int(fields[0]), int(fields[1])
            try:
                if group == pid:
                    os.killpg(pid, signal.SIGTERM)
                else:
                    os.kill(pid, signal.SIGTERM)
                leftovers.append(pid)
            except ProcessLookupError:
                pass
    deadline = time.monotonic() + 20
    while leftovers and time.monotonic() < deadline:
        time.sleep(0.2)
        leftovers = [pid for pid in leftovers if running(pid)]
    for pid in leftovers:
        try:
            os.kill(pid, signal.SIGKILL)
        except ProcessLookupError:
            pass


def release_connections(helper, platforms=('android', 'ios')):
    import inspect
    code = 'import sys\n' + inspect.getsource(release_local_connections) + '\nrelease_local_connections(sys.argv[1:])\n'
    code = main(helper, ['exec', 'python3', '-c', code, *platforms])
    if code:
        raise RuntimeError('Could not release connect-owned phone drivers before the device suite')
    return code


def requested(helper, name, relative, args):
    if helper.CONFIG.get('device_host') != 'worker':
        return False
    hosts = helper.read_hosts_contract(helper.selected_gradle_directory(name, relative, args))
    tasks = helper.gradle_tasks(args, hosts.get('taskOptions', {}))
    selected = helper.read_gradle_request(name, relative, args)
    if selected is not None:
        node = selected[2]['node']
        tasks = [node['project'].rstrip(':') + ':' + node['name']]
    return device_tasks(tasks)


def device_tasks(tasks):
    return any('deviceTests' in task.split(':') or task.split(':')[-1] in {
        'maestroRunnerSetup', 'prepareIosDevice', 'prepareMobileFixtures',
    } or re.fullmatch(r'(install(Debug|Release)(AndroidTest)?|connected\w*AndroidTest)', task.split(':')[-1])
        for task in tasks)


def gradle(helper, name, relative, args):
    import remote_build
    # All phone suites share one workspace and lease. Push flows use both phones,
    # and separate worker build slots must never drive the same devices at once.
    print(f'Physical mobile work runs on {helper.HOST} (worker USB devices)', flush=True)
    with remote_build.lease(helper.STATE / 'worker-gradle.lock') as lock:
        release_connections(helper)
        return helper.slot_build(name, relative, args, 0, lock)


def read_inventory(host):
    """Observe native transport records without connecting, pairing or launching devices."""
    import json
    import os
    import subprocess
    import tempfile

    def capture(command, json_output=False):
        with tempfile.TemporaryDirectory(prefix='reaktor-device-inventory-') as directory:
            output_path = os.path.join(directory, 'devices.json')
            if json_output:
                command = [*command, '--json-output', output_path]
            try:
                with tempfile.TemporaryFile() as stdout, tempfile.TemporaryFile() as stderr:
                    result = subprocess.run(command, stdin=subprocess.DEVNULL, stdout=stdout, stderr=stderr,
                                            timeout=8, env=dict(os.environ, LC_ALL='C'))
                    if result.returncode:
                        return {'exitCode': result.returncode, 'problem': 'Native device probe failed'}
                    if json_output:
                        with open(output_path, 'rb') as source:
                            contents = source.read(24_577)
                    else:
                        stdout.seek(0)
                        contents = stdout.read(24_577)
                    if len(contents) > 24_576:
                        return {'exitCode': 1, 'problem': 'Device capture limit reached'}
                    if json_output:
                        return {'exitCode': 0, 'data': json.loads(contents)}
                    return {'exitCode': 0, 'output': contents.decode('utf-8', errors='replace')}
            except subprocess.TimeoutExpired:
                return {'exitCode': 124, 'problem': 'Native device probe timed out'}
            except (OSError, ValueError):
                return {'exitCode': 1, 'problem': 'Native device observation unavailable'}

    observation = {'host': host, 'adb': capture(['adb', 'devices', '-l']),
                   'devicectl': capture(['xcrun', 'devicectl', 'list', 'devices', '--timeout', '5'], json_output=True)}
    print(json.dumps(observation, separators=(',', ':')), flush=True)


def main(helper, args):
    import argparse
    parser = argparse.ArgumentParser(description='Control phones attached to m1-worker')
    parser.add_argument('command', choices=['doctor', 'devices', 'inventory', 'connect', 'exec', 'gradle', 'adb', 'ios', 'push'])
    parser.add_argument('args', nargs=argparse.REMAINDER)
    options = parser.parse_args(args)
    rest = options.args
    if rest[:1] == ['--']:
        rest = rest[1:]
    if options.command == 'inventory':
        if rest and rest != ['--state-root', str(helper.STATE)]:
            parser.error('inventory accepts only the configured --state-root')
        import inspect
        code = inspect.getsource(read_inventory) + '\nread_inventory(' + repr(helper.HOST) + ')\n'
        return main(helper, ['exec', 'python3', '-c', code])
    if options.command == 'gradle':
        if not rest or rest[0] not in helper.PROJECTS:
            parser.error('gradle needs a configured project followed by Gradle arguments')
        return gradle(helper, rest[0], helper.Path('.'), rest[1:])
    if options.command == 'push':
        return gradle(helper, 'bestbuds', helper.Path('.'), [':deviceTests:pushDeviceFlows', *rest])
    if options.command == 'connect':
        return helper.subprocess.run([str(helper.Path.home() / '.reaktor/bin/reaktor'), 'connect', *rest]).returncode
    env = helper.BASE_REMOTE + '/.remote-dev/mobile-env.sh'
    prefix = f'. {helper.shlex.quote(env)} && export PATH=/opt/homebrew/bin:$PATH && '
    if options.command == 'exec':
        if not rest:
            parser.error('exec needs a native device-host command')
        prefix = f'eval "$(/usr/bin/sed -n \'/^export /p\' {helper.shlex.quote(env)})" && export PATH=/opt/homebrew/bin:$PATH TMPDIR=/tmp && '
        command = rest
    elif options.command == 'doctor':
        command = ['maestro-runner', '--team-id', 'XUB9G6A6DA', 'doctor', *rest]
    elif options.command == 'devices':
        command = ['maestro-runner', 'devices', *rest]
    elif options.command == 'ios':
        command = ['xcrun', 'devicectl', *rest]
    else:
        command = ['adb', *rest]
    translated = command if options.command == 'exec' else [arg.replace(str(helper.ROOT), helper.BASE_REMOTE) for arg in command]
    shell = prefix + 'cd ' + helper.shlex.quote(helper.BASE_REMOTE + '/bestbuds') + ' && ' + helper.shlex.join(translated)
    return helper.subprocess.run(['ssh', '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=3', '-o', 'ServerAliveInterval=3', '-o', 'ServerAliveCountMax=1', helper.HOST, shell]).returncode


if __name__ == '__main__':
    raise SystemExit('Use remote-dev mobile ...')

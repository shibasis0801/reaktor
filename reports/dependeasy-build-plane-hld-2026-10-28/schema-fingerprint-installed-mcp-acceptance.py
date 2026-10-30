import hashlib
import json
import os
from pathlib import Path
import selectors
import subprocess
import sys
import time


ROOT = Path('/Users/ovd/dev/bestbuds')
REPORT = Path(__file__).parent
EXPECTED_OWNER = json.loads((REPORT / 'schema-fingerprint-package-library-proof.json').read_text())['primaryLibs']['kernel.jar']


def probe(label, command):
    started = time.monotonic()
    process = subprocess.Popen(command, cwd=ROOT, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                               stderr=subprocess.DEVNULL)
    selector = selectors.DefaultSelector()
    selector.register(process.stdout, selectors.EVENT_READ)
    pending = b''

    def send(value):
        process.stdin.write((json.dumps(value) + '\n').encode())
        process.stdin.flush()

    def call(identity, method, params):
        nonlocal pending
        send({'jsonrpc': '2.0', 'id': identity, 'method': method, 'params': params})
        deadline = time.monotonic() + 35
        while time.monotonic() < deadline:
            while b'\n' in pending:
                line, pending = pending.split(b'\n', 1)
                try:
                    value = json.loads(line)
                except (ValueError, UnicodeDecodeError):
                    continue
                if value.get('id') == identity:
                    if 'error' in value:
                        raise AssertionError(value['error'])
                    return value['result']
            if selector.select(max(0, deadline - time.monotonic())):
                data = os.read(process.stdout.fileno(), 65536)
                if not data:
                    raise AssertionError(f'{label}: exited {process.poll()}')
                pending += data
        raise TimeoutError(f'{label}: {method}')

    try:
        initialized = call(1, 'initialize', {'protocolVersion': '2025-06-18', 'capabilities': {},
                                          'clientInfo': {'name': 'build-plane-acceptance', 'version': '1'}})
        send({'jsonrpc': '2.0', 'method': 'notifications/initialized'})
        names = [tool['name'] for tool in call(2, 'tools/list', {})['tools']]
        assert names == ['kernel', 'atlas_query', 'atlas_read', 'change_plan', 'change_apply', 'check_run'], (label, names)
        status = call(3, 'tools/call', {'name': 'kernel', 'arguments': {'operation': 'kernel.status', 'arguments': {}}})
        status_text = next(item['text'] for item in status['content'] if item['type'] == 'text')
        owner = json.loads(status_text)['result']
        assert owner['owner'].startswith(EXPECTED_OWNER), owner['owner']
        assert owner['operations'] == 290
        large_params = {'name': 'kernel', 'arguments': {'operation': 'operation.catalog', 'arguments': {'query': 'x' * 980000}}}
        large_bytes = len((json.dumps({'jsonrpc': '2.0', 'id': 5, 'method': 'tools/call', 'params': large_params}) + '\n').encode())
        assert 980000 < large_bytes < 1048576
        large = call(5, 'tools/call', large_params)
        assert not large.get('isError'), large
        large_text = next(item['text'] for item in large['content'] if item['type'] == 'text')
        large_result = json.loads(large_text)
        assert large_result.get('type') == 'completed' and large_result.get('result') == [], large_result
        after = call(6, 'tools/call', {'name': 'kernel', 'arguments': {'operation': 'kernel.status', 'arguments': {}}})
        assert not after.get('isError'), after
        after_owner = json.loads(next(item['text'] for item in after['content'] if item['type'] == 'text'))['result']['owner']
        assert after_owner == owner['owner'], (after_owner, owner['owner'])
        source = call(4, 'tools/call', {'name': 'atlas_read', 'arguments': {'name': 'BuildModelReader.modelPage'}})
        text = '\n'.join(item.get('text', '') for item in source['content'])
        assert 'model.nodes.drop(offset).take(limit)' in text, text
        assert not source.get('isError')
        return {'label': label, 'protocol': initialized['protocolVersion'], 'tools': names,
                'owner': owner['owner'], 'operations': owner['operations'], 'behind': owner['behind'],
                'sourceReadSha256': hashlib.sha256(text.encode()).hexdigest(),
                'largeReadRequestBytes': large_bytes, 'largeReadResult': 'completed: empty catalog', 'followingStatusSameOwner': True, 'seconds': time.monotonic() - started}
    finally:
        process.stdin.close()
        try:
            process.wait(timeout=8)
        except subprocess.TimeoutExpired:
            process.terminate()
            process.wait(timeout=8)
        selector.close()


rows = []
for label, command in [
    ('primary-stdio-mcp', [str(Path.home() / '.reaktor/bin/reaktor'), 'mcp', '--stdio', '--dir', str(ROOT), '--seat', 'codex']),
    ('agent-stdio-mcp', [str(Path.home() / '.local/bin/reaktor-agent'), 'workspace', 'mcp', '--dir', str(ROOT), '--seat', 'codex']),
]:
    rows.append(probe(label, command))
    print(json.dumps(rows[-1]), flush=True)
result = {'observedAt': time.time(), 'rows': rows, 'scope': 'Fresh installed launchers, SHA256-equal checked distributions and normally upgraded BestBuds owner. Both CLI transports include the qualified schema fingerprint implementation and kernel invocation deadline.'}
(REPORT / (sys.argv[1] if len(sys.argv) > 1 else 'source-capture-installed-acceptance.json')).write_text(json.dumps(result, indent=2) + '\n')
print(json.dumps(result))

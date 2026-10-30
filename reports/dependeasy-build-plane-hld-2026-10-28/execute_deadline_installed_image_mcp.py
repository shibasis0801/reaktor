"""Submit the retained, reviewed local image plan once through the installed MCP bridge."""
import json
import os
from pathlib import Path
import selectors
import subprocess
import time

REPORT = Path(__file__).parent
ROOT = Path('/Users/ovd/dev/bestbuds')
args = json.loads((REPORT / 'server-image-deadline-installed-execution-arguments.json').read_text())
process = subprocess.Popen(['/Users/ovd/.reaktor/bin/reaktor', 'mcp', '--stdio', '--dir', str(ROOT), '--seat', 'codex'],
                           cwd=ROOT, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
selector = selectors.DefaultSelector()
selector.register(process.stdout, selectors.EVENT_READ)
pending = b''


def call(identity, method, params):
    global pending
    message = json.dumps({'jsonrpc': '2.0', 'id': identity, 'method': method, 'params': params}, separators=(',', ':')).encode() + b'\n'
    assert len(message) <= 1048576
    if identity == 2:
        (REPORT / 'server-image-deadline-installed-mcp-submit-metadata.json').write_text(json.dumps({'requestBytes': len(message), 'submittedAt': time.time(), 'requestId': 'dependeasy-server-image-deadline-installed-20261029-1215'}, indent=2) + '\n')
    process.stdin.write(message)
    process.stdin.flush()
    deadline = time.monotonic() + 180
    while time.monotonic() < deadline:
        while b'\n' in pending:
            line, pending = pending.split(b'\n', 1)
            value = json.loads(line)
            if value.get('id') == identity:
                assert 'error' not in value, value
                return value['result']
        if selector.select(max(0, deadline - time.monotonic())):
            chunk = os.read(process.stdout.fileno(), 65536)
            assert chunk, f'Bridge exited: {process.poll()}'
            pending += chunk
    raise TimeoutError('Inspect retained run before any retry; submission outcome is unknown')


try:
    call(1, 'initialize', {'protocolVersion': '2025-06-18', 'capabilities': {}, 'clientInfo': {'name': 'dependeasy-image-qualification', 'version': '1'}})
    result = call(2, 'tools/call', {'name': 'kernel', 'arguments': {'operation': 'build.run', 'requestId': 'dependeasy-server-image-deadline-installed-20261029-1215', 'arguments': args}})
    (REPORT / 'server-image-deadline-installed-mcp-submission.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result), flush=True)
finally:
    process.stdin.close()
    try:
        process.wait(timeout=8)
    except subprocess.TimeoutExpired:
        process.terminate()
        process.wait(timeout=8)
    selector.close()

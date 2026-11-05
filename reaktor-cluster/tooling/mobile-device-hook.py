#!/usr/bin/env python3
"""Apply this worker's iOS WDA signing before a product's existing device hook."""
import datetime
import hashlib
import json
from pathlib import Path
import plistlib
import re
import subprocess
import sys


def workspace():
    source = Path(__file__).resolve()
    if source.parent.name == '.remote-dev':
        return source.parent.parent
    from cluster_locations import workspace_root
    return workspace_root(source)


def prepare(contract):
    root = workspace()
    config = json.loads((root / 'bestbuds/config/mobile-e2e.json').read_text())
    if contract['team'] != config['appleTeamId'] or contract['wdaBundleId'] != config['wdaBundleId']:
        raise ValueError('The device suite requires a different iOS signing team or WDA bundle')
    runner = Path(contract['runner']).resolve().parent.parent
    # Only the suite-owned copy is signed. The pinned global installation stays pristine.
    if not runner.is_relative_to(root) or not {'tmp', 'work'}.intersection(runner.relative_to(root).parts):
        raise ValueError('Expected a workspace-owned device runner under tmp/ or work/')
    profiles = root / 'bestbuds/config/apple-signing/development'
    profile = plistlib.loads(subprocess.check_output(['security', 'cms', '-D', '-i',
        str(profiles / 'Maestro_Development.mobileprovision')]))
    if profile['ExpirationDate'] <= datetime.datetime.now(datetime.timezone.utc).replace(tzinfo=None):
        raise ValueError('The Maestro development profile has expired')
    available = set(re.findall(r'\b[0-9A-F]{40}\b', subprocess.check_output(
        ['security', 'find-identity', '-v', '-p', 'codesigning'], text=True)))
    identity = next((hashlib.sha1(certificate).hexdigest().upper() for certificate in profile['DeveloperCertificates']
                     if hashlib.sha1(certificate).hexdigest().upper() in available), None)
    if identity is None:
        raise ValueError('The worker cannot access the Maestro development private key')
    path = runner / 'drivers/ios/WebDriverAgent/WebDriverAgent.xcodeproj/project.pbxproj'
    project = plistlib.loads(subprocess.check_output(['plutil', '-convert', 'xml1', '-o', '-', str(path)]))
    objects = project['objects']
    target = next(value for value in objects.values()
                  if value.get('isa') == 'PBXNativeTarget' and value.get('name') == 'WebDriverAgentRunner')
    for reference in objects[target['buildConfigurationList']]['buildConfigurations']:
        objects[reference]['buildSettings'].update({
            'CODE_SIGN_STYLE': 'Manual', 'CODE_SIGN_IDENTITY': identity,
            'DEVELOPMENT_TEAM': contract['team'], 'PRODUCT_BUNDLE_IDENTIFIER': contract['wdaBundleId'],
            'PROVISIONING_PROFILE_SPECIFIER': profile['UUID'],
        })
    value = plistlib.dumps(project)
    if path.read_bytes() != value:
        path.write_bytes(value)
    print('Worker development signing configured for the suite-owned iOS driver.', flush=True)


def main():
    original = json.loads(sys.argv[1])
    phase, file, *rest = sys.argv[2:]
    if phase == 'beforeSuite':
        contract = json.loads(Path(file).read_text())
        if contract.get('schema') != 1:
            raise ValueError('Unsupported device flow contract')
        root = workspace()
        tooling = root / 'reaktor/reaktor-cluster/tooling'
        sys.path.insert(0, str(tooling if tooling.is_dir() else root / 'tools'))
        from mobile_worker import release_local_connections
        release_local_connections([contract['platform']])
        if contract['platform'] == 'ios':
            prepare(contract)
    if original:
        return subprocess.run([*original, phase, file, *rest]).returncode
    return 0


if __name__ == '__main__':
    sys.exit(main())

import importlib.util
from pathlib import Path
import subprocess
import sys
from tempfile import TemporaryDirectory
import unittest

from cluster_locations import state_root, workspace_root


spec = importlib.util.spec_from_file_location('cluster_link_installer', Path(__file__).with_name('install-links.py'))
installer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(installer)


class ClusterLocationsTest(unittest.TestCase):
    def test_state_prefers_configured_roots_before_empty_recreated_directories(self):
        with TemporaryDirectory() as temporary:
            workspace = Path(temporary).resolve() / 'dev'
            legacy, relocated = workspace / '.remote-dev', workspace.parent / 'other/.remote-dev'
            self.assertEqual(legacy.resolve(), state_root(workspace))
            self.assertFalse(legacy.exists())
            relocated.mkdir(parents=True)
            self.assertEqual(relocated.resolve(), state_root(workspace))
            legacy.mkdir(parents=True)
            self.assertEqual(legacy.resolve(), state_root(workspace))
            (relocated / 'config.json').write_text('{}')
            self.assertEqual(relocated.resolve(), state_root(workspace))
            (legacy / 'config.json').mkdir()
            self.assertEqual(relocated.resolve(), state_root(workspace))
            (legacy / 'config.json').rmdir()
            (legacy / 'config.json').write_text('{}')
            self.assertEqual(legacy.resolve(), state_root(workspace))

    def test_bootstrap_cli_reports_state_and_requires_configuration_without_creating_it(self):
        with TemporaryDirectory() as temporary:
            workspace = Path(temporary).resolve() / 'dev'
            script = Path(__file__).with_name('cluster_locations.py')
            def cli(command):
                return subprocess.run([sys.executable, str(script), command, '--workspace', str(workspace)],
                                      capture_output=True, text=True)
            self.assertEqual(1, cli('configured').returncode)
            self.assertEqual(str(workspace / '.remote-dev'), cli('state-root').stdout.strip())
            self.assertFalse(workspace.exists())
            relocated = workspace.parent / 'other/.remote-dev'
            relocated.mkdir(parents=True)
            (relocated / 'config.json').write_text('{}')
            self.assertEqual(0, cli('configured').returncode)
            self.assertEqual('', cli('configured').stdout)
            self.assertEqual(str(relocated), cli('state-root').stdout.strip())

    def test_module_and_legacy_layouts_select_the_same_workspace(self):
        with TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            self.assertEqual(root, workspace_root(root / 'reaktor/reaktor-cluster/tooling/remote-dev'))
            self.assertEqual(root, workspace_root(root / 'tools/remote-dev'))

    def test_compatibility_link_uses_canonical_assets_without_changing_state_root(self):
        with TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            canonical = root / 'reaktor/reaktor-cluster/tooling/remote-dev'
            canonical.parent.mkdir(parents=True)
            canonical.touch()
            link = root / 'tools/remote-dev'
            link.parent.mkdir()
            link.symlink_to('../reaktor/reaktor-cluster/tooling/remote-dev')
            self.assertEqual(root, workspace_root(link))

    def test_installer_is_repeatable_and_checks_every_compatibility_link(self):
        with TemporaryDirectory() as temporary:
            root = Path(temporary)
            installer.install(root)
            installer.install(root)
            installer.install(root, check=True)
            for name in installer.ASSETS:
                self.assertTrue((root / 'tools' / name).is_symlink())

    def test_installer_preserves_existing_files_and_installs_nothing_on_conflict(self):
        with TemporaryDirectory() as temporary:
            root = Path(temporary)
            conflict = root / 'tools' / installer.ASSETS[-1]
            conflict.parent.mkdir()
            conflict.write_text('local source')
            with self.assertRaises(ValueError):
                installer.install(root)
            self.assertEqual('local source', conflict.read_text())
            self.assertEqual([conflict], list(conflict.parent.iterdir()))

    def test_installer_repairs_moved_compatibility_links_without_recreating_legacy_tools(self):
        with TemporaryDirectory() as temporary:
            workspace = Path(temporary).resolve() / 'dev'
            state = workspace.parent / 'other/.remote-dev'
            state.mkdir(parents=True)
            config = state / 'config.json'
            config.write_text('{"preserved": true}')
            tools = state.parent / 'tools'
            tools.mkdir()
            for name in installer.ASSETS:
                (tools / name).symlink_to('../reaktor/reaktor-cluster/tooling/' + name)
            installer.install(workspace)
            installer.install(workspace, check=True)
            self.assertFalse((workspace / 'tools').exists())
            self.assertEqual('{"preserved": true}', config.read_text())
            for name in installer.ASSETS:
                self.assertEqual(Path(installer.__file__).parent / name, (tools / name).resolve())


if __name__ == '__main__':
    unittest.main()

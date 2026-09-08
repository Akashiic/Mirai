"""Isolated integration tests. No real modpack is inspected or modified.

python -m unittest discover -s tests/diagnostics -p test_collect_mirai_logs.py -v
Set MIRAI_POWERSHELL to test another PowerShell executable.
"""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import threading
import unittest
import zipfile

SCRIPT = Path(__file__).resolve().parents[2] / 'scripts' / 'collect-mirai-logs.ps1'
SHELL = os.environ.get('MIRAI_POWERSHELL') or shutil.which('powershell.exe') or shutil.which('pwsh')


@unittest.skipUnless(SHELL, 'PowerShell is required for the isolated collector integration test')
class CollectorTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='mirai-collector-test-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.game = self.root / 'fixture game'
        self.out = self.root / 'output'
        self.contents = {
            'logs/latest.log': b'Minecraft log\r\n',
            'logs/akashic-mirai/latest.log': b'{"kind":"fixture"}\n',
            'logs/akashic-mirai/previous-1.log': b'previous one\n',
            'logs/akashic-mirai/previous-2.log': b'previous two\n',
            'logs/akashic-mirai/previous-7.log': b'older rotation\n',
        }
        for name, data in self.contents.items():
            path = self.game / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
        (self.game / 'logs/akashic-mirai/not-a-log.txt').write_text('must not be inventoried')
        (self.game / 'options.txt').write_text('must not be inventoried')

    def run_collector(self, game=None):
        return subprocess.run([SHELL, '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', str(SCRIPT),
                               '-GameDirectory', str(game or self.game), '-OutputDirectory', str(self.out)],
                              capture_output=True, text=True, timeout=60)

    def archive(self):
        paths = list(self.out.glob('*.zip'))
        self.assertEqual(len(paths), 1)
        return paths[0]

    def validate_success(self):
        with zipfile.ZipFile(self.archive()) as z:
            self.assertEqual(z.testzip(), None)
            self.assertEqual(set(z.namelist()), set(self.contents) | {'manifest.json'})
            self.assertEqual(len(z.namelist()), len(set(z.namelist())))
            manifest = json.loads(z.read('manifest.json'))
            self.assertTrue(manifest['complete'])
            self.assertFalse(manifest['atomic_snapshot'])
            self.assertFalse(manifest['source_set_changed'])
            self.assertEqual(manifest['errors'], [])
            for entry in manifest['files']:
                data = z.read(entry['path'])
                self.assertEqual(data, self.contents[entry['path']])
                self.assertTrue(entry['copied'])
                self.assertFalse(entry['altered_during_copy'])
                self.assertEqual(entry['sha256'], hashlib.sha256(data).hexdigest())
                self.assertEqual(entry['copied_bytes'], len(data))
                self.assertEqual(entry['source_before'], entry['source_after'])

    def test_all_rotations_paths_hashes_and_unchanged_sources(self):
        before = {name: ((self.game/name).read_bytes(), (self.game/name).stat().st_mtime_ns) for name in self.contents}
        result = self.run_collector()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.validate_success()
        after = {name: ((self.game/name).read_bytes(), (self.game/name).stat().st_mtime_ns) for name in self.contents}
        self.assertEqual(before, after)

    def test_open_writer_can_be_copied_with_sharing(self):
        with (self.game/'logs/akashic-mirai/latest.log').open('ab') as writer:
            writer.flush()
            result = self.run_collector()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.validate_success()

    def test_concurrent_append_is_flagged_and_copied_bytes_are_hashed(self):
        path = self.game/'logs/latest.log'
        original = os.urandom(8 * 1024 * 1024)
        path.write_bytes(original)
        stop = threading.Event()
        def append():
            with path.open('ab', buffering=0) as writer:
                while not stop.is_set():
                    writer.write(b'append fixture\n' * 128)
                    stop.wait(.001)
        writer = threading.Thread(target=append)
        writer.start()
        try:
            result = self.run_collector()
        finally:
            stop.set(); writer.join(timeout=10)
        self.assertFalse(writer.is_alive())
        self.assertEqual(result.returncode, 0, result.stderr)
        with zipfile.ZipFile(self.archive()) as z:
            manifest = json.loads(z.read('manifest.json'))
            entry = next(e for e in manifest['files'] if e['path']=='logs/latest.log')
            copied = z.read(entry['path'])
            self.assertTrue(manifest['complete'])
            self.assertFalse(manifest['atomic_snapshot'])
            self.assertTrue(entry['altered_during_copy'])
            self.assertEqual(len(copied), entry['handle_length_at_open'])
            self.assertEqual(copied, path.read_bytes()[:len(copied)])
            self.assertEqual(entry['sha256'], hashlib.sha256(copied).hexdigest())

    def test_empty_log_hash_is_valid(self):
        name = 'logs/akashic-mirai/latest.log'
        self.contents[name] = b''
        (self.game/name).write_bytes(b'')
        result = self.run_collector()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.validate_success()

    def test_new_output_never_replaces_an_existing_archive(self):
        first = self.run_collector()
        self.assertEqual(first.returncode, 0, first.stderr)
        path = self.archive(); original = path.read_bytes()
        second = self.run_collector()
        self.assertEqual(second.returncode, 0, second.stderr)
        self.assertEqual(len(list(self.out.glob('*.zip'))), 2)
        self.assertEqual(path.read_bytes(), original)

    def test_missing_game_fails_without_success_archive(self):
        result = self.run_collector(self.root/'absent')
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(list(self.out.glob('*.zip')), [])

    def test_missing_minecraft_log_fails(self):
        (self.game/'logs/latest.log').unlink()
        result = self.run_collector()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('logs/latest.log', result.stderr)
        self.assertEqual(list(self.out.glob('*.zip')), [])

    def test_missing_mirai_sources_fails(self):
        for name in self.contents:
            if name.startswith('logs/akashic-mirai/'):
                (self.game/name).unlink()
        result = self.run_collector()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('No .log files', result.stderr)
        self.assertEqual(list(self.out.glob('*.zip')), [])

    @unittest.skipUnless(os.name == 'nt', 'Windows junction tests')
    def test_junctions_in_each_source_directory_are_rejected(self):
        def junction(link, target):
            quote = lambda path: "'" + str(path).replace("'", "''") + "'"
            command = 'New-Item -ItemType Junction -Path ' + quote(link) + ' -Target ' + quote(target) + ' -ErrorAction Stop | Out-Null'
            result = subprocess.run([SHELL, '-NoProfile', '-NonInteractive', '-Command', command], capture_output=True, text=True, timeout=30)
            self.assertEqual(result.returncode, 0, result.stderr)
        link = self.root/'linked game'
        junction(link, self.game)
        try:
            result = self.run_collector(link)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn('reparse points', result.stderr)
        finally:
            link.rmdir()  # Remove the junction itself, never its target tree.
        for relative in ['logs', 'logs/akashic-mirai']:
            with self.subTest(component=relative):
                path = self.game/relative; backup = self.root/'fixture-backup'
                path.rename(backup)
                try:
                    junction(path, backup)
                    result = self.run_collector()
                    self.assertNotEqual(result.returncode, 0)
                    self.assertIn('reparse points', result.stderr)
                    self.assertEqual(list(self.out.glob('*.zip')), [])
                finally:
                    if path.exists(): path.rmdir()
                    backup.rename(path)
        for name, data in self.contents.items():
            self.assertEqual((self.game/name).read_bytes(), data)

    @unittest.skipUnless(os.name == 'nt', 'Windows file symlink test')
    def test_file_symlink_is_rejected_when_supported(self):
        target = self.root/'outside-fixture.log'
        target.write_bytes(b'must not be copied')
        path = self.game/'logs/akashic-mirai/previous-2.log'
        path.unlink()
        try:
            path.symlink_to(target)
        except OSError as error:
            if getattr(error, 'winerror', None) == 1314:
                self.skipTest('Windows symlink privilege/developer mode unavailable')
            raise
        result = self.run_collector()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('reparse points', result.stderr)
        self.assertEqual(list(self.out.glob('*.zip')), [])
        self.assertEqual(target.read_bytes(), b'must not be copied')

    @unittest.skipUnless(os.name == 'nt', 'Exclusive Windows sharing test')
    def test_exclusively_locked_source_fails_and_marks_partial_archive(self):
        import ctypes
        from ctypes import wintypes
        kernel = ctypes.WinDLL('kernel32', use_last_error=True)
        kernel.CreateFileW.argtypes = [wintypes.LPCWSTR, wintypes.DWORD, wintypes.DWORD, wintypes.LPVOID,
                                      wintypes.DWORD, wintypes.DWORD, wintypes.HANDLE]
        kernel.CreateFileW.restype = wintypes.HANDLE
        kernel.CloseHandle.argtypes = [wintypes.HANDLE]
        locked = 'logs/akashic-mirai/previous-2.log'
        handle = kernel.CreateFileW(str(self.game/locked), 0x80000000, 0, None, 3, 0, None)
        self.assertNotEqual(handle, ctypes.c_void_p(-1).value)
        try:
            result = self.run_collector()
        finally:
            kernel.CloseHandle(handle)
        self.assertNotEqual(result.returncode, 0)
        with zipfile.ZipFile(self.archive()) as z:
            manifest = json.loads(z.read('manifest.json'))
            self.assertFalse(manifest['complete'])
            failed = [entry for entry in manifest['files'] if not entry['copied']]
            self.assertEqual([entry['path'] for entry in failed], [locked])
            self.assertTrue(manifest['errors'])
            self.assertNotIn(locked, z.namelist())


if __name__ == '__main__':
    unittest.main()

"""Offline integration tests for the actual provisioning shell script.
Runs in disposable build directories; mocks only Android id/am commands.
"""
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
BASH = os.environ.get('REAR_TEST_BASH') or shutil.which('bash')
if os.name == 'nt' and Path('C:/Program Files/Git/bin/bash.exe').exists():
    BASH = 'C:/Program Files/Git/bin/bash.exe'

def posix(path):
    value = str(path.resolve()).replace('\\', '/')
    return '/' + value[0].lower() + value[2:] if os.name == 'nt' else value

def quote(value):
    return "'" + value.replace("'", "'\"'\"'") + "'"

@unittest.skipUnless(BASH, 'bash is required')
class InstallTests(unittest.TestCase):
    def setUp(self):
        (ROOT / 'build').mkdir(exist_ok=True)
        self.tmp = tempfile.TemporaryDirectory(prefix='setup-test-', dir=ROOT / 'build')
        self.root = Path(self.tmp.name)
        self.stage = self.root / 'stage with spaces'
        self.stage.mkdir()
        self.theme = self.root / "theme's files"
        self.identity = '0123456789abcdef0123456789abcdef'
        self.target = self.theme / 'RearDaily' / self.identity
        self.bin = self.root / 'bin'
        self.bin.mkdir()
        for name, script in {
            'id': '#!/bin/sh\necho 2000\n',
            'am': '#!/bin/sh\nif [ "$1" = get-current-user ]; then echo 0; fi\n',
        }.items():
            (self.bin / name).write_text(script, encoding='utf8', newline='\n')
            (self.bin / name).chmod(0o755)
        self.prepare()

    def tearDown(self):
        self.tmp.cleanup()

    def prepare(self, template=b'PK-test-template-v1'):
        for name, data in {'rearscreen': template, 'description.xml': b'<theme/>',
                           'resource.mrm': b'{"resSubType":"ai"}', 'preview.png': b'PNG-test'}.items():
            (self.stage / name).write_bytes(data)
        checksums = ''.join(hashlib.sha256((self.stage / n).read_bytes()).hexdigest() + '  ' + n + '\n'
                            for n in ['rearscreen', 'description.xml', 'resource.mrm', 'preview.png'])
        (self.stage / 'checksums').write_text(checksums, encoding='utf8', newline='\n')
        text = (ROOT / 'assets/setup/install.sh').read_text(encoding='utf8')
        for key, value in {'@@APP@@': quote('com.reardaily.app'), '@@ID@@': quote(self.identity),
                           '@@FILES@@': quote(posix(self.theme)), '@@USER@@': '0',
                           '@@HASH@@': quote(hashlib.sha256(template).hexdigest())}.items():
            text = text.replace(key, value)
        (self.stage / 'install.sh').write_text(text, encoding='utf8', newline='\n')
        (self.stage / 'ready').write_text(self.identity, encoding='utf8')

    def run_script(self, *args, success=True, no_launch=False):
        command = 'export PATH=' + quote(posix(self.bin)) + ':"$PATH"\nexec sh ' + quote(posix(self.stage / 'install.sh'))
        command += ''.join(' ' + quote(arg) for arg in args)
        env = dict(os.environ)
        if no_launch: env['REAR_SETUP_NO_LAUNCH'] = '1'
        result = subprocess.run([BASH, '-c', command], capture_output=True, encoding='utf8', timeout=20, env=env)
        self.assertEqual(result.returncode == 0, success, result.stdout + result.stderr)
        return result

    def candidate(self, name='chosen'):
        path = self.theme / 'MIUI/.ai_wallpaper/maml' / name
        path.mkdir(parents=True)
        (path / 'rearscreen').write_bytes(b'PK-original-' + name.encode())
        (path / 'description.xml').write_bytes(b'original-description')
        return path

    def test_install_is_independent_and_repeatable(self):
        other = self.candidate()
        self.run_script()
        original_time = (self.target / 'rearscreen').stat().st_mtime_ns
        self.run_script()
        self.assertEqual((self.target / 'rearscreen').read_bytes(), (self.stage / 'rearscreen').read_bytes())
        self.assertEqual((self.target / 'rearscreen').stat().st_mtime_ns, original_time)
        self.assertEqual((other / 'rearscreen').read_bytes(), b'PK-original-chosen')
        self.assertFalse(list(self.target.parent.glob('*.backup.*')))
        self.assertEqual((self.stage / 'installed').read_text().splitlines()[2], posix(self.target))
        self.assertFalse((self.stage / '.install-lock').exists())

    def test_shizuku_installs_without_starting_an_activity(self):
        marker = self.root / 'activity-started'
        wrapper = '#!/bin/sh\nif [ "$1" = get-current-user ]; then echo 0; else echo launch > ' + quote(posix(marker)) + '; fi\n'
        (self.bin / 'am').write_text(wrapper, encoding='utf8', newline='\n')
        (self.bin / 'am').chmod(0o755)
        self.run_script(no_launch=True)
        self.assertTrue((self.stage / 'installed').exists())
        self.assertFalse(marker.exists())
        self.run_script()
        self.assertTrue(marker.exists(), 'ADB fallback still launches the app')

    def test_app_identity_cannot_run_installer(self):
        (self.bin / 'id').write_text('#!/bin/sh\necho 10399\n', encoding='utf8', newline='\n')
        result = self.run_script(no_launch=True, success=False)
        self.assertIn('Shizuku', result.stderr)
        self.assertFalse(self.target.exists())

    def test_wrong_android_user_cannot_install(self):
        (self.bin / 'am').write_text('#!/bin/sh\necho 10\n', encoding='utf8', newline='\n')
        self.run_script(no_launch=True, success=False)
        self.assertFalse(self.target.exists())

    def test_upgrade_keeps_previous_owned_version(self):
        self.run_script()
        self.prepare(b'PK-upgrade')
        self.run_script()
        backups = list(self.target.parent.glob('*.backup.*'))
        self.assertEqual(len(backups), 1)
        self.assertEqual((backups[0] / 'rearscreen').read_bytes(), b'PK-test-template-v1')
        self.assertEqual((self.target / 'rearscreen').read_bytes(), b'PK-upgrade')

    def test_checksum_failure_changes_no_template(self):
        self.run_script()
        (self.stage / 'rearscreen').write_bytes(b'corrupt')
        self.run_script(success=False)
        self.assertEqual((self.target / 'rearscreen').read_bytes(), b'PK-test-template-v1')
        self.assertFalse((self.stage / '.install-lock').exists())

    def test_foreign_directory_is_never_overwritten(self):
        self.target.mkdir(parents=True)
        (self.target / 'rearscreen').write_bytes(b'foreign')
        self.run_script(success=False)
        self.assertEqual((self.target / 'rearscreen').read_bytes(), b'foreign')

    def test_unprepared_or_busy_stage_is_rejected(self):
        (self.stage / 'ready').unlink()
        self.run_script(success=False)
        self.assertFalse(self.target.exists())
        self.prepare()
        (self.stage / '.install-lock').mkdir()
        self.run_script(success=False)
        self.assertTrue((self.stage / '.install-lock').exists())
        self.assertFalse(self.target.exists())

    def test_manual_requires_explicit_choice_and_restores_original(self):
        chosen, other = self.candidate('chosen'), self.candidate('other')
        output = self.run_script('list').stdout
        self.assertIn('/chosen', output)
        self.assertIn('/other', output)
        self.run_script('manual', 'chosen')
        self.run_script('manual', 'chosen')
        self.assertEqual((chosen / 'rearscreen.reardaily-original').read_bytes(), b'PK-original-chosen')
        self.assertEqual((other / 'rearscreen').read_bytes(), b'PK-original-other')
        self.assertEqual((chosen / 'description.xml').read_bytes(), b'original-description')
        self.run_script('restore', 'chosen')
        self.assertEqual((chosen / 'rearscreen').read_bytes(), b'PK-original-chosen')
        self.assertFalse((self.stage / 'installed').exists())

    def test_invalid_manual_paths_are_rejected(self):
        for value in ['../chosen', '/tmp', "x'; touch owned", '', 'a' * 129]:
            self.run_script('manual', value, success=False)
        self.run_script('manual', 'missing', success=False)
        self.run_script('restore', 'missing', success=False)

    def test_failed_commit_rolls_back_existing_installation(self):
        self.run_script()
        self.prepare(b'PK-upgrade')
        wrapper = '#!/bin/sh\ncase "$1" in */.rd-*) exit 19;; esac\nexec /usr/bin/mv "$@"\n'
        (self.bin / 'mv').write_text(wrapper, encoding='utf8', newline='\n')
        (self.bin / 'mv').chmod(0o755)
        self.run_script(success=False)
        self.assertEqual((self.target / 'rearscreen').read_bytes(), b'PK-test-template-v1')
        self.assertFalse(list(self.target.parent.glob('.rd-*')))
        self.assertFalse((self.stage / '.install-lock').exists())

    def test_damaged_backup_is_not_committed(self):
        chosen = self.candidate()
        wrapper = '#!/bin/sh\ncase "$2" in *.pending) echo damaged > "$2"; exit 12;; esac\nexec /usr/bin/cp "$@"\n'
        (self.bin / 'cp').write_text(wrapper, encoding='utf8', newline='\n')
        (self.bin / 'cp').chmod(0o755)
        self.run_script('manual', 'chosen', success=False)
        self.assertFalse((chosen / 'rearscreen.reardaily-original').exists())
        self.assertEqual((chosen / 'rearscreen').read_bytes(), b'PK-original-chosen')

if __name__ == '__main__':
    unittest.main(verbosity=2)

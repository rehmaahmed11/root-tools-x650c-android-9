"""Host-side Android command doubles. Never calls real root or Android services."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
BASE_USERS = 'Users:\n\tUserInfo{0:Owner:13} running\n\tUserInfo{10:Guest:104}\n\tUserInfo{11:Personal:10}\n\tUserInfo{12:Work:30}\n'

class BackendTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.mod = self.root / 'module'
        shutil.copytree(ROOT / 'module', self.mod)
        script = self.mod / 'bin/guestctl.sh'
        script.write_text(script.read_text().replace('LOCK=/dev/.guestguard-operation', f'LOCK={self.root}/lock').replace('BB=/data/adb/magisk/busybox', f'BB={self.root}/fake/busybox'))
        self.script = script
        self.fake = self.root / 'fake'
        self.fake.mkdir()
        self.log = self.root / 'commands'
        self.env = dict(os.environ, PATH=f'{self.fake}:/usr/bin:/bin', FAKE_LOG=str(self.log), FAKE_USERS=BASE_USERS,
                        FAKE_CURRENT='0', FAKE_BOOT='1', FAKE_UID='0', FAKE_MAX='Maximum supported users: 4',
                        FAKE_LIST_CODE='0', FAKE_CHANGE_CODE='0')
        tools = {
            'busybox': 'exec "$@"',
            'id': 'printf "%s\\n" "$FAKE_UID"',
            'getprop': '''case "$1" in
              sys.boot_completed) echo "$FAKE_BOOT";; fw.max_users) echo 4;; ro.build.version.release) echo 9;; esac''',
            'am': '''if [ "$1" = get-current-user ]; then echo "$FAKE_CURRENT"; exit "${FAKE_CURRENT_CODE:-0}"; fi
              echo "am $*" >> "$FAKE_LOG"; printf '%s' "${FAKE_SWITCH_OUTPUT:-}"; exit "$FAKE_CHANGE_CODE"''',
            'pm': '''case "$1" in
              list) printf '%s' "$FAKE_USERS"; exit "$FAKE_LIST_CODE";;
              get-max-users) echo "$FAKE_MAX"; exit "${FAKE_MAX_CODE:-0}";;
              *) echo "pm $*" >> "$FAKE_LOG";; esac
              case "$1" in
              create-user) echo "${FAKE_CREATE_OUTPUT:-Success: created user id 15}";;
              remove-user) echo "${FAKE_REMOVE_OUTPUT:-Success: removed user}";;
              install) echo "${FAKE_INSTALL_OUTPUT:-Success}";;
              path) echo 'package:/data/app/guestguard/base.apk';;
              esac
              exit "$FAKE_CHANGE_CODE"'''
        }
        for name, body in tools.items():
            p = self.fake / name
            p.write_text('#!/bin/sh\n' + body + '\n')
            p.chmod(0o755)

    def run_cmd(self, *args):
        return subprocess.run(['sh', str(self.script), *args], env=self.env, text=True, capture_output=True, timeout=5)

    def blocked(self, *args):
        result = self.run_cmd(*args)
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertFalse(self.log.exists(), self.log.read_text() if self.log.exists() else '')
        self.assertIn('ERROR:', result.stderr)
        return result

    def test_status_counts_and_types(self):
        r = self.run_cmd('status')
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn('CURRENT\t0', r.stdout)
        self.assertIn('MAX\t4', r.stdout)
        self.assertIn('USER\t0\towner\t13\tOwner', r.stdout)
        self.assertIn('USER\t10\tguest\t104\tGuest', r.stdout)
        self.assertIn('USER\t11\tprotected', r.stdout)
        self.assertIn('USER\t12\tprotected', r.stdout)

    def test_owner_never_deleted_even_with_guest_flag(self):
        self.env['FAKE_USERS'] = 'Users:\nUserInfo{0:Owner:4}\n'
        self.blocked('delete-guest', '0', 'DELETE-GUEST-0')

    def test_regular_and_work_users_never_deleted(self):
        for user in ['11', '12']:
            self.blocked('delete-guest', user, 'DELETE-GUEST-' + user)

    def test_primary_admin_restricted_managed_system_profile_main_protected(self):
        for flag in [1, 2, 8, 32, 2048, 4096, 16384]:
            self.env['FAKE_USERS'] = f'Users:\nUserInfo{{0:Owner:13}}\nUserInfo{{10:Fake guest:{flag | 4:x}}}\n'
            self.blocked('delete-guest', '10', 'DELETE-GUEST-10')

    def test_active_guest_never_deleted(self):
        self.env['FAKE_CURRENT'] = '10'
        self.blocked('delete-guest', '10', 'DELETE-GUEST-10')

    def test_inactive_guest_deleted(self):
        r = self.run_cmd('delete-guest', '10', 'DELETE-GUEST-10')
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(self.log.read_text().strip(), 'pm remove-user 10')
        self.assertFalse((self.root / 'lock').exists())

    def test_confirmation_required(self):
        self.blocked('delete-guest', '10', 'yes')
        self.blocked('delete-guest', '10')

    def test_bad_ids_and_injection_blocked(self):
        for user in ['', '-1', '00', '010', '0;reboot', '9999999', '10 11', '1e1', 'no']:
            self.blocked('delete-guest', user, 'DELETE-GUEST-' + user)

    def test_missing_user_blocked(self): self.blocked('delete-guest', '19', 'DELETE-GUEST-19')

    def test_missing_owner_and_bad_records_block_all_changes(self):
        for listing in ['Error: denied', 'Users:\nUserInfo{10:Guest:4}', 'UserInfo{0:Owner:13}\nUserInfo{oops}',
                        'UserInfo{0:Owner:13}\nUserInfo{10:Guest:ZZ}', 'UserInfo{0:Owner:13}\nUserInfo{0:Duplicate:13}',
                        'UserInfo{0:Owner:13}\nUserInfo{010:Guest:4}', 'UserInfo{0:Owner:13}\nUserInfo{10:Guest:1234567}']:
            self.env['FAKE_USERS'] = listing
            self.blocked('delete-guest', '10', 'DELETE-GUEST-10')
            self.blocked('create-guest', 'Guest')

    def test_list_failure_even_with_valid_output(self):
        self.env['FAKE_LIST_CODE'] = '1'
        self.blocked('delete-guest', '10', 'DELETE-GUEST-10')

    def test_names_are_display_data(self):
        self.env['FAKE_USERS'] = 'UserInfo{0:Owner:13}\nUserInfo{10:Guest:with:colons; $(reboot):4}\n'
        r = self.run_cmd('status')
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn('Guest:with:colons; $(reboot)', r.stdout)
        self.assertFalse(self.log.exists())

    def test_create_guest_only(self):
        r = self.run_cmd('create-guest', 'Weekend Guest')
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(self.log.read_text().strip(), 'pm create-user --guest Weekend Guest')

    def test_create_rejects_unsafe_names(self):
        for name in ['', '-Guest', 'x' * 33, 'Guest\nName', 'Guest;reboot', '$(reboot)', "Guest'", 'نام']:
            self.blocked('create-guest', name)

    def test_create_requires_owner(self):
        self.env['FAKE_CURRENT'] = '10'
        self.blocked('create-guest', 'Guest')

    def test_guest_quota_failure_never_falls_back(self):
        self.env['FAKE_CREATE_OUTPUT'] = 'Error: maximum number of guests reached'
        r = self.run_cmd('create-guest', 'Guest')
        self.assertNotEqual(r.returncode, 0)
        self.assertEqual(len(self.log.read_text().splitlines()), 1)
        self.assertIn('--guest', self.log.read_text())

    def test_delete_zero_exit_error_not_success(self):
        self.env['FAKE_REMOVE_OUTPUT'] = 'Error: could not remove user'
        self.assertNotEqual(self.run_cmd('delete-guest', '10', 'DELETE-GUEST-10').returncode, 0)
        self.assertEqual(len(self.log.read_text().splitlines()), 1)

    def test_nonzero_exit_even_with_success_text(self):
        self.env['FAKE_CHANGE_CODE'] = '1'
        self.assertNotEqual(self.run_cmd('delete-guest', '10', 'DELETE-GUEST-10').returncode, 0)

    def test_switch_full_users_but_not_profiles(self):
        self.blocked('switch', '12')
        self.blocked('switch', '0')
        r = self.run_cmd('switch', '10')
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(self.log.read_text().strip(), 'am switch-user 10')

    def test_regular_full_user_can_switch_but_not_delete(self):
        self.blocked('delete-guest', '11', 'DELETE-GUEST-11')
        self.assertEqual(self.run_cmd('switch', '11').returncode, 0)
        self.assertEqual(self.log.read_text().strip(), 'am switch-user 11')

    def test_switch_back_to_owner(self):
        self.env['FAKE_CURRENT'] = '10'
        self.assertEqual(self.run_cmd('switch', '0').returncode, 0)

    def test_disabled_guest_cannot_switch(self):
        self.env['FAKE_USERS'] = 'UserInfo{0:Owner:13}\nUserInfo{10:Guest:44}'
        self.blocked('switch', '10')

    def test_switch_zero_exit_error_not_success(self):
        self.env['FAKE_SWITCH_OUTPUT'] = 'Error: user switch disallowed'
        self.assertNotEqual(self.run_cmd('switch', '10').returncode, 0)

    def test_unknown_max_not_unlimited(self):
        for value in ['Unknown command: get-max-users', 'Maximum supported users: 0', 'unlimited', 'Maximum supported users: 99999999999999']:
            self.env['FAKE_MAX'] = value
            r = self.run_cmd('status')
            self.assertEqual(r.returncode, 0, r.stderr)
            self.assertIn('MAX\tunknown\n', r.stdout)

    def test_failed_max_command_ignores_misleading_output(self):
        self.env['FAKE_MAX_CODE'] = '1'
        self.assertIn('MAX\tunknown\n', self.run_cmd('status').stdout)

    def test_magisk_busybox_required(self):
        (self.fake / 'busybox').unlink()
        self.blocked('create-guest', 'Guest')

    def test_bad_current_fails_closed(self):
        for value in ['', 'Error', '00', '-1']:
            self.env['FAKE_CURRENT'] = value
            self.blocked('delete-guest', '10', 'DELETE-GUEST-10')

    def test_current_command_failure_fails_closed(self):
        self.env['FAKE_CURRENT_CODE'] = '1'
        self.blocked('delete-guest', '10', 'DELETE-GUEST-10')

    def test_root_denial_and_boot_not_ready(self):
        self.env['FAKE_UID'] = '2000'
        self.blocked('status')
        self.env['FAKE_UID'] = '0'
        self.env['FAKE_BOOT'] = '0'
        self.blocked('create-guest', 'Guest')

    def test_disabled_or_removed_module_blocks(self):
        for name in ['disable', 'remove']:
            (self.mod / name).touch()
            self.blocked('status')
            (self.mod / name).unlink()

    def test_overlapping_operations_blocked(self):
        (self.root / 'lock').mkdir()
        self.blocked('create-guest', 'Guest')
        self.assertEqual(self.run_cmd('status').returncode, 0)

    def test_unknown_commands_and_extra_arguments_blocked(self):
        for args in [('delete-user', '0'), ('shell', 'reboot'), ('status', 'extra'), ('create-guest', 'Guest', 'extra'), ()]:
            self.blocked(*args)

    def test_action_only_launches_owner_app(self):
        r = self.run_cmd('launch')
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn('am start --user 0 -n dev.guestguard/.MainActivity', self.log.read_text())
        self.assertNotIn('remove-user', self.log.read_text())

    def test_install_failure_does_not_uninstall(self):
        (self.mod / 'companion.apk').write_bytes(b'test')
        self.env['FAKE_INSTALL_OUTPUT'] = 'Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]'
        r = self.run_cmd('install')
        self.assertNotEqual(r.returncode, 0)
        self.assertNotIn('uninstall', self.log.read_text())
        self.assertEqual(len(self.log.read_text().splitlines()), 1)

if __name__ == '__main__': unittest.main(verbosity=2)

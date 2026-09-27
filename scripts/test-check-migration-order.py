#!/usr/bin/env python3
"""Tests for check-migration-order.py. Run: python3 scripts/test-check-migration-order.py

Stdlib unittest, no dependencies. The version-parsing tests drive the pure functions; the branch
tests build a throwaway git repository with a synthetic "main" and run the real script against it
as a subprocess, so the git plumbing (merge base, rename detection, exit codes) is exercised too --
a guard whose diff silently finds nothing would pass every PR and look identical to a clean one.
"""

import importlib.util
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parent / "check-migration-order.py"
_spec = importlib.util.spec_from_file_location("check_migration_order", SCRIPT)
cmo = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(cmo)

MIGRATIONS = cmo.MIGRATION_DIR


class VersionParsing(unittest.TestCase):

    def test_sub_version_sits_between_its_integer_and_the_next(self):
        self.assertLess(cmo.parse_version("V236__a.sql"), cmo.parse_version("V236_1__a.sql"))
        self.assertLess(cmo.parse_version("V236_1__a.sql"), cmo.parse_version("V237__a.sql"))

    def test_parts_compare_numerically_not_lexically(self):
        self.assertLess(cmo.parse_version("V99__a.sql"), cmo.parse_version("V100__a.sql"))
        self.assertLess(cmo.parse_version("V236_2__a.sql"), cmo.parse_version("V236_10__a.sql"))

    def test_underscore_and_dot_are_the_same_separator(self):
        self.assertEqual(cmo.parse_version("V236_1__a.sql"), cmo.parse_version("V236.1__a.sql"))

    def test_trailing_zero_parts_are_insignificant(self):
        self.assertEqual(cmo.parse_version("V236_0__a.sql"), cmo.parse_version("V236__a.sql"))

    def test_leading_zeros_do_not_change_the_version(self):
        self.assertEqual(cmo.parse_version("V010__a.sql"), cmo.parse_version("V10__a.sql"))

    def test_non_versioned_files_are_ignored(self):
        for name in ("R__view.sql", "V236_a.sql", "V236__a.txt", "README.md", "afterMigrate.sql"):
            self.assertIsNone(cmo.parse_version(name), name)

    def test_rename_suggestions(self):
        self.assertEqual(cmo.rename_suggestions((236,)), ("V237", "V236_1"))
        self.assertEqual(cmo.rename_suggestions((236, 1)), ("V237", "V236_2"))


class Violations(unittest.TestCase):
    """The four cases the guard exists to separate, against a main whose highest version is 236."""

    BASE = [f"{MIGRATIONS}/V235__x.sql", f"{MIGRATIONS}/V236__y.sql"]

    def check(self, added):
        top, bad = cmo.violations(self.BASE, [f"{MIGRATIONS}/{added}"])
        self.assertEqual(top, ((236,), "V236__y.sql"))
        return bad

    def test_lower_version_fails(self):
        self.assertEqual(len(self.check("V234__new.sql")), 1)

    def test_equal_version_fails(self):
        self.assertEqual(len(self.check("V236__new.sql")), 1)

    def test_equal_version_with_trailing_zero_fails(self):
        self.assertEqual(len(self.check("V236_0__new.sql")), 1)

    def test_higher_version_passes(self):
        self.assertEqual(self.check("V237__new.sql"), [])

    def test_sub_version_above_max_passes(self):
        self.assertEqual(self.check("V236_1__new.sql"), [])

    def test_empty_base_reports_no_floor(self):
        self.assertEqual(cmo.violations([], [f"{MIGRATIONS}/V1__a.sql"]), (None, []))


class Branches(unittest.TestCase):
    """End to end against a real git repository: main at V236, one branch per case."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.repo = Path(self._tmp.name)
        self.git("init", "-q", "-b", "main")
        self.write("V235__inflow.sql", "select 235;")
        self.write("V236__refresh.sql", "select 236;")
        self.commit("main at 236")

    def tearDown(self):
        self._tmp.cleanup()

    def git(self, *args):
        return subprocess.run(
            ["git", "-c", "user.name=t", "-c", "user.email=t@example.invalid",
             "-c", "commit.gpgsign=false", "-c", "core.hooksPath=/dev/null", *args],
            cwd=self.repo, check=True, capture_output=True, text=True).stdout

    def write(self, name, body):
        path = self.repo / MIGRATIONS / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(body + "\n")

    def commit(self, message):
        self.git("add", "-A")
        self.git("commit", "-q", "-m", message)

    def branch(self, name, start="main"):
        self.git("checkout", "-q", "-b", name, start)

    def run_check(self, base="main", head="HEAD"):
        return subprocess.run([sys.executable, str(SCRIPT), "--base", base, "--head", head],
                              cwd=self.repo, capture_output=True, text=True)

    def assert_fails_naming(self, result, filename, version):
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn(filename, result.stdout)
        self.assertIn(f"Flyway version {version}", result.stdout)
        self.assertIn("highest version 236 (V236__refresh.sql)", result.stdout)
        self.assertIn("Rename it to V237__", result.stdout)
        self.assertIn("V236_1__", result.stdout)

    def test_lower_version_fails(self):
        self.branch("lower")
        self.write("V234__late.sql", "select 234;")
        self.commit("add 234")
        self.assert_fails_naming(self.run_check(), "V234__late.sql", "234")

    def test_equal_version_fails(self):
        self.branch("equal")
        self.write("V236__collides.sql", "select 'other 236';")
        self.commit("add another 236")
        result = self.run_check()
        self.assert_fails_naming(result, "V236__collides.sql", "236")
        self.assertIn("equal to", result.stdout)

    def test_higher_version_passes(self):
        self.branch("higher")
        self.write("V237__next.sql", "select 237;")
        self.commit("add 237")
        result = self.run_check()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("1 added migration(s)", result.stdout)

    def test_sub_version_passes(self):
        self.branch("sub")
        self.write("V236_1__hotfix.sql", "select 236.1;")
        self.commit("add 236_1")
        self.assertEqual(self.run_check().returncode, 0)

    def test_branch_that_was_fine_when_opened_fails_once_main_moves(self):
        """The 2026-09-27 incident: V235 opened while main was at 234, V236 then merged first."""
        self.branch("old-main")
        self.git("rm", "-q", f"{MIGRATIONS}/V235__inflow.sql", f"{MIGRATIONS}/V236__refresh.sql")
        self.write("V234__before.sql", "select 234;")
        self.commit("main at 234")
        self.branch("feature-235", "old-main")
        self.write("V235__inflow.sql", "select 235;")
        self.commit("add 235")
        self.assertEqual(self.run_check(base="old-main").returncode, 0)
        self.git("checkout", "-q", "old-main")
        self.write("V236__refresh.sql", "select 236;")
        self.commit("236 merges first")
        self.git("checkout", "-q", "feature-235")
        result = self.run_check(base="old-main")
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn("V235__inflow.sql has Flyway version 235", result.stdout)

    def test_rename_of_unapplied_file_above_max_passes(self):
        """#1818's shape: V235 renamed to V236_1 is judged by its new name, and passes."""
        self.branch("rename-up")
        self.git("mv", f"{MIGRATIONS}/V235__inflow.sql", f"{MIGRATIONS}/V236_1__inflow.sql")
        self.commit("rename 235 -> 236_1")
        result = self.run_check()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("1 added migration(s)", result.stdout)

    def test_rename_to_a_version_at_or_below_max_fails(self):
        self.branch("rename-down")
        self.git("mv", f"{MIGRATIONS}/V235__inflow.sql", f"{MIGRATIONS}/V234_5__inflow.sql")
        self.commit("rename 235 -> 234_5")
        self.assert_fails_naming(self.run_check(), "V234_5__inflow.sql", "234.5")

    def test_modifying_an_existing_migration_is_not_an_add(self):
        self.branch("modify")
        self.write("V235__inflow.sql", "select 'edited';")
        self.commit("edit 235")
        self.assertEqual(self.run_check().returncode, 0)

    def test_branch_cut_before_main_renamed_a_file_is_not_blamed_for_the_old_name(self):
        """Tree comparison would see V235 on the branch and not on main; the merge-base diff does not."""
        self.branch("unrelated")
        (self.repo / "other.txt").write_text("x\n")
        self.commit("unrelated change")
        self.git("checkout", "-q", "main")
        self.git("mv", f"{MIGRATIONS}/V235__inflow.sql", f"{MIGRATIONS}/V236_1__inflow.sql")
        self.commit("main renames 235 -> 236_1")
        self.git("checkout", "-q", "unrelated")
        self.assertEqual(self.run_check().returncode, 0)

    def test_every_offending_file_is_reported(self):
        self.branch("two")
        self.write("V233_1__a.sql", "select 1;")
        self.write("V236__b.sql", "select 2;")
        self.write("V238__c.sql", "select 3;")
        self.commit("three adds")
        result = self.run_check()
        self.assertEqual(result.returncode, 1)
        self.assertIn("V233_1__a.sql", result.stdout)
        self.assertIn("V236__b.sql", result.stdout)
        self.assertNotIn("V238__c.sql has", result.stdout)
        self.assertIn("2 migration(s) not above", result.stderr)

    def test_no_migration_changes_passes(self):
        self.branch("docs")
        (self.repo / "other.txt").write_text("x\n")
        self.commit("docs only")
        result = self.run_check()
        self.assertEqual(result.returncode, 0)
        self.assertIn("0 added migration(s)", result.stdout)

    def test_unresolvable_base_fails_closed(self):
        result = self.run_check(base="origin/does-not-exist")
        self.assertEqual(result.returncode, 2, result.stdout + result.stderr)

    def test_base_without_migrations_fails_closed(self):
        """A floor of nothing would pass everything; a moved directory must not read as clean."""
        self.branch("emptied")
        self.git("rm", "-rq", MIGRATIONS)
        self.commit("migrations moved away")
        self.branch("feat", "main")
        self.write("V300__a.sql", "select 1;")
        self.commit("add")
        result = self.run_check(base="emptied")
        self.assertEqual(result.returncode, 2, result.stdout + result.stderr)
        self.assertIn("refusing to pass vacuously", result.stderr)

if __name__ == "__main__":
    unittest.main()

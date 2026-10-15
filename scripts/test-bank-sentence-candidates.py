#!/usr/bin/env python3
"""Mechanism test for bank-sentence-candidates.py's in-repo refusal.
Run: python3 scripts/test-bank-sentence-candidates.py

Only the refusal is tested here: the probe itself needs the owner's statements and cannot reach CI
(its Java half is covered by BankSentenceCandidatesTest over synthetic PDFs).
"""

import importlib.util
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parent / "bank-sentence-candidates.py"
_s = importlib.util.spec_from_file_location("bsc", SCRIPT)
bsc = importlib.util.module_from_spec(_s)
_s.loader.exec_module(bsc)


class TheOutputAndTheCorpusMustBeOutsideEveryRepository(unittest.TestCase):

    def test_an_output_file_inside_the_repo_exits_2(self):
        with tempfile.TemporaryDirectory() as outside:
            result = subprocess.run([sys.executable, str(SCRIPT), outside, "-o", "./inside.txt"],
                                    cwd=bsc.REPO_ROOT, capture_output=True, text=True)
        self.assertEqual(result.returncode, 2)
        self.assertIn("REFUSED", result.stderr)
        self.assertFalse((bsc.REPO_ROOT / "inside.txt").exists())

    def test_a_corpus_inside_the_repo_exits_2(self):
        with tempfile.TemporaryDirectory() as outside:
            result = subprocess.run([sys.executable, str(SCRIPT), str(bsc.REPO_ROOT / "scripts"),
                                     "-o", str(Path(outside) / "out.txt")],
                                    capture_output=True, text=True)
        self.assertEqual(result.returncode, 2)
        self.assertIn("REFUSED", result.stderr)

    def test_a_path_inside_any_root_is_refused(self):
        root = Path(tempfile.gettempdir()).resolve() / "some-checkout"
        with self.assertRaises(SystemExit) as raised:
            bsc.refuse_if_inside_repo(root / "deep" / "out.txt", "output file", [root])
        self.assertEqual(raised.exception.code, 2)

    def test_a_sibling_sharing_a_prefix_is_not_refused(self):
        root = Path(tempfile.gettempdir()).resolve() / "some-checkout"
        bsc.refuse_if_inside_repo(Path(str(root) + "-sibling") / "out.txt", "output file", [root])   # does not raise

    def test_the_roots_include_this_checkout(self):
        self.assertIn(bsc.REPO_ROOT, bsc.repository_roots())


if __name__ == "__main__":
    unittest.main()

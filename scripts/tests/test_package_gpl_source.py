#!/usr/bin/env python3
from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "package-gpl-source.py"


class PackageGplSourceTest(unittest.TestCase):
    def make_tree(self, root: Path) -> Path:
        (root / "app/src").mkdir(parents=True)
        (root / "app/src/Main.kt").write_text("// source\n", encoding="utf-8")
        (root / "LICENSE").write_text("GPL\n", encoding="utf-8")
        nextlib = root / "nextlib-source"
        nextlib.mkdir()
        (nextlib / "LICENSE").write_text("GPL\n", encoding="utf-8")
        (nextlib / "library.kt").write_text("// nextlib\n", encoding="utf-8")
        return nextlib

    def run_script(self, root: Path, nextlib: Path, output: Path) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [sys.executable, str(SCRIPT), "--root", str(root), "--version", "test",
             "--nextlib-source", str(nextlib), "--output", str(output)],
            text=True, capture_output=True, check=False,
        )

    def test_real_packager_creates_portable_archive_with_nextlib(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp) / "project"
            root.mkdir()
            nextlib = self.make_tree(root)
            output = Path(temp) / "source.zip"
            result = self.run_script(root, nextlib, output)
            self.assertEqual(0, result.returncode, result.stderr)
            with zipfile.ZipFile(output) as archive:
                names = archive.namelist()
                self.assertIn("LICENSE", names)
                self.assertIn("CORRESPONDING_SOURCE_MANIFEST.json", names)
                self.assertTrue(any(name.startswith("third-party/nextlib-") for name in names))
                self.assertFalse(any("\\" in name or name.startswith("/") or ".." in Path(name).parts for name in names))
                manifest = json.loads(archive.read("CORRESPONDING_SOURCE_MANIFEST.json"))
                self.assertEqual("d774e7e6d65204d409dc679ab173c67030d4a7d4", manifest["nextlibCommit"])
            self.assertTrue(output.with_suffix(".zip.sha256").is_file())

    def test_packager_fails_closed_on_secret_file(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp) / "project"
            root.mkdir()
            nextlib = self.make_tree(root)
            (root / "secrets.properties").write_text("API_KEY=do-not-publish\n", encoding="utf-8")
            result = self.run_script(root, nextlib, Path(temp) / "source.zip")
            self.assertNotEqual(0, result.returncode)
            self.assertIn("Refusing to package sensitive files", result.stderr + result.stdout)


    def test_packager_skips_development_artifacts(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp) / "project"
            root.mkdir()
            nextlib = self.make_tree(root)
            (root / ".kotlin").mkdir()
            (root / ".kotlin/cache.bin").write_text("cache", encoding="utf-8")
            (root / ".artifacts").mkdir()
            (root / ".artifacts/plan.md").write_text("plan", encoding="utf-8")
            (root / "scripts/__pycache__").mkdir(parents=True)
            (root / "scripts/__pycache__/mod.pyc").write_text("bytecode", encoding="utf-8")
            (root / "scripts/loose.pyc").write_text("bytecode", encoding="utf-8")
            output = Path(temp) / "source.zip"
            result = self.run_script(root, nextlib, output)
            self.assertEqual(0, result.returncode, result.stderr)
            with zipfile.ZipFile(output) as archive:
                names = archive.namelist()
                self.assertIn("LICENSE", names)
                self.assertFalse(any(
                    ".kotlin" in Path(name).parts or
                    ".artifacts" in Path(name).parts or
                    "__pycache__" in Path(name).parts or
                    name.endswith(".pyc")
                    for name in names
                ))


if __name__ == "__main__":
    unittest.main()

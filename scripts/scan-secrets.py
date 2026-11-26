#!/usr/bin/env python3
"""Fail on committed private keys or common non-template secret files.

The check is about material that would be *published*, so the candidate set is what git would
publish rather than every file sitting on disk. A bare tree walk also flagged machine-local
files that .gitignore already excludes: ``local.properties`` carries only ``sdk.dir`` and is
required for Gradle to locate the Android SDK, yet it made every working checkout fail a scan
whose own docstring says "committed".

Rules that cannot be resolved confidently fail *closed* - the file is still scanned. Wildcard
ignore rules such as ``*.jks`` are deliberately not honoured, so the highest-risk material is
never skipped on the strength of a pattern this script cannot fully interpret.
"""
from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
IGNORED = {".git", ".gradle", "build", ".idea"}
SENSITIVE_NAMES = {".env", "local.properties", "secrets.properties", "oxigenplayer-secrets.properties"}
SENSITIVE_SUFFIXES = {".jks", ".keystore", ".jceks", ".p12", ".pem", ".key"}
PRIVATE_KEY = re.compile(rb"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----")


def git_candidates() -> list[Path] | None:
    """Files git would publish, or ``None`` when this checkout is not a repository.

    ``--cached`` is the committed set and ``--others --exclude-standard`` adds untracked files
    that .gitignore does not already exclude; together they are exactly what ``git add -A``
    would stage. Asking git keeps the answer authoritative - reimplementing gitignore semantics
    (negation, ``**``, directory-only rules) by hand risks silently skipping a real secret.
    """
    if not (ROOT / ".git").exists():
        return None
    try:
        completed = subprocess.run(
            ["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"],
            cwd=ROOT,
            capture_output=True,
            check=True,
        )
    except (OSError, subprocess.CalledProcessError):
        return None
    return [ROOT / name for name in completed.stdout.decode("utf-8").split("\0") if name]


def literal_ignore_rules() -> set[str]:
    """Unambiguous .gitignore rules, for a checkout with no .git directory to ask.

    Only literal basenames and root-anchored literal paths are honoured. Any rule carrying a
    wildcard, a negation or a nested path is dropped instead of guessed at, so an uncertain
    rule fails closed and the file is still scanned.
    """
    ignore_file = ROOT / ".gitignore"
    if not ignore_file.is_file():
        return set()
    rules: set[str] = set()
    for raw in ignore_file.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith(("#", "!")):
            continue
        anchored = line.startswith("/")
        pattern = line.strip("/")
        if not pattern or "/" in pattern or any(ch in pattern for ch in "*?["):
            continue
        rules.add("/" + pattern if anchored else pattern)
    return rules


def candidate_files() -> list[Path]:
    """Every file the secret scan should look at, in repository or bare-checkout form."""
    tracked = git_candidates()
    if tracked is not None:
        return tracked
    rules = literal_ignore_rules()
    candidates: list[Path] = []
    for path in ROOT.rglob("*"):
        if not path.is_file():
            continue
        relative = path.relative_to(ROOT)
        if any(part in IGNORED for part in relative.parts):
            continue
        if "/" + relative.as_posix() in rules or path.name in rules:
            continue
        candidates.append(path)
    return candidates

violations: list[str] = []
for path in candidate_files():
    if not path.is_file():
        continue
    relative = path.relative_to(ROOT)
    if any(part in IGNORED for part in relative.parts):
        continue
    name = path.name.lower()
    if not (name.endswith(".example") or name.endswith(".template")):
        if name in SENSITIVE_NAMES or name.startswith(".env.") or path.suffix.lower() in SENSITIVE_SUFFIXES:
            violations.append(relative.as_posix())
            continue
    if path.stat().st_size <= 2_000_000 and PRIVATE_KEY.search(path.read_bytes()):
        violations.append(relative.as_posix())

if violations:
    print("Sensitive material found:\n- " + "\n- ".join(sorted(violations)), file=sys.stderr)
    raise SystemExit(1)
print("No committed private keys or non-template secret files found")

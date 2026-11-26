#!/usr/bin/env python3
"""Create a portable, secret-safe corresponding-source archive for GPL full builds."""

from __future__ import annotations

import argparse
import hashlib
import json
import zipfile
from pathlib import Path, PurePosixPath

NEXTLIB_COORDINATE = "io.github.anilbeesetti:nextlib-media3ext:1.11.1-0.18.0"
NEXTLIB_TAG = "v1.11.1-0.18.0"
NEXTLIB_COMMIT = "d774e7e6d65204d409dc679ab173c67030d4a7d4"
NEXTLIB_SOURCE_URL = f"https://github.com/anilbeesetti/nextlib/archive/{NEXTLIB_COMMIT}.zip"
EXCLUDED_PARTS = {
    ".git", ".gradle", "build", ".idea", ".kotlin", ".artifacts", "__pycache__",
    "apk-debug", "outputs", "audit-evidence",
}
# Binary and machine-local artifacts that must never enter the published source archive.
# The working tree carries debug APKs, decoded manifest/arsc dumps, build logs and
# screenshots; the corresponding source is the text under app/, scripts/, gradle/ and docs.
EXCLUDED_SUFFIXES = {
    ".pyc", ".pyo", ".apk", ".aab", ".arsc", ".dex", ".zip",
    ".log", ".err", ".out", ".png", ".jpg", ".jpeg", ".gif", ".webp",
    ".prof", ".hprof", ".bin",
}
# Machine-local build dumps that live at the repository root. The real manifests are
# app/src/*/AndroidManifest.xml and must stay, so this is matched on the root-relative
# path rather than on the file name.
EXCLUDED_ROOT_NAMES = {"AndroidManifest.xml", "resources.arsc"}
EXCLUDED_ROOT_SUFFIXES = {".txt", ".err", ".log", ".out"}
SENSITIVE_EXACT_NAMES = {
    ".env", "local.properties", "secrets.properties", "oxigenplayer-secrets.properties",
}
SENSITIVE_SUFFIXES = {".jks", ".keystore", ".jceks", ".p12", ".pem", ".key"}
SENSITIVE_CONFIG_SUFFIXES = {".properties", ".json", ".yaml", ".yml", ".toml"}


def relative_posix(path: Path, root: Path) -> str:
    return str(PurePosixPath(*path.relative_to(root).parts))


def is_sensitive(path: Path) -> bool:
    name = path.name.lower()
    if name.endswith(".example") or name.endswith(".template"):
        return False
    if name in SENSITIVE_EXACT_NAMES or name.startswith(".env."):
        return True
    if path.suffix.lower() in SENSITIVE_SUFFIXES:
        return True
    return path.suffix.lower() in SENSITIVE_CONFIG_SUFFIXES and (
        "secret" in name or "credential" in name or "token" in name
    )


def project_files(root: Path, output: Path) -> list[Path]:
    files: list[Path] = []
    sensitive: list[str] = []
    for path in root.rglob("*"):
        if not path.is_file() or path.resolve() == output:
            continue
        relative = path.relative_to(root)
        if any(part in EXCLUDED_PARTS for part in relative.parts):
            continue
        if len(relative.parts) == 1 and (
            path.name in EXCLUDED_ROOT_NAMES or path.suffix.lower() in EXCLUDED_ROOT_SUFFIXES
        ):
            continue
        if path.suffix.lower() in EXCLUDED_SUFFIXES:
            continue
        if is_sensitive(relative):
            sensitive.append(relative_posix(path, root))
            continue
        files.append(path)
    if sensitive:
        details = "\n- ".join(sorted(sensitive))
        raise SystemExit(f"Refusing to package sensitive files:\n- {details}")
    return sorted(files)


def add_nextlib_source(archive: zipfile.ZipFile, source: Path) -> int:
    prefix = PurePosixPath("third-party", f"nextlib-{NEXTLIB_COMMIT}")
    count = 0
    if source.is_dir():
        for path in sorted(source.rglob("*")):
            if path.is_file():
                archive.write(path, str(prefix / PurePosixPath(*path.relative_to(source).parts)))
                count += 1
    elif source.is_file() and zipfile.is_zipfile(source):
        archive.write(source, str(PurePosixPath("third-party", f"nextlib-{NEXTLIB_COMMIT}.zip")))
        count += 1
        with zipfile.ZipFile(source) as upstream:
            for info in upstream.infolist():
                if info.is_dir():
                    continue
                parts = PurePosixPath(info.filename).parts
                stripped = parts[1:] if len(parts) > 1 else parts
                if not stripped or ".." in stripped:
                    raise SystemExit("Unsafe path in NextLib source archive")
                archive.writestr(str(prefix / PurePosixPath(*stripped)), upstream.read(info))
                count += 1
    else:
        raise SystemExit("--nextlib-source must be an extracted directory or ZIP archive")
    return count


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path.cwd())
    parser.add_argument("--version", required=True)
    parser.add_argument(
        "--nextlib-source",
        type=Path,
        required=True,
        help="Exact NextLib source directory/ZIP for the pinned commit",
    )
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()

    root = args.root.resolve()
    output = (args.output or root / f"OxigenPlayer-{args.version}-corresponding-source.zip").resolve()
    source = args.nextlib_source.resolve()
    output.parent.mkdir(parents=True, exist_ok=True)

    files = project_files(root, output)
    manifest = {
        "applicationVersion": args.version,
        "license": "GPL-3.0-only",
        "nextlibCoordinate": NEXTLIB_COORDINATE,
        "nextlibTag": NEXTLIB_TAG,
        "nextlibCommit": NEXTLIB_COMMIT,
        "nextlibSourceUrl": NEXTLIB_SOURCE_URL,
    }
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for path in files:
            archive.write(path, relative_posix(path, root))
        archive.writestr("CORRESPONDING_SOURCE_MANIFEST.json", json.dumps(manifest, indent=2) + "\n")
        nextlib_count = add_nextlib_source(archive, source)

    digest = hashlib.sha256(output.read_bytes()).hexdigest()
    checksum_file = output.with_suffix(output.suffix + ".sha256")
    checksum_file.write_text(f"{digest}  {output.name}\n", encoding="utf-8")
    print(f"Wrote {output} with {len(files)} project files and {nextlib_count} NextLib files")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

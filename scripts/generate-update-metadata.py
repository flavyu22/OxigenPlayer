#!/usr/bin/env python3
"""Generate validated OxigenPlayer direct-update metadata from a final signed APK."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import zipfile
from pathlib import Path
from urllib.parse import urlparse

MAX_APK_BYTES = 350 * 1024 * 1024
EXPECTED_REPOSITORY_PATH = "/flavyu22/OxigenPlayer/releases/download/"
RELEASE_PATH_PATTERN = re.compile(
    r"^/flavyu22/OxigenPlayer/releases/download/"
    r"[0-9A-Za-z][0-9A-Za-z._+-]{0,127}/"
    r"[0-9A-Za-z][0-9A-Za-z._+-]{0,191}\.apk$",
    re.IGNORECASE,
)
VERSION_NAME_PATTERN = re.compile(r"^[0-9A-Za-z][0-9A-Za-z._+-]{0,31}$")
PACKAGE_NAME_PATTERN = re.compile(r"^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+$")


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def valid_download_url(value: str) -> bool:
    parsed = urlparse(value)
    return (
        parsed.scheme == "https"
        and parsed.hostname == "github.com"
        and parsed.port in (None, 443)
        and parsed.username is None
        and parsed.password is None
        and parsed.fragment == ""
        and parsed.query == ""
        and RELEASE_PATH_PATTERN.fullmatch(parsed.path) is not None
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("apk", type=Path, help="Final signed direct-release APK")
    parser.add_argument("--version-code", type=int, required=True)
    parser.add_argument("--version-name", required=True)
    parser.add_argument("--download-url", required=True)
    parser.add_argument("--package-name", default="com.oxigenplayer.app")
    parser.add_argument("--output", type=Path, default=Path("update.json"))
    args = parser.parse_args()

    if not args.apk.is_file():
        parser.error(f"APK not found: {args.apk}")
    size = args.apk.stat().st_size
    if args.version_code <= 0:
        parser.error("--version-code must be positive")
    if not VERSION_NAME_PATTERN.fullmatch(args.version_name):
        parser.error("--version-name must contain 1-32 safe release characters")
    if not PACKAGE_NAME_PATTERN.fullmatch(args.package_name):
        parser.error("--package-name is not a valid Android package name")
    if not zipfile.is_zipfile(args.apk):
        parser.error("The supplied file is not an APK/ZIP archive")
    if not 0 < size <= MAX_APK_BYTES:
        parser.error(f"APK size must be between 1 and {MAX_APK_BYTES} bytes")
    if not valid_download_url(args.download_url):
        parser.error("--download-url must be the immutable HTTPS APK URL from this repository's GitHub release")

    metadata = {
        "enabled": True,
        "versionCode": args.version_code,
        "versionName": args.version_name,
        "packageName": args.package_name,
        "downloadUrl": args.download_url,
        "sha256": sha256(args.apk),
        "sizeBytes": size,
    }
    args.output.write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Wrote {args.output} ({size} bytes, SHA-256 {metadata['sha256']})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

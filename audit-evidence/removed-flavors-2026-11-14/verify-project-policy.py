#!/usr/bin/env python3
"""Repository policy checks that require neither Android SDK nor network access."""

from __future__ import annotations

import hashlib
import re
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LANG_DIRS = {
    "en": "values",
    "ro": "values-ro",
    "es": "values-es",
    "fr": "values-fr",
    "de": "values-de",
    "it": "values-it",
    "pt": "values-pt",
    "ru": "values-ru",
}
PLACEHOLDER = re.compile(r"%(?:\d+\$)?[a-zA-Z]")


def fail(message: str) -> None:
    print(f"ERROR: {message}", file=sys.stderr)
    raise SystemExit(1)


def verify_wrapper() -> None:
    jar = ROOT / "gradle/wrapper/gradle-wrapper.jar"
    expected = (ROOT / "gradle/wrapper/gradle-wrapper.jar.sha256").read_text(encoding="utf-8").split()[0].lower()
    if hashlib.sha256(jar.read_bytes()).hexdigest() != expected:
        fail("Gradle wrapper JAR checksum mismatch")


def verify_no_dynamic_versions() -> None:
    catalog = (ROOT / "gradle/libs.versions.toml").read_text(encoding="utf-8")
    if re.search(r'(?i)(latest\.|snapshot|version\s*=\s*"\+")', catalog):
        fail("Dynamic or snapshot dependency version found")


def read_strings(path: Path) -> dict[str, str]:
    root = ET.parse(path).getroot()
    result: dict[str, str] = {}
    for node in root.findall("string"):
        name = node.attrib.get("name")
        if not name or name in result:
            fail(f"Missing or duplicate string name in {path}")
        result[name] = "".join(node.itertext())
    return result


def verify_localization_shape() -> None:
    translations = {
        language: read_strings(ROOT / "app/src/main/res" / directory / "strings.xml")
        for language, directory in LANG_DIRS.items()
    }
    reference = translations["en"]
    for language, entries in translations.items():
        if entries.keys() != reference.keys():
            missing = sorted(reference.keys() - entries.keys())
            extra = sorted(entries.keys() - reference.keys())
            fail(f"Localization key mismatch for {language}; missing={missing}, extra={extra}")
        for key, value in entries.items():
            if PLACEHOLDER.findall(value) != PLACEHOLDER.findall(reference[key]):
                fail(f"Localization placeholder mismatch for {language}:{key}")
    locale_config = ET.parse(ROOT / "app/src/main/res/xml/locales_config.xml").getroot()
    declared = {node.attrib.get("{http://schemas.android.com/apk/res/android}name") for node in locale_config}
    if declared != set(LANG_DIRS):
        fail(f"localeConfig mismatch: {sorted(declared)}")


def verify_language_sources_agree() -> None:
    """The supported-language list is declared in four places; drift is silent and user-visible.

    A language present in the picker but absent from ``localeFilters`` ships without resources,
    and a language missing from ``SUPPORTED_APP_LANGUAGES`` is silently downgraded to English.
    """
    expected = set(LANG_DIRS)

    gradle = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
    filters = re.search(r"localeFilters\s*\+=\s*listOf\(([^)]*)\)", gradle)
    if not filters:
        fail("app/build.gradle.kts no longer declares localeFilters")
    declared_filters = set(re.findall(r'"([a-z-]+)"', filters.group(1)))
    if declared_filters != expected:
        fail(f"localeFilters mismatch: {sorted(declared_filters)} != {sorted(expected)}")

    preferences = (ROOT / "app/src/main/java/com/oxigenplayer/app/PreferencesManager.kt").read_text(
        encoding="utf-8"
    )
    supported = re.search(r"SUPPORTED_APP_LANGUAGES\s*=\s*setOf\(([^)]*)\)", preferences)
    if not supported:
        fail("PreferencesManager no longer declares SUPPORTED_APP_LANGUAGES")
    declared_supported = set(re.findall(r'"([a-z-]+)"', supported.group(1)))
    if declared_supported != expected:
        fail(f"SUPPORTED_APP_LANGUAGES mismatch: {sorted(declared_supported)} != {sorted(expected)}")


def verify_text_encoding() -> None:
    """Translated resources must not be double-encoded.

    The shipped ``values-*/strings.xml`` were UTF-8 encoded, decoded as Windows-1252 and
    re-encoded as UTF-8, so every non-ASCII character reached the screen as mojibake
    (``Ãœbersetzen`` for ``Übersetzen``). The signature is unambiguous: a C1 control
    character (U+0080-U+009F), which cannot legitimately appear in translated copy, or
    a lead character immediately followed by one. ``C3 83 C2 8D`` is ``Í`` encoded twice;
    valid UTF-8 for ``Í`` is ``C3 8D``.
    """
    suspects = []
    for path in sorted((ROOT / "app/src/main/res").rglob("*.xml")):
        text = path.read_text(encoding="utf-8")
        for number, line in enumerate(text.splitlines(), 1):
            if any("\u0080" <= character <= "\u009f" for character in line):
                suspects.append(f"{path.relative_to(ROOT).as_posix()}:{number}")
                continue
            for index in range(len(line) - 1):
                if line[index] in "\u00c3\u00c2\u00e2" and "\u0080" <= line[index + 1] <= "\u009f":
                    suspects.append(f"{path.relative_to(ROOT).as_posix()}:{number}")
                    break
    if suspects:
        fail(
            "Double-encoded text in resources (re-save as UTF-8, do not round-trip "
            "through Windows-1252): " + ", ".join(suspects)
        )


def verify_string_lookup_keys() -> None:
    """Every OxigenStrings.get key must be mapped, otherwise the raw key reaches the screen.

    The lookup is by string, so the compiler cannot catch a typo or a key removed during a
    refactor; ``get`` returns the identifier verbatim and the user sees ``subtitle_read_failed``
    instead of a sentence.
    """
    facade = ROOT / "app/src/main/java/com/oxigenplayer/app/OxigenStrings.kt"
    mapped = set(re.findall(r'"(\w+)" to R\.string\.(\w+)', facade.read_text(encoding="utf-8")))
    renamed = [(key, resource) for key, resource in mapped if key != resource]
    if renamed:
        fail(f"Lookup key does not match its resource name: {renamed}")
    mapped_keys = {key for key, _ in mapped}

    requested: set[str] = set()
    for path in (ROOT / "app/src").rglob("*.kt"):
        text = path.read_text(encoding="utf-8")
        requested |= set(re.findall(r'OxigenStrings\.get\(\s*\w+\s*,\s*"(\w+)"', text))
    unmapped = sorted(requested - mapped_keys)
    if unmapped:
        fail(f"OxigenStrings keys requested but not mapped: {unmapped}")


def verify_real_packager_tests() -> None:
    result = subprocess.run(
        [sys.executable, "-m", "unittest", "discover", "-s", "scripts/tests"],
        cwd=ROOT, text=True, capture_output=True, check=False,
    )
    if result.returncode:
        fail("Corresponding-source packaging tests failed:\n" + result.stdout + result.stderr)


def verify_security_defaults() -> None:
    gradle_properties = (ROOT / "gradle.properties").read_text(encoding="utf-8")
    if "OXIGEN_EMBED_PROVIDER_CREDENTIALS=false" not in gradle_properties:
        fail("Provider credential embedding must default to false")
    manifest = ET.parse(ROOT / "app/src/main/AndroidManifest.xml").getroot()
    application = manifest.find("application")
    android = "{http://schemas.android.com/apk/res/android}"
    if application is None or application.attrib.get(android + "usesCleartextTraffic") != "false":
        fail("Main/Play manifest must disable cleartext traffic")
    if application.attrib.get(android + "localeConfig") != "@xml/locales_config":
        fail("Android localeConfig must be declared")
    direct = (ROOT / "app/src/direct/AndroidManifest.xml").read_text(encoding="utf-8")
    if 'android:usesCleartextTraffic="true"' not in direct:
        fail("Direct flavor must explicitly declare its LAN cleartext exception")
    network = (ROOT / "app/src/main/java/com/oxigenplayer/app/NetworkClient.kt").read_text(encoding="utf-8")
    if network.count("addNetworkInterceptor") < 2:
        fail("Per-hop network interceptors are missing")
    if "isExplicitLanHost" not in network or "routeAddress" not in network:
        fail("LAN cleartext route enforcement is missing")
    if not (ROOT / "LICENSE").is_file():
        fail("Root LICENSE is missing")
    # Derive the expected revision from the packager instead of hardcoding a second copy, so a
    # NextLib bump can never leave the published source offer or the shipped notice pointing at a
    # stale commit. Both the source offer and the `full` flavor's bundled notice must carry it.
    packager = (ROOT / "scripts/package-gpl-source.py").read_text(encoding="utf-8")
    pinned = re.search(r'^NEXTLIB_COMMIT = "([0-9a-f]{40})"', packager, re.MULTILINE)
    if pinned is None:
        fail("Pinned NextLib source revision is missing")
    for relative in ("SOURCE_OFFER_FULL.md", "app/src/full/res/raw/nextlib_notice.txt"):
        if pinned.group(1) not in (ROOT / relative).read_text(encoding="utf-8"):
            fail("Pinned NextLib source revision is missing")


def verify_variant_matrix() -> None:
    workflow = (ROOT / ".github/workflows/android.yml").read_text(encoding="utf-8")
    expected = (
        "PlayPlatformApi24", "PlayPlatformGenai", "PlayFullApi24", "PlayFullGenai",
        "DirectPlatformApi24", "DirectPlatformGenai", "DirectFullApi24", "DirectFullGenai",
    )
    missing = [variant for variant in expected if workflow.count(variant) < 2]
    if missing:
        fail(f"CI does not cover debug and release for: {', '.join(missing)}")
    for token in ("android-37", "ExternalPlaybackIntentInstrumentedTest", "-accel auto"):
        if token not in workflow:
            fail(f"CI instrumentation policy is missing: {token}")


def verify_source_headers() -> None:
    missing = []
    for path in ROOT.rglob("*.kt"):
        if any(part in {"build", ".gradle"} for part in path.parts):
            continue
        if not path.read_text(encoding="utf-8").startswith("// SPDX-License-Identifier: GPL-3.0-only"):
            missing.append(path.relative_to(ROOT).as_posix())
    if missing:
        fail("Kotlin files missing SPDX header: " + ", ".join(missing))


def main() -> int:
    verify_wrapper()
    verify_no_dynamic_versions()
    verify_localization_shape()
    verify_text_encoding()
    verify_language_sources_agree()
    verify_string_lookup_keys()
    verify_real_packager_tests()
    verify_security_defaults()
    verify_variant_matrix()
    verify_source_headers()
    subprocess.run([sys.executable, "scripts/scan-secrets.py"], cwd=ROOT, check=True)
    print("Project policy checks passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

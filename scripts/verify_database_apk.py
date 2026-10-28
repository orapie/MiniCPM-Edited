#!/usr/bin/env python3
"""Check the actual APK, not just the Gradle source directory configuration."""

import argparse
import hashlib
import json
from pathlib import Path
import sys
import zipfile

from prepare_database_bundle import check, require


def verify(apk, source, program_source, report):
    manifest = check(source)
    with zipfile.ZipFile(apk) as archive:
        # AAPT may emit UTF-8 entry names without setting ZIP's UTF-8 flag.
        # Python otherwise interprets existing Chinese reference-audio names as CP437.
        entries = {}
        for info in archive.infolist():
            name = info.filename
            if not info.flag_bits & 0x800:
                try:
                    name = name.encode("cp437").decode("utf-8")
                except (UnicodeEncodeError, UnicodeDecodeError):
                    pass
            require(name not in entries, "APK contains duplicate entries")
            entries[name] = info
        names = list(entries)
        require(len(names) == len(set(names)), "APK contains duplicate entries")
        assets = {name for name in names if name.startswith("assets/") and not name.endswith("/")}
        expected = {"assets/database/manifest.json": (source / "manifest.json").read_bytes()}
        expected.update({"assets/database/" + item["path"]: (source / item["path"]).read_bytes()
                         for item in manifest["files"]})
        require({name for name in assets if name.startswith("assets/database/")} == set(expected),
                "APK database file inventory differs from current bundle")
        for name, content in expected.items():
            require(archive.read(entries[name]) == content, f"APK contains stale database file: {name}")
        require(not any(name.startswith("assets/harness/") for name in assets), "Legacy Harness assets packaged")
        program = {"assets/prompts/roleplay_system.prompt":
                   (program_source / "prompts/roleplay_system.prompt").read_bytes()}
        program.update({"assets/" + path.relative_to(program_source).as_posix(): path.read_bytes()
                        for path in (program_source / "ref_audios").rglob("*") if path.is_file() and not path.name.startswith(".")})
        require(assets == set(expected) | set(program), "Unexpected or missing program assets in APK")
        for name, content in program.items():
            require(archive.read(entries[name]) == content, f"Program asset changed or missing: {name}")
        inventory = {"dataset_id": manifest["dataset_id"], "dataset_fingerprint": manifest["dataset_fingerprint"],
                     "apk_sha256": hashlib.sha256(apk.read_bytes()).hexdigest(), "knowledge_files": len(expected),
                     "program_files": len(program), "legacy_harness_files": 0, "assets": sorted(assets)}
    report.parent.mkdir(parents=True, exist_ok=True)
    report.write_text(json.dumps(inventory, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return inventory


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--program-source", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    try:
        result = verify(args.apk, args.source.resolve(), args.program_source.resolve(), args.report)
        print(f"APK_VALID dataset={result['dataset_id']} knowledge_files={result['knowledge_files']} "
              f"legacy_harness_files=0 fingerprint={result['dataset_fingerprint']}")
    except (ValueError, KeyError, OSError, zipfile.BadZipFile) as error:
        print(f"APK_DATABASE_ERROR: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())

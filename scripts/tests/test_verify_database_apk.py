from pathlib import Path
import json
import sys
import tempfile
import unittest
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from verify_database_apk import verify


ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "database"
PROGRAM = ROOT / "app/src/main/assets"


class ApkInventoryTest(unittest.TestCase):
    def make_apk(self, path, extra=None, stale=None):
        manifest = json.loads((SOURCE / "manifest.json").read_text())
        entries = {"assets/database/manifest.json": (SOURCE / "manifest.json").read_bytes()}
        entries.update({"assets/database/" + item["path"]: (SOURCE / item["path"]).read_bytes()
                        for item in manifest["files"]})
        entries["assets/prompts/roleplay_system.prompt"] = (PROGRAM / "prompts/roleplay_system.prompt").read_bytes()
        entries.update({"assets/" + file.relative_to(PROGRAM).as_posix(): file.read_bytes()
                        for file in (PROGRAM / "ref_audios").glob("*") if file.is_file()})
        if extra:
            entries[extra] = b"legacy data"
        if stale:
            entries[stale] = b"{}"
        with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            for name, value in entries.items():
                archive.writestr(name, value)

    def test_old_harness_index_in_apk_is_rejected(self):
        with tempfile.TemporaryDirectory(prefix="apk-inventory-test-") as temporary:
            apk, report = Path(temporary) / "test.apk", Path(temporary) / "report.json"
            self.make_apk(apk, extra="assets/harness/rag/index.json")
            with self.assertRaisesRegex(ValueError, "Legacy Harness"):
                verify(apk, SOURCE, PROGRAM, report)
            self.assertFalse(report.exists())

    def test_stale_database_bytes_in_apk_are_rejected(self):
        with tempfile.TemporaryDirectory(prefix="apk-stale-test-") as temporary:
            apk, report = Path(temporary) / "test.apk", Path(temporary) / "report.json"
            self.make_apk(apk, stale="assets/database/runtime/android/vector/index.json")
            with self.assertRaisesRegex(ValueError, "stale database"):
                verify(apk, SOURCE, PROGRAM, report)
            self.assertFalse(report.exists())


if __name__ == "__main__":
    unittest.main()

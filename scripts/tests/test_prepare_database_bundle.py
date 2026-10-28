import importlib.util
import json
from pathlib import Path
import shutil
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "prepare_database_bundle.py"
SPEC = importlib.util.spec_from_file_location("prepare_database_bundle", SCRIPT)
bundle = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(bundle)
SOURCE = SCRIPT.parents[1] / "database"


class DatabasePreparationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="database-prepare-test-")
        self.root = Path(self.temp.name) / "database"
        shutil.copytree(SOURCE, self.root, ignore=shutil.ignore_patterns("__pycache__", "*.pyc"))

    def tearDown(self):
        self.temp.cleanup()

    def test_preparation_is_deterministic_and_does_not_modify_sqlite_or_promote_source_edges(self):
        original = bundle.sha256(self.root / bundle.PATHS["v2_database"])
        first, report = bundle.prepare(self.root, "three_body_2", "test-v1", "luo_ji")
        second, _ = bundle.prepare(self.root, "three_body_2", "test-v1", "luo_ji")
        self.assertEqual(first, second)
        self.assertEqual(original, bundle.sha256(self.root / bundle.PATHS["v2_database"]))
        self.assertEqual(125, report["statement_chars"]["over_180"])
        self.assertEqual(229, report["legacy_story_direct_edges_not_authorized"])
        runtime = bundle.load_json(self.root / bundle.PATHS["runtime_v2"])
        self.assertEqual(3, len(runtime["claims"]))
        self.assertTrue(all(c["source_tier"] == "canon" and c["verification_status"] == "source_span_verified"
                            for c in runtime["claims"]))
        candidates = bundle.load_json(self.root / bundle.PATHS["candidate_v2"])
        self.assertEqual({"available"}, {e["belief_status"] for e in candidates["knowledge_edges"]})
        self.assertEqual({"character_scene_relevance"}, {e["acquisition_method"] for e in candidates["knowledge_edges"]})

    def test_missing_source_does_not_search_old_character_or_harness(self):
        (Path(self.temp.name) / "character").mkdir()
        with self.assertRaisesRegex(ValueError, "does not exist"):
            bundle.prepare(Path(self.temp.name) / "missing", "three_body_2", "v1", "luo_ji")

    def test_path_traversal_absolute_windows_and_symlink_escape_are_rejected(self):
        for path in ("../character/x", "/tmp/x", "C:/old/x", "a\\b", "a/../b", "a//b", "./x"):
            with self.subTest(path=path), self.assertRaisesRegex(ValueError, "Unsafe"):
                bundle.resolve(self.root, path)
        (self.root / "escape").symlink_to(Path(self.temp.name), target_is_directory=True)
        with self.assertRaisesRegex(ValueError, "escapes"):
            bundle.resolve(self.root, "escape/outside")

    def test_source_quote_edit_is_rejected_before_any_generation(self):
        source = bundle.read_source(self.root)
        reviews = bundle.load_json(self.root / bundle.PATHS["reviewed_facts"])
        reviews["facts"][0]["quote"] = "凭空捏造的一句话"
        with self.assertRaisesRegex(ValueError, "quote mismatch"):
            bundle.authorize_reviews(self.root, "three_body_2", source, reviews)

    def test_scene_relevance_is_not_an_allowed_review_acquisition(self):
        reviews = bundle.load_json(self.root / bundle.PATHS["reviewed_facts"])
        reviews["facts"][0]["acquisition_method"] = "character_scene_relevance"
        with self.assertRaisesRegex(ValueError, "not authorization"):
            bundle.authorize_reviews(self.root, "three_body_2", bundle.read_source(self.root), reviews)

    def test_long_atomic_fact_is_rejected(self):
        reviews = bundle.load_json(self.root / bundle.PATHS["reviewed_facts"])
        reviews["facts"][0]["statement"] = "长" * 121
        with self.assertRaisesRegex(ValueError, "120"):
            bundle.authorize_reviews(self.root, "three_body_2", bundle.read_source(self.root), reviews)

    def test_corpus_and_database_must_share_provenance(self):
        path = self.root / "knowledge_base_v2/corpus/documents.jsonl"
        records = bundle.load_jsonl(path)
        records[0]["text"] = "不属于该数据集的内容"
        path.write_text("\n".join(json.dumps(row, ensure_ascii=False) for row in records), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "Corpus text/hash mismatch"):
            bundle.read_source(self.root)

    def test_check_detects_modified_authorized_file(self):
        path = self.root / bundle.PATHS["runtime_v2"]
        path.write_text(path.read_text() + " ", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "file/hash mismatch"):
            bundle.check(self.root)

    def test_read_only_check_preserves_files(self):
        before = (self.root / "manifest.json").read_bytes()
        bundle.check(self.root)
        self.assertEqual(before, (self.root / "manifest.json").read_bytes())

    def test_vector_index_has_provenance_but_does_not_authorize_scene_candidates(self):
        index = bundle.load_json(self.root / bundle.PATHS["vector_index"])
        self.assertEqual(128, len(index["items"]))
        self.assertEqual(125, sum(item["chunk"]["metadata"]["record_kind"] == "scene_candidate" for item in index["items"]))
        for item in index["items"]:
            self.assertEqual(384, len(item["embedding"]))
            self.assertAlmostEqual(1.0, sum(value * value for value in item["embedding"]), places=10)
            self.assertEqual("true", item["chunk"]["metadata"]["authorization_required"])
            self.assertEqual("three_body_2", item["chunk"]["metadata"]["dataset_id"])

    def test_staging_replaces_only_owned_generated_tree_and_does_not_modify_source(self):
        output = Path(self.temp.name) / "build/generated/databaseAssets"
        before = (self.root / "manifest.json").read_bytes()
        manifest = bundle.stage_apk_assets(self.root, output)
        (output / "stale.txt").write_text("old package")
        bundle.stage_apk_assets(self.root, output)
        self.assertFalse((output / "stale.txt").exists())
        expected = {"manifest.json"} | {item["path"] for item in manifest["files"]}
        actual = {p.relative_to(output / "database").as_posix() for p in (output / "database").rglob("*") if p.is_file()}
        self.assertEqual(expected, actual)
        self.assertFalse((output / "harness").exists())
        self.assertFalse((output / "database/scripts").exists())
        self.assertEqual(before, (self.root / "manifest.json").read_bytes())

    def test_stale_source_fails_before_modifying_previous_staged_assets(self):
        output = Path(self.temp.name) / "build/generated/databaseAssets"
        bundle.stage_apk_assets(self.root, output)
        before = (output / "database/manifest.json").read_bytes()
        (self.root / bundle.PATHS["vector_index"]).write_text("{}")
        with self.assertRaisesRegex(ValueError, "file/hash mismatch"):
            bundle.stage_apk_assets(self.root, output)
        self.assertEqual(before, (output / "database/manifest.json").read_bytes())

    def test_staging_rejects_broad_or_unowned_output_directory(self):
        with self.assertRaisesRegex(ValueError, "must end"):
            bundle.stage_apk_assets(self.root, Path(self.temp.name))
        output = Path(self.temp.name) / "build/generated/databaseAssets"
        output.mkdir(parents=True)
        (output / "user-file.txt").write_text("keep")
        with self.assertRaisesRegex(ValueError, "unowned"):
            bundle.stage_apk_assets(self.root, output)
        self.assertEqual("keep", (output / "user-file.txt").read_text())


if __name__ == "__main__":
    unittest.main()

from contextlib import closing
import json
from pathlib import Path
import sqlite3
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from evaluate_semantic_baseline import ROOT, run


class SemanticBaselineTest(unittest.TestCase):
    def setUp(self):
        self.database = ROOT/'database/knowledge_base_v2/character_knowledge_v2.sqlite3'
        self.fixture = ROOT/'scripts/fixtures/semantic_v2_2/evidence_cases.json'

    def test_case_provenance_split_and_lexical_results(self):
        result=run(self.database,self.fixture)
        self.assertEqual(12,len(result['results']))
        self.assertTrue(all(not ids for ids in result['split_overlaps'].values()))
        self.assertIn('lexical',result['backend'])
        self.assertEqual('profession_dev',result['results'][1]['id'])
        self.assertEqual(1,result['results'][1]['rank'])

    def test_stale_fixture_hash_rejected(self):
        value=json.loads(self.fixture.read_text())
        value['sqlite_sha256']='0'*64
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'bad.json';path.write_text(json.dumps(value),encoding='utf-8')
            with self.assertRaisesRegex(ValueError,'fingerprint mismatch'):
                run(self.database,path)

    def test_split_leakage_rejected(self):
        value=json.loads(self.fixture.read_text())
        value['cases'][0]['split']='validation'
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'bad.json';path.write_text(json.dumps(value),encoding='utf-8')
            with self.assertRaisesRegex(ValueError,'leakage'):
                run(self.database,path)


if __name__=='__main__': unittest.main()

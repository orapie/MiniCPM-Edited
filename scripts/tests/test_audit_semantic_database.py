import hashlib
import json
from pathlib import Path
import shutil
import sqlite3
import struct
import subprocess
import sys
import tempfile
import unittest
from contextlib import closing

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from audit_semantic_database import ROOT, audit


class SemanticAuditTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = ROOT / 'knowledge_base_v2/character_knowledge_v2.sqlite3'
        cls.original_hash = hashlib.sha256(cls.source.read_bytes()).hexdigest()

    @classmethod
    def tearDownClass(cls):
        assert hashlib.sha256(cls.source.read_bytes()).hexdigest() == cls.original_hash

    def test_actual_source_passes_and_stale_android_bundle_is_reported(self):
        report = audit(ROOT)
        self.assertEqual('passed', report['status'], report['errors'])
        self.assertEqual(536, report['counts']['evidence_units'])
        self.assertEqual(536, report['counts']['evidence_embeddings'])
        self.assertEqual(3, len(report['old_review_mappings']))
        self.assertTrue(all(item['candidate_matches'] and item['migration_status']=='requires_manual_review'
                            for item in report['old_review_mappings']))
        manifest = json.loads((ROOT / 'manifest.json').read_text())
        has_stale_files = any(not (ROOT / row['path']).is_file() or
                              hashlib.sha256((ROOT / row['path']).read_bytes()).hexdigest() != row['sha256']
                              for row in manifest['files'])
        self.assertEqual(has_stale_files, 'android_bundle_hash_mismatch' in [w['code'] for w in report['warnings']])

    def corrupt_and_expect(self, sql, args, code):
        with tempfile.TemporaryDirectory() as directory:
            copy = Path(directory) / 'source.sqlite3'
            shutil.copyfile(self.source, copy)
            with closing(sqlite3.connect(copy)) as db:
                with db:
                    db.execute(sql, args)
            result = audit(ROOT, copy)
            self.assertEqual('failed', result['status'])
            self.assertIn(code, [e['code'] for e in result['errors']], result['errors'])

    def test_bad_vector_length_rejected(self):
        self.corrupt_and_expect('update evidence_embeddings set vector=? where evidence_id=(select min(evidence_id) from evidence_embeddings)', (b'x',), 'embedding_blob_size')

    def test_nonfinite_vector_rejected(self):
        self.corrupt_and_expect('update evidence_embeddings set vector=? where evidence_id=(select min(evidence_id) from evidence_embeddings)', (struct.pack('<512f',float('nan'), *([0.0]*511)),), 'embedding_nonfinite')

    def test_unscaled_vector_rejected(self):
        self.corrupt_and_expect('update evidence_embeddings set vector=? where evidence_id=(select min(evidence_id) from evidence_embeddings)', (struct.pack('<512f',*([0.0]*512)),), 'embedding_norm')

    def test_missing_embedding_rejected(self):
        self.corrupt_and_expect('delete from evidence_embeddings where evidence_id=(select min(evidence_id) from evidence_embeddings)', (), 'embedding_count_mismatch')

    def test_forged_evidence_rejected(self):
        self.corrupt_and_expect("update evidence set quote='伪造原文' where evidence_id=(select min(evidence_id) from evidence)", (), 'evidence_quote_or_range')

    def test_unknown_and_future_edge_time_rejected(self):
        self.corrupt_and_expect("update knowledge_edges set acquired_at_event='unknown' where knowledge_edge_id=(select min(knowledge_edge_id) from knowledge_edges)", (), 'bad_edge_reference')
        self.corrupt_and_expect("update knowledge_edges set acquired_sequence=999999 where knowledge_edge_id=(select min(knowledge_edge_id) from knowledge_edges)", (), 'bad_edge_reference')

    def test_multiple_active_indices_rejected(self):
        self.corrupt_and_expect("insert into embedding_indexes select 'imposter',model_name,model_revision,dimension,dtype,pooling,normalized,query_instruction,passage_instruction,content_template,content_sha256,document_count,created_at,status from embedding_indexes limit 1", (), 'active_index_count')

    def test_mismatched_graph_mirror_rejected(self):
        self.corrupt_and_expect("update retrieval_expressions set text='changed' where expression_id=(select min(expression_id) from retrieval_expressions)", (), 'mirror_mismatch')

    def test_cli_never_writes_audit_output_inside_source_directory(self):
        target = ROOT / 'audit-should-not-exist.json'
        self.assertFalse(target.exists())
        command = [sys.executable, str(ROOT.parent / 'scripts/audit_semantic_database.py'), '--output', str(target)]
        result = subprocess.run(command, capture_output=True, text=True, check=False)
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(target.exists())

    def test_norm_preserving_vector_tamper_detected_by_pinned_bytes(self):
        snapshot = ROOT.parent / 'scripts/fixtures/semantic_v2_2/source_snapshot.json'
        with tempfile.TemporaryDirectory() as directory:
            copy = Path(directory) / 'source.sqlite3'
            shutil.copyfile(self.source, copy)
            with closing(sqlite3.connect(copy)) as db:
                with db:
                    db.execute('update evidence_embeddings set vector=? where evidence_id=(select min(evidence_id) from evidence_embeddings)',
                        (struct.pack('<512f',1.0,*([0.0]*511)),))
            result = audit(ROOT, copy, snapshot)
            self.assertEqual('failed', result['status'])
            self.assertIn('snapshot_vector_mismatch', [item['code'] for item in result['errors']])


if __name__ == '__main__':
    unittest.main()

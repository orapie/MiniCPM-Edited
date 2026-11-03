from contextlib import closing
from pathlib import Path
import hashlib
import json
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from prepare_v22_migration import ROOT, build_stage
from prepare_database_bundle import fingerprint

SNAPSHOT = ROOT.parent / 'scripts/fixtures/semantic_v2_2/source_snapshot.json'

class V22MigrationTest(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory(prefix='v22-migration-test-')
        self.root=Path(self.tmp.name)/'database'
        shutil.copytree(ROOT,self.root,ignore=shutil.ignore_patterns('__pycache__','*.pyc'))
        self.db=self.root/'knowledge_base_v2/character_knowledge_v2.sqlite3'
        self.before=hashlib.sha256(self.db.read_bytes()).hexdigest()
    def tearDown(self):
        self.tmp.cleanup()
    def rewrite_reviews(self, mutator):
        path=self.root/'reviewed/atomic_facts.json'
        obj=json.loads(path.read_text());mutator(obj)
        path.write_text(json.dumps(obj,ensure_ascii=False,indent=2)+'\n')
        manifest_path=self.root/'manifest.json';manifest=json.loads(manifest_path.read_text())
        next(item for item in manifest['files'] if item['path']=='reviewed/atomic_facts.json')['sha256']=hashlib.sha256(path.read_bytes()).hexdigest()
        manifest['dataset_fingerprint']=fingerprint(manifest)
        manifest_path.write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
    def test_unique_review_mapping_preserves_old_authorization_without_activation(self):
        stage=build_stage(self.root,SNAPSHOT)
        self.assertFalse(stage['activation_allowed'])
        self.assertEqual(536,len(stage['evidence']))
        with (self.root/'knowledge_base_v2/corpus/documents.jsonl').open(encoding='utf-8') as source:
            docs={row['source_id']:row['text'].splitlines() for row in (json.loads(line) for line in source if line.strip())}
        blank_line_count=0
        for item in stage['evidence']:
            projection=item['source_projection']
            lines=docs[item['source_id']]
            reconstructed=''.join(lines[span['line']-1][span['start']:span['end']] for span in projection['line_spans'])
            self.assertEqual(item['quote'],reconstructed,item['evidence_id'])
            self.assertEqual(item['line_end']-item['line_start']+1,len(projection['text'].split('\n')))
            blank_line_count += '\n\n' in projection['text']
        self.assertEqual(75,blank_line_count)
        golden=json.loads((ROOT.parent/'scripts/fixtures/semantic_v2_2/source_projection_golden.json').read_text())
        self.assertEqual(stage['source_sqlite_sha256'],golden['sqlite_sha256'])
        expected=[]
        for item in stage['evidence']:
            projection=item['source_projection']
            expected.append({'evidence_id':item['evidence_id'], 'line_start':item['line_start'],
                'line_end':item['line_end'], 'quote_sha256':item['quote_sha256'],
                'text_sha256':projection['text_sha256'], 'line_spans':projection['line_spans']})
        self.assertEqual(expected,golden['cases'])
        self.assertEqual(768,len(stage['source_edges']))
        self.assertEqual({'approved', 'candidate'}, {edge['authorization_status'] for edge in stage['source_edges']})
        self.assertTrue(all('belief_status' in edge and 'acquired_sequence' in edge for edge in stage['source_edges']))
        self.assertTrue(all(not e['derived_text_is_authorized_fact'] for e in stage['evidence']))
        self.assertEqual(3,len(stage['review_candidates']))
        self.assertTrue(all(x['migration']['requires_independent_review_before_activation'] for x in stage['review_candidates']))
        self.assertEqual('evd-3056d0c06fe12d9c',next(x for x in stage['review_candidates'] if x['claim_id']=='reviewed-luo-ji-profession')['migration']['new_evidence_id'])
        self.assertEqual(self.before,hashlib.sha256(self.db.read_bytes()).hexdigest())
    def test_forged_old_quote_rejected_even_after_manifest_refresh(self):
        self.rewrite_reviews(lambda r:r['facts'][0].update(quote='捏造的原文'))
        with self.assertRaisesRegex(ValueError,'no exact quote'):
            build_stage(self.root,SNAPSHOT)
    def test_changed_review_without_manifest_refresh_rejected(self):
        path=self.root/'reviewed/atomic_facts.json';path.write_text(path.read_text()+' ')
        with self.assertRaisesRegex(ValueError,'review file'):
            build_stage(self.root,SNAPSHOT)
    def test_untrusted_acquisition_method_rejected(self):
        self.rewrite_reviews(lambda r:r['facts'][0].update(acquisition_method='character_scene_relevance'))
        with self.assertRaisesRegex(ValueError,'knowledge basis'):
            build_stage(self.root,SNAPSHOT)
    def test_unknown_character_rejected(self):
        self.rewrite_reviews(lambda r:r['facts'][0].update(character_id='unknown_character'))
        with self.assertRaisesRegex(ValueError,'invalid old review'):
            build_stage(self.root,SNAPSHOT)
    def test_snapshot_rejects_source_database_tampering(self):
        with closing(sqlite3.connect(self.db)) as db:
            with db: db.execute("update evidence_embeddings set vector=zeroblob(2048) where evidence_id=(select min(evidence_id) from evidence_embeddings)")
        with self.assertRaisesRegex(ValueError,'source audit failed'):
            build_stage(self.root,SNAPSHOT)
    def test_cli_cannot_write_inside_source(self):
        target=self.root/'inspection.json'
        result=subprocess.run([sys.executable,str(ROOT.parent/'scripts/prepare_v22_migration.py'),'--source',str(self.root),'--output',str(target)],capture_output=True,text=True)
        self.assertNotEqual(0,result.returncode)
        self.assertFalse(target.exists())

if __name__=='__main__': unittest.main()

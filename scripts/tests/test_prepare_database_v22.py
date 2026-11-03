import hashlib
import json
from pathlib import Path
import shutil
import sys
import tempfile
import unittest
from unittest import mock
import os

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import prepare_database_bundle as bundle
import prepare_database_v22

SOURCE=Path(__file__).resolve().parents[2]/'database'

class V22BundlePreparationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.original=tempfile.TemporaryDirectory(prefix='v22-source-')
        cls.prepared=Path(cls.original.name)/'database'
        shutil.copytree(SOURCE,cls.prepared,ignore=shutil.ignore_patterns('__pycache__','*.pyc'))
        cls.before=bundle.sha256(cls.prepared/'knowledge_base_v2/character_knowledge_v2.sqlite3')
        cls.manifest, cls.report=bundle.prepare(cls.prepared,'three_body_2','v22-test','luo_ji')
    @classmethod
    def tearDownClass(cls): cls.original.cleanup()
    def setUp(self):
        self.temporary=tempfile.TemporaryDirectory(prefix='v22-test-')
        self.root=Path(self.temporary.name)/'database'
        shutil.copytree(self.prepared,self.root)
    def tearDown(self): self.temporary.cleanup()
    def rehash(self,path):
        manifest=self.root/'manifest.json';m=json.loads(manifest.read_text())
        next(f for f in m['files'] if f['path']==path)['sha256']=bundle.sha256(self.root/path)
        m['dataset_fingerprint']=bundle.fingerprint(m)
        bundle.write_json(manifest,m)
    def test_reproducible_bundle_and_preserved_sqlite(self):
        original=(self.root/'manifest.json').read_bytes()
        self.assertEqual(self.manifest,bundle.check(self.root))
        second,_=bundle.prepare(self.root,'three_body_2','v22-test','luo_ji')
        self.assertEqual(self.manifest,second)
        self.assertEqual(original,(self.root/'manifest.json').read_bytes())
        self.assertEqual(self.before,bundle.sha256(self.root/'knowledge_base_v2/character_knowledge_v2.sqlite3'))
        self.assertEqual(536,self.report['counts']['source_claims'])
        self.assertEqual(3,self.report['counts']['authorized_claims'])
        self.assertEqual('hash_ngram',self.manifest['embedding']['type'])
        self.assertIn('semantic_index',self.manifest['paths'])
        reviews=bundle.load_json(self.root/self.manifest['paths']['reviewed_facts'])
        self.assertEqual(3,len(reviews['facts']))
        self.assertTrue(all(r['migration']['knowledge_basis_preserved'] for r in reviews['facts']))
        self.assertTrue(all(r['migration']['activation_basis']=='unchanged_prior_review_exact_quote_v1' and
                            r['migration']['human_independent_review']=='pending' for r in reviews['facts']))
    def test_tampered_projection_rejected_after_rehash(self):
        path=self.manifest['paths']['candidate_v2'];x=bundle.load_json(self.root/path)
        x['evidence'][0]['source_projection']['line_spans'][0]['start']+=1
        bundle.write_json(self.root/path,x);self.rehash(path)
        with self.assertRaisesRegex(ValueError,'generated export mismatch'):
            bundle.check(self.root)
    def test_tampered_review_rejected_after_rehash(self):
        path=self.manifest['paths']['reviewed_facts'];x=bundle.load_json(self.root/path)
        x['facts'][0]['statement']='伪造事实'
        bundle.write_json(self.root/path,x);self.rehash(path)
        with self.assertRaisesRegex(ValueError,'generated export mismatch'):
            bundle.check(self.root)
    def test_tampered_model_metadata_rejected(self):
        path=self.manifest['paths']['semantic_index'];x=bundle.load_json(self.root/path)
        x['model_revision']='wrong'
        bundle.write_json(self.root/path,x);self.rehash(path)
        with self.assertRaisesRegex(ValueError,'source audit failed'):
            bundle.check(self.root)
    def test_installer_staging_rejects_stale_data(self):
        assets=Path(self.temporary.name)/'build/generated/databaseAssets'
        bundle.stage_apk_assets(self.root,assets)
        before=(assets/'database/manifest.json').read_bytes()
        (self.root/self.manifest['paths']['v2_database']).write_bytes(b'corrupt')
        with self.assertRaisesRegex(ValueError,'file/hash mismatch'):
            bundle.stage_apk_assets(self.root,assets)
        self.assertEqual(before,(assets/'database/manifest.json').read_bytes())

    def test_prepare_rolls_back_when_a_file_replacement_fails(self):
        paths = ['manifest.json', 'runtime/android/scene_candidates.json', 'runtime/android/knowledge_fallback.json']
        before = {name: bundle.sha256(self.root/name) for name in paths}
        actual_replace = os.replace
        calls = 0
        def replace_once_then_fail(source, target):
            nonlocal calls
            calls += 1
            if calls == 2:
                raise OSError('simulated replacement failure')
            return actual_replace(source, target)
        with mock.patch.object(prepare_database_v22.os, 'replace', side_effect=replace_once_then_fail):
            with self.assertRaisesRegex(OSError, 'simulated replacement failure'):
                bundle.prepare(self.root, 'three_body_2', 'v22-failed-test', 'luo_ji')
        self.assertEqual(before, {name: bundle.sha256(self.root/name) for name in paths})
        self.assertEqual(self.manifest, bundle.check(self.root))

    def test_source_symlink_is_rejected(self):
        target = Path(self.temporary.name)/'external.txt';target.write_text('outside')
        (self.root/'knowledge_base_v2'/'unexpected-link').symlink_to(target)
        with self.assertRaisesRegex(ValueError, 'symlink'):
            bundle.prepare(self.root, 'three_body_2', 'v22-test', 'luo_ji')

    def test_source_directory_symlink_is_rejected(self):
        cards = self.root/'characters'
        original = self.root/'cards-real'
        cards.rename(original)
        cards.symlink_to(original, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, 'symlink'):
            bundle.prepare(self.root, 'three_body_2', 'v22-test', 'luo_ji')

if __name__=='__main__':unittest.main()

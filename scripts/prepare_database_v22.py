"""V2.2 bundle adapter. Transitional runtime index remains explicitly hash_ngram.

Raw BGE vectors stay inside the hashed SQLite; an Android BGE encoder is a
separate phase. Only previously reviewed atomic facts are carried forward.
"""
from contextlib import closing
import hashlib
import json
import os
import re
import shutil
from pathlib import Path
import sqlite3
import struct
import tempfile

import prepare_database_bundle as legacy
from prepare_v22_migration import build_stage

SOURCE_SCHEMA = '2.2.0'
PATHS = dict(legacy.PATHS, reviewed_facts='runtime/android/reviewed_facts_v22.json',
             prior_reviews='reviewed/atomic_facts.json',
             source_corpus='knowledge_base_v2/corpus/documents.jsonl',
             semantic_index='knowledge_base_v2/index/semantic_index_manifest.json',
             bge_model='embedding/bge-small-zh-v1.5/model.onnx',
             bge_vocab='embedding/bge-small-zh-v1.5/vocab.txt',
             bge_tokenizer='embedding/bge-small-zh-v1.5/tokenizer.json',
             bge_model_manifest='embedding/bge-small-zh-v1.5/model_manifest.json',
             bge_index='runtime/android/vector/bge_index.json')
OLD_REVIEWS = 'reviewed/atomic_facts.json'


def payload(root, dataset_id, version, default_character):
    root = root.resolve()
    for directory in ('knowledge_base_v2', 'characters', 'story', 'reviewed', 'embedding'):
        legacy.require(not (root / directory).is_symlink(), f'V2.2 source symlink is not allowed: {directory}')
        legacy.resolve(root, directory)
        for path in (root / directory).rglob('*'):
            legacy.require(not path.is_symlink(), f'V2.2 source symlink is not allowed: {path}')
    stage = build_stage(root)
    legacy.require(stage['dataset_id'] == dataset_id and stage['source_schema'] == SOURCE_SCHEMA, 'V2.2 source identity mismatch')
    cards = [legacy.load_json(p) for p in sorted((root / 'characters').glob('*.json'))]
    default = next((x for x in cards if x['npc_id'] == default_character), None)
    legacy.require(default is not None, 'Default character not in database')
    source = legacy.load_json(root / legacy.PATHS['v2_manifest'])
    source_hash = legacy.sha256(root / legacy.PATHS['v2_database'])
    legacy.require(source_hash == stage['source_sqlite_sha256'], 'V2.2 source changed')
    model = legacy.load_json(legacy.resolve(root,PATHS['bge_model_manifest']))
    descriptor = stage['semantic_index']
    legacy.require(model['schema_version']=='bge-onnx-v1' and model['model_name']==descriptor['model_name'] and
        model['model_revision']==descriptor['model_revision'] and model['dimensions']==descriptor['dimension'] and
        model['query_instruction']==descriptor['query_instruction'] and model['pooling']==descriptor['pooling'] and
        model['normalized'] is True and model['lowercase'] is True and model['max_length']==512,
        'BGE model/index contract mismatch')
    for key, digest_key in [('bge_model','model_onnx_sha256'),('bge_vocab','vocab_sha256'),('bge_tokenizer','tokenizer_json_sha256')]:
        legacy.require(legacy.sha256(legacy.resolve(root,PATHS[key]))==model[digest_key], f'BGE artifact hash mismatch: {key}')
    with closing(sqlite3.connect((root / legacy.PATHS['v2_database']).as_uri() + '?mode=ro', uri=True)) as db:
        db.row_factory = sqlite3.Row
        raw_claims = {r['claim_id']: dict(r) for r in db.execute('select * from claims')}
        vectors = {r['evidence_id']: bytes(r['vector']) for r in db.execute(
            'select evidence_id,vector from evidence_embeddings where index_id=? order by evidence_id',
            (descriptor['index_id'],))}
    claims, evidence = [], []
    for item in stage['evidence']:
        projection = item['source_projection']
        claim = raw_claims[item['claim_id']].copy()
        claim['subject_ids_json'] = json.loads(claim['subject_ids_json'])
        claim['keywords_json'] = json.loads(claim['keywords_json'])
        # Retrieval candidates are source projections, never generated summaries.
        claim['statement'] = projection['text']
        claim['object_text'] = projection['text']
        claims.append(claim)
        evidence.append({'evidence_id':item['evidence_id'], 'claim_id':item['claim_id'],
            'source_id':item['source_id'], 'line_start':item['line_start'], 'line_end':item['line_end'],
            'sha256':projection['text_sha256'], 'quote':item['quote'], 'source_quote':item['quote'],
            'source_quote_sha256':item['quote_sha256'], 'source_projection':projection})
    claims.sort(key=lambda x:x['claim_id'])
    evidence.sort(key=lambda x:x['evidence_id'])
    reviews=[]
    for row in stage['review_candidates']:
        review = row.copy(); migration = dict(review['migration'])
        review['parent_claim_id'] = migration['new_parent_claim_id']
        review['evidence_id'] = migration['new_evidence_id']
        # This preserves only an already-authorized, unchanged atomic fact;
        # it does not approve the broader V2.2 source evidence or any edge.
        migration['requires_independent_review_before_activation'] = False
        migration['activation_basis'] = 'unchanged_prior_review_exact_quote_v1'
        migration['human_independent_review'] = 'pending'
        review['migration'] = migration
        reviews.append(review)
    reviewed={'schema_version':'1.0','dataset_id':dataset_id,'facts':reviews,
              'migration_policy':'prior_review_exact_quote_v1'}
    source_documents = legacy.load_jsonl(root / PATHS['source_corpus'])
    documents={d['source_id']:d['text'].splitlines() for d in source_documents}
    # Reuse the narrow, existing atomic-fact authorization policy.
    source_tuple=(source,claims,stage['source_edges'],evidence,documents,cards,
                  legacy.load_jsonl(root / legacy.PATHS['story_events']),source['counts']['relations'])
    atomic, reviewed_edges = legacy.authorize_reviews(root,dataset_id,source_tuple,reviewed)
    common={'schema_version':SOURCE_SCHEMA,'dataset_id':dataset_id,'dataset_version':version,
            'source_database_sha256':source_hash,'cutoffs':stage['cutoffs']}
    candidates=dict(common,usage='candidate_only_not_character_knowledge',claims=claims,
                    knowledge_edges=stage['source_edges'], evidence=evidence)
    runtime=dict(common,authorization_policy=legacy.POLICY,claims=atomic,knowledge_edges=reviewed_edges)
    index=legacy.build_vector_index(common,claims,evidence,atomic,reviewed)
    semantic_items=[]
    for item in stage['evidence']:
        vector=vectors.get(item['evidence_id'])
        legacy.require(vector is not None and len(vector)==2048,'Missing or invalid BGE evidence vector')
        metadata={'dataset_id':dataset_id,'dataset_version':version,'claim_id':item['claim_id'],
                  'parent_claim_id':item['claim_id'],'evidence_id':item['evidence_id'],
                  'record_kind':'scene_candidate','authorization_required':'true',
                  'source_database_sha256':source_hash}
        semantic_items.append({'chunk':{'id':item['evidence_id'],'text':item['source_projection']['text'],
            'source_path':'database/'+PATHS['v2_database'],'start':item['line_start'],'end':item['line_end'],
            'metadata':metadata},'embedding':list(struct.unpack('<512f',vector))})
    legacy.require(len(semantic_items)==len(vectors)==descriptor['document_count'],'BGE vector inventory mismatch')
    semantic_vector_index={'schema_version':'1.1','dataset_id':dataset_id,'dataset_version':version,
        'source_database_sha256':source_hash,'semantic_index_id':descriptor['index_id'],
        'model_name':descriptor['model_name'],'model_revision':descriptor['model_revision'],
        'dimension':descriptor['dimension'],'items':semantic_items}
    report={'schema_version':'1.0','dataset_id':dataset_id,'source_database_sha256':source_hash,
            'source_schema':SOURCE_SCHEMA,'counts':{'source_claims':len(claims),
            'source_edges':len(stage['source_edges']),'authorized_claims':len(atomic)},
            'vector_items':len(index['items']),'runtime_embedding':'bge_onnx',
            'semantic_sqlite_present':True,'semantic_runtime_connected':True}
    outputs={PATHS['candidate_v2']:candidates,PATHS['runtime_v2']:runtime,
             PATHS['vector_index']:index,PATHS['bge_index']:semantic_vector_index,PATHS['reviewed_facts']:reviewed,
             'runtime/android/compatibility_report.json':report}
    files=[PATHS[k] for k in ('story_events','v2_manifest','v2_database','candidate_v2','runtime_v2',
                                'reviewed_facts','vector_index','source_corpus','semantic_index',
                                'bge_model','bge_vocab','bge_tokenizer','bge_model_manifest','bge_index')]
    files.append(OLD_REVIEWS)
    files += [p.relative_to(root).as_posix() for p in sorted((root/'characters').glob('*.json'))]
    return outputs, sorted(files), default, report


def prepare(root,dataset_id,version,default_character):
    root=root.resolve()
    legacy.require(root.is_dir() and re.fullmatch(r'[a-z0-9_-]+',dataset_id), 'Invalid V2.2 dataset')
    legacy.require(version and '\n' not in version and '\r' not in version, 'Invalid dataset version')
    outputs,files,default,report=payload(root,dataset_id,version,default_character)
    destinations = [*outputs, 'manifest.json']  # manifest is replaced last
    for path in destinations: legacy.resolve(root,path)
    with tempfile.TemporaryDirectory(prefix='.v22-prepare-', dir=root.parent) as directory:
        temporary = Path(directory)
        staged = temporary / 'database'
        shutil.copytree(root, staged, symlinks=True)
        for path,value in outputs.items(): legacy.write_json(legacy.resolve(staged,path),value)
        bundle={'bundle_schema_version':'1.0','dataset_id':dataset_id,'dataset_version':version,
                'default_character_id':default_character,'default_story_cutoff':default['knowledge_scope']['story_cutoff'],
                'authorization_policy':legacy.POLICY,'capabilities':{'vector':True,'v2':True,'v1':False,'timed_state':False},
                'embedding':legacy.EMBEDDING,'paths':PATHS,
                'files':[{'path':path,'sha256':legacy.sha256(legacy.resolve(staged,path))} for path in files]}
        bundle['dataset_fingerprint']=legacy.fingerprint(bundle)
        legacy.write_json(staged/'manifest.json',bundle)
        check(staged)
        backups = temporary / 'backups'
        replaced = []
        try:
            for relative in destinations:
                target = legacy.resolve(root,relative)
                target.parent.mkdir(parents=True, exist_ok=True)
                backup = backups / relative
                existed = target.is_file()
                if existed:
                    backup.parent.mkdir(parents=True,exist_ok=True)
                    shutil.copy2(target,backup)
                os.replace(legacy.resolve(staged,relative),target)
                replaced.append((target,backup,existed))
        except Exception:
            for target,backup,existed in reversed(replaced):
                if existed: os.replace(backup,target)
                elif target.exists(): target.unlink()
            raise
    return bundle,report


def check(root):
    root=root.resolve();bundle=legacy.load_json(root/'manifest.json')
    legacy.require(bundle['bundle_schema_version']=='1.0' and bundle['authorization_policy']==legacy.POLICY and
                   bundle['capabilities']=={'vector':True,'v2':True,'v1':False,'timed_state':False} and
                   bundle['embedding']==legacy.EMBEDDING and bundle['paths']==PATHS, 'V2.2 bundle contract mismatch')
    files=bundle['files'];legacy.require(len({f['path'] for f in files})==len(files),'Duplicate file manifest entries')
    for item in files:
        path=legacy.resolve(root,item['path'])
        legacy.require(path.is_file() and legacy.sha256(path)==item['sha256'],f"Bundle file/hash mismatch: {item['path']}")
    legacy.require(bundle['dataset_fingerprint']==legacy.fingerprint(bundle),'Bundle fingerprint mismatch')
    outputs,expected_files,default,_=payload(root,bundle['dataset_id'],bundle['dataset_version'],bundle['default_character_id'])
    legacy.require(sorted(f['path'] for f in files)==expected_files,'V2.2 bundle file inventory mismatch')
    legacy.require(bundle['default_story_cutoff']==default['knowledge_scope']['story_cutoff'],'Invalid default cutoff')
    for path,value in outputs.items():
        if path.endswith('compatibility_report.json'): continue
        legacy.require(legacy.load_json(root/path)==value,f'V2.2 generated export mismatch: {path}')
    return bundle

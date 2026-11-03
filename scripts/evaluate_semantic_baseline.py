#!/usr/bin/env python3
"""Reproduce the current builder's lexical baseline on reviewed source samples.

No BGE model is loaded. Positive labels are non-exhaustive and not knowledge grants.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import sqlite3
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'three_body_2_kb_builder/scripts'))
from query_evidence import retrieve


def sha(path):
    result = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            result.update(chunk)
    return result.hexdigest()


def run(database, fixture):
    cases = json.loads(fixture.read_text(encoding='utf-8'))
    digest = sha(database)
    if digest != cases['sqlite_sha256']:
        raise ValueError('fixture SQLite fingerprint mismatch; re-audit labels before evaluation')
    groups = {'development': {'scenes': set(), 'entities': set(), 'characters': set()},
              'validation': {'scenes': set(), 'entities': set(), 'characters': set()}}
    with tempfile.TemporaryDirectory(prefix='semantic-lexical-baseline-') as tmp:
        staged = Path(tmp) / 'database.sqlite3'
        shutil.copyfile(database, staged)
        db = sqlite3.connect(staged.as_uri() + '?mode=ro', uri=True)
        db.row_factory = sqlite3.Row
        try:
            output=[]
            for case in cases['cases']:
                group = groups[case['split']]
                group['characters'].add(case['character'])
                cutoff=db.execute('select sequence from cutoffs where event_id=?',(case['cutoff'],)).fetchone()
                if cutoff is None:
                    raise ValueError(f"unknown cutoff: {case['id']}")
                for eid in case.get('forbidden_future_evidence_ids', []):
                    row=db.execute('select line_end from evidence_units where evidence_id=?',(eid,)).fetchone()
                    if row is None or row['line_end'] <= cutoff['sequence']:
                        raise ValueError(f"invalid future evidence label: {case['id']}")
                if case['source'] is not None:
                    eid=case['expected_evidence_ids'][0]
                    row=db.execute('select * from evidence_units where evidence_id=?',(eid,)).fetchone()
                    if row is None or case['expected_answer_substring'] not in row['quote']:
                        raise ValueError(f"unverified evidence or answer: {case['id']}")
                    expected_source=case['source']
                    if any(row[k] != expected_source[k] for k in ('source_id','line_start','line_end','scene_id','event_id','claim_id')):
                        raise ValueError(f"stale source: {case['id']}")
                    if json.loads(row['entity_ids_json']) != case['entity_ids']:
                        raise ValueError(f"stale entities: {case['id']}")
                    if row['line_end'] > cutoff['sequence']:
                        raise ValueError(f"future or unknown positive evidence: {case['id']}")
                    group['scenes'].add(row['scene_id']); group['entities'].update(case['entity_ids'])
                found=retrieve(staged,case['character'],case['cutoff'],case['query'],limit=20,candidate_limit=20,
                               retrieval_mode='lexical',knowledge_policy='approved')
                ids=[item['evidence_id'] for item in found]
                expected=set(case['expected_evidence_ids'])
                rank=next((i+1 for i,eid in enumerate(ids) if eid in expected), None)
                output.append({'id':case['id'],'split':case['split'],'category':case['category'],
                               'expected_evidence_ids':sorted(expected),'rank':rank,'returned_ids':ids,
                               'negative_returned_candidates':bool(ids) if not expected else None,
                               'forbidden_future_returned': [eid for eid in case.get('forbidden_future_evidence_ids', []) if eid in ids]})
        finally:
            db.close()
    overlaps={key:sorted(groups['development'][key] & groups['validation'][key]) for key in ('scenes','entities','characters')}
    if any(overlaps.values()):
        raise ValueError(f'development/validation leakage: {overlaps}')
    return {'dataset_id':cases['dataset_id'],'sqlite_sha256':digest,'fixture_sha256':sha(fixture),
            'backend':'PC builder lexical/approved only; no Android/BGE/model generation',
            'results':output,'split_overlaps':overlaps,
            'limits':['Positive labels are not exhaustive; misses need manual review',
                      'Negative returned candidates are not automatically false evidence or prompt injection',
                      'Source presence is not proof of character knowledge',
                      'Cases are Codex source-reviewed, not independent human epistemic review']}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--database',type=Path,default=ROOT/'database/knowledge_base_v2/character_knowledge_v2.sqlite3')
    parser.add_argument('--fixture',type=Path,default=ROOT/'scripts/fixtures/semantic_v2_2/evidence_cases.json')
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    result=run(args.database,args.fixture)
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    for row in result['results']:
        print(row['id'], 'rank=',row['rank'],'returned=',len(row['returned_ids']))

if __name__=='__main__': main()

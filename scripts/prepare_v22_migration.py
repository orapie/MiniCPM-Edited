#!/usr/bin/env python3
"""Stage V2.2 evidence and existing reviewed facts for inspection; does not activate a bundle.

Inputs are read only. An existing review can be mapped only to a unique original
source quote within the same line range. Source-created edges never authorize facts.
"""
import argparse
from contextlib import closing
import hashlib
import json
from pathlib import Path
import sqlite3
import sys

from audit_semantic_database import audit
from prepare_database_bundle import fingerprint
from v22_evidence_projection import project_quote

ROOT = Path(__file__).resolve().parents[1] / 'database'


def digest(path):
    result = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            result.update(chunk)
    return result.hexdigest()


def read_json(path):
    return json.loads(path.read_text(encoding='utf-8'))


def build_stage(root, snapshot=None):
    root = root.resolve()
    audit_result = audit(root, snapshot=snapshot)
    if audit_result['status'] != 'passed':
        raise ValueError('V2.2 source audit failed: ' + ', '.join(e['code'] for e in audit_result['errors'][:5]))
    bundle_manifest = read_json(root / 'manifest.json')
    if fingerprint(bundle_manifest) != bundle_manifest['dataset_fingerprint']:
        raise ValueError('existing bundle fingerprint does not match its manifest')
    dataset = bundle_manifest['dataset_id']
    source = root / 'knowledge_base_v2'
    source_manifest = read_json(source / 'manifest.json')
    review_path = root / 'reviewed/atomic_facts.json'
    declared_reviews = [item['sha256'] for item in bundle_manifest['files'] if item['path'] == 'reviewed/atomic_facts.json']
    if len(declared_reviews) != 1 or digest(review_path) != declared_reviews[0]:
        raise ValueError('existing review file does not match its recorded bundle hash')
    reviews = read_json(review_path)
    if reviews['schema_version'] != '1.0' or reviews['dataset_id'] != dataset:
        raise ValueError('invalid review identity')
    card_ids = {read_json(path)['npc_id'] for path in (root / 'characters').glob('*.json')}
    documents = {}
    with (source / 'corpus/documents.jsonl').open(encoding='utf-8') as stream:
        for line in stream:
            if line.strip():
                record = json.loads(line)
                documents[record['source_id']] = record['text'].splitlines()
    database = source / 'character_knowledge_v2.sqlite3'
    with closing(sqlite3.connect(database.as_uri() + '?mode=ro', uri=True)) as db:
        db.row_factory = sqlite3.Row
        facts = [dict(row) for row in db.execute('select * from evidence_units order by evidence_id')]
        edges = [dict(row) for row in db.execute('select * from knowledge_edges order by knowledge_edge_id')]
        claims = {r['claim_id']: dict(r) for r in db.execute('select * from claims')}
        evidence = {r['evidence_id']: dict(r) for r in db.execute('select * from evidence')}
    staged_evidence = []
    for item in facts:
        proof = evidence[item['evidence_id']]
        projection = project_quote(documents[item['source_id']], item['line_start'], item['line_end'],
                                   item['quote'], proof['sha256'])
        staged_evidence.append({
            'evidence_id': item['evidence_id'], 'claim_id': item['claim_id'],
            'event_id': item['event_id'], 'scene_id': item['scene_id'], 'source_id': item['source_id'],
            'line_start': item['line_start'], 'line_end': item['line_end'], 'quote': item['quote'],
            'quote_sha256': proof['sha256'], 'title': item['title'], 'fact': item['fact'],
            'source_projection': projection,
            'derived_text_is_authorized_fact': False,
        })
    migrated = []
    seen = set()
    for old in reviews['facts']:
        review_id = old['claim_id']
        if review_id in seen or old['character_id'] not in card_ids or old['review_method'] != 'dialogue_context_inspection' or not old['reviewed_by']:
            raise ValueError(f'invalid old review: {review_id}')
        if old['acquisition_method'] not in {'direct','witnessed','reported','inferred'} or len(old['knowledge_basis']) < 10:
            raise ValueError(f'invalid reviewed knowledge basis: {review_id}')
        if not (0 < len(old['statement']) <= 120):
            raise ValueError(f'invalid reviewed statement: {review_id}')
        matches = []
        for unit in facts:
            if not (unit['line_start'] <= old['line_start'] <= old['line_end'] <= unit['line_end']):
                continue
            lines = documents.get(unit['source_id'], [])
            selected = '\n'.join(lines[old['line_start']-1:old['line_end']]).strip()
            if old['quote'] == selected and old['quote'] in unit['quote']:
                matches.append(unit)
        if len(matches) != 1:
            raise ValueError(f'review mapping is not unique or has no exact quote: {review_id} ({len(matches)})')
        unit = matches[0]
        claim = claims[unit['claim_id']]
        cutoff = source_manifest['cutoffs'].get(unit['event_id'], {}).get('sequence')
        if not isinstance(cutoff, int) or old['line_end'] > cutoff or claim['valid_from_sequence'] > old['line_end']:
            raise ValueError(f'review is outside event time: {review_id}')
        new = dict(old)
        new['migration'] = {'old_parent_claim_id': old['parent_claim_id'], 'old_evidence_id': old['evidence_id'],
                            'source_sqlite_sha256': audit_result['sqlite_sha256'],
                            'new_parent_claim_id': unit['claim_id'], 'new_evidence_id': unit['evidence_id'],
                            'method': 'unique_exact_prior_review_quote_and_source_lines',
                            'knowledge_basis_preserved': True,
                            'requires_independent_review_before_activation': True}
        # Do not change the active review IDs or grant new permissions here.
        migrated.append(new)
        seen.add(review_id)
    if not migrated:
        raise ValueError('no reviewed facts to migrate')
    if digest(database) != audit_result['sqlite_sha256']:
        raise ValueError('source database changed during staging')
    return {'stage_schema':'v22-inspection-1', 'dataset_id':dataset,
            'source_schema':source_manifest['schema_version'], 'source_sqlite_sha256':audit_result['sqlite_sha256'],
            'semantic_index':source_manifest['semantic_index'],
            'cutoffs':source_manifest['cutoffs'], 'evidence':staged_evidence, 'source_edges':edges,
            'review_candidates':migrated, 'activation_allowed':False,
            'warnings':audit_result['warnings']}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source',type=Path,default=ROOT)
    parser.add_argument('--snapshot',type=Path)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    if args.output.resolve().is_relative_to(args.source.resolve()):
        print('Inspection output must be outside the database source directory',file=sys.stderr)
        return 2
    try:
        result=build_stage(args.source,args.snapshot)
    except (OSError,ValueError,KeyError,TypeError,sqlite3.Error,json.JSONDecodeError) as e:
        print(f'V22_INSPECTION_ERROR: {e}',file=sys.stderr)
        return 1
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(result,ensure_ascii=False,sort_keys=True,indent=2)+'\n',encoding='utf-8')
    print(f"STAGED evidence={len(result['evidence'])} edges={len(result['source_edges'])} review_candidates={len(result['review_candidates'])} activation_allowed=false")
    return 0

if __name__=='__main__': sys.exit(main())

#!/usr/bin/env python3
"""Read-only audit of the V2.2 source database; never prepares an Android bundle."""
import argparse
from collections import Counter
import hashlib
import json
import math
from pathlib import Path
import sqlite3
import struct
import sys

ROOT = Path(__file__).resolve().parents[1] / 'database'


def sha(data):
    return hashlib.sha256(data).hexdigest()


def read_json(path):
    return json.loads(path.read_text(encoding='utf-8'))


def read_jsonl(path):
    with path.open(encoding='utf-8') as stream:
        return [json.loads(line) for line in stream if line.strip()]


def audit(root, database=None, snapshot=None):
    root = root.resolve()
    source = root / 'knowledge_base_v2'
    database = (database or source / 'character_knowledge_v2.sqlite3').resolve()
    manifest = read_json(source / 'manifest.json')
    semantic = read_json(source / 'index/semantic_index_manifest.json')
    failures, warnings = [], []
    def check(condition, code, detail=None):
        if not condition:
            failures.append({'code': code, **({'detail': detail} if detail is not None else {})})
    check(manifest.get('schema_version') == '2.2.0' and manifest.get('knowledge_base_version') == 'v2', 'source_schema')
    check(manifest.get('semantic_index') == semantic, 'semantic_manifest_mismatch')
    cards = {x['npc_id'] for x in (read_json(p) for p in sorted((root / 'characters').glob('*.json')))}
    check(bool(cards), 'missing_cards')
    documents = read_jsonl(source / 'corpus/documents.jsonl')
    corpus = {x['source_id']: x for x in documents}
    check(len(corpus) == len(documents), 'duplicate_corpus_id')
    for source_id, doc in corpus.items():
        text = doc['text']
        check(sha(text.encode('utf-8')) == doc['sha256'] and len(text) == doc['char_count'] and
              len(text.split('\n')) == doc['line_count'], 'corpus_hash_or_length', source_id)
    db = sqlite3.connect(database.as_uri() + '?mode=ro', uri=True)
    db.row_factory = sqlite3.Row
    try:
        check(db.execute('PRAGMA integrity_check').fetchone()[0] == 'ok', 'sqlite_integrity')
        tables = {r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type IN ('table','view')")}
        required = {'documents','scenes','events','claims','evidence','evidence_units','knowledge_edges',
                    'retrieval_expressions','embedding_indexes','evidence_embeddings','cutoffs'}
        check(required <= tables, 'missing_tables', sorted(required - tables))
        if not required <= tables:
            return {'status': 'failed', 'errors': failures, 'warnings': warnings}
        counts = {t: db.execute(f'SELECT count(*) FROM "{t}"').fetchone()[0] for t in sorted(required)}
        for table in ('documents', 'scenes', 'events', 'claims', 'evidence', 'evidence_units', 'knowledge_edges', 'retrieval_expressions'):
            check(manifest['counts'].get(table) == counts[table], 'manifest_count_mismatch', table)
        dbdocs = {x['source_id']: dict(x) for x in db.execute('select * from documents')}
        check(set(dbdocs) == set(corpus), 'document_inventory_mismatch')
        for key in set(dbdocs) & set(corpus):
            check(all(corpus[key].get(k) == v for k,v in dbdocs[key].items()), 'document_metadata_mismatch', key)
        cutoffs = {r['event_id']: {'story_order': r['story_order'], 'sequence': r['sequence']} for r in db.execute('select * from cutoffs')}
        check(cutoffs == manifest.get('cutoffs'), 'cutoff_manifest_mismatch')
        check(all(isinstance(v['sequence'], int) and v['sequence'] > 0 for v in cutoffs.values()), 'invalid_cutoff')
        checks = {
            'orphan_expression': 'select count(*) from retrieval_expressions r left join evidence_units e on r.evidence_id=e.evidence_id where e.evidence_id is null',
            'orphan_embedding': 'select count(*) from evidence_embeddings x left join evidence_units e on x.evidence_id=e.evidence_id where e.evidence_id is null',
            'orphan_evidence': 'select count(*) from evidence_units u left join evidence e on u.evidence_id=e.evidence_id left join claims c on u.claim_id=c.claim_id left join scenes s on u.scene_id=s.scene_id left join events v on u.event_id=v.event_id where e.evidence_id is null or c.claim_id is null or s.scene_id is null or v.event_id is null',
            'inconsistent_evidence': 'select count(*) from evidence_units u join evidence e on u.evidence_id=e.evidence_id join claims c on u.claim_id=c.claim_id where u.claim_id!=e.claim_id or u.event_id!=c.event_id or u.source_id!=e.source_id or u.line_start!=e.line_start or u.line_end!=e.line_end or u.quote!=e.quote',
            'bad_evidence_time': 'select count(*) from evidence_units u left join cutoffs c on u.event_id=c.event_id where c.event_id is null or u.line_start<1 or u.line_end<u.line_start or u.sequence is null or u.line_end>c.sequence',
            'bad_edge_reference': 'select count(*) from knowledge_edges k left join evidence_units u on k.evidence_id=u.evidence_id left join claims c on k.claim_id=c.claim_id left join cutoffs t on k.acquired_at_event=t.event_id where u.evidence_id is null or c.claim_id is null or k.claim_id!=u.claim_id or t.event_id is null or k.acquired_sequence is null or k.acquired_sequence<1 or k.acquired_sequence>t.sequence',
        }
        for code, query in checks.items():
            n = db.execute(query).fetchone()[0]
            check(n == 0, code, n)
        derived_text_observations = {
            'statement_differs_from_quote': db.execute('select count(*) from claims c join evidence e on c.claim_id=e.claim_id where c.statement!=e.quote').fetchone()[0],
            'fact_ends_with_ellipsis': db.execute("select count(*) from evidence_units where fact like '%…'").fetchone()[0],
        }
        edges = [dict(r) for r in db.execute('select * from knowledge_edges')]
        check(all(e['character_id'] in cards for e in edges), 'unknown_edge_character')
        check(all(e['authorization_status'] in {'approved','candidate'} and e['belief_status'] in {'known','believed','unreviewed'} for e in edges), 'unknown_edge_status')
        evidence = {r['evidence_id']: dict(r) for r in db.execute('select * from evidence')}
        for eid, e in evidence.items():
            doc = corpus.get(e['source_id'])
            if not doc:
                check(False, 'evidence_source_missing', eid); continue
            lines = doc['text'].splitlines()
            start, end, quote = e['line_start'], e['line_end'], e['quote']
            valid = isinstance(start,int) and isinstance(end,int) and 1 <= start <= end <= len(lines)
            check(valid and bool(quote) and quote in ''.join(line.strip() for line in lines[start-1:end]) and
                  sha(quote.encode('utf-8')) == e['sha256'], 'evidence_quote_or_range', eid)
        # JSONL schemas are source-specific projections; validate every declared field.
        mirrors = {
            'corpus/scenes.jsonl': ('scenes', {}),
            'corpus/evidence_units.jsonl': ('evidence_units', {}),
            'corpus/utterances.jsonl': ('utterances', {}),
            'index/retrieval_expressions.jsonl': ('retrieval_expressions', {}),
            'graph/claims.jsonl': ('claims', {'fact':'statement','sequence':'valid_from_sequence'}),
            'graph/knowledge_edges.jsonl': ('knowledge_edges', {'event_id':'acquired_at_event','story_order':'acquired_story_order','sequence':'acquired_sequence'}),
            'graph/events.jsonl': ('events', {}),
            'graph/evidence.jsonl': ('evidence', {}),
            'graph/aliases.jsonl': ('aliases', {}),
            'graph/entities.jsonl': ('entities', {}),
            'graph/entity_mentions.jsonl': ('entity_mentions', {}),
            'graph/relations.jsonl': ('relations', {}),
            'graph/character_states.jsonl': ('character_states', {}),
        }
        for path, (table, mapping) in mirrors.items():
            rows = read_jsonl(source / path)
            actual = [dict(x) for x in db.execute(f'SELECT * FROM "{table}"')]
            if not rows and not actual: continue
            key = next(iter(rows[0])) if rows else ''
            by_id = {r[key]: r for r in actual if key in r}
            valid = len(rows) == len(actual) == len(by_id) and len({r[key] for r in rows}) == len(rows)
            for row in rows:
                found = by_id.get(row[key])
                if found is None:
                    valid = False; break
                for field, expected in row.items():
                    if path == 'corpus/scenes.jsonl' and field == 'segmentation_method':
                        if expected != 'paragraph_target_chars': valid = False
                        continue
                    column = mapping.get(field, field)
                    if column not in found and column + '_json' in found:
                        value = json.loads(found[column + '_json'])
                    elif column in found:
                        value = found[column]
                    else:
                        valid = False; break
                    if value != expected:
                        valid = False; break
                if not valid: break
            check(valid, 'mirror_mismatch', path)
        indexes = [dict(r) for r in db.execute("select * from embedding_indexes where status='active'")]
        check(len(indexes) == 1, 'active_index_count', len(indexes))
        vector_payload_sha256 = None
        if len(indexes) == 1:
            index = indexes[0]
            for key, value in semantic.items():
                if key != 'storage':
                    check(index.get(key) == value, 'index_metadata_mismatch', key)
            check(index['dtype'] == 'float32_le' and index['dimension'] == 512 and index['normalized'] == 1 and
                  index['pooling'] == 'cls' and index['content_template'] == '{title}\n{fact}', 'unsupported_vector_contract')
            rows = db.execute('select e.evidence_id,e.content_sha256,e.vector,u.title,u.fact from evidence_embeddings e left join evidence_units u on e.evidence_id=u.evidence_id where e.index_id=? order by e.evidence_id',(index['index_id'],)).fetchall()
            check(len(rows) == index['document_count'] == counts['evidence_units'], 'embedding_count_mismatch')
            norms=[]; payload=[]
            vector_digest=hashlib.sha256()
            for r in rows:
                eid=r['evidence_id']; title=r['title']; fact=r['fact']; blob=r['vector']
                if isinstance(blob, bytes):
                    vector_digest.update(eid.encode('utf-8') + b'\0' + len(blob).to_bytes(8, 'big') + blob)
                if title is None or fact is None:
                    check(False, 'embedding_missing_evidence', eid); continue
                expected=sha((title+'\n'+fact).encode('utf-8'))
                check(expected == r['content_sha256'], 'embedding_content_hash', eid)
                payload.append(f'{eid}\t{expected}')
                if not isinstance(blob, bytes) or len(blob) != 2048:
                    check(False, 'embedding_blob_size', eid); continue
                vector=struct.unpack('<512f', blob)
                if not all(math.isfinite(v) for v in vector):
                    check(False, 'embedding_nonfinite', eid); continue
                norm=math.sqrt(sum(v*v for v in vector)); norms.append(norm)
                check(abs(norm-1.0) <= 1e-5, 'embedding_norm', eid)
            check(sha('\n'.join(payload).encode('utf-8')) == index['content_sha256'], 'index_content_hash')
            vector_payload_sha256 = vector_digest.hexdigest()
        else:
            index = {}; norms=[]
        sqlite_sha256 = sha(database.read_bytes())
        if snapshot is not None:
            pinned=read_json(snapshot)
            check(pinned.get('sqlite_sha256') == sqlite_sha256, 'snapshot_sqlite_mismatch')
            check(pinned.get('index_id') == index.get('index_id'), 'snapshot_index_mismatch')
            check(pinned.get('vector_payload_sha256') == vector_payload_sha256, 'snapshot_vector_mismatch')
        else:
            warnings.append({'code':'vector_bytes_not_compared_to_pinned_snapshot'})
        # Installed bundle mismatch is reported separately: it must not turn an
        # internally sound source audit into an assertion that Android can load it.
        outer=read_json(root/'manifest.json'); mismatches=[]
        for f in outer['files']:
            p=root/f['path']
            if not p.is_file() or sha(p.read_bytes()) != f['sha256']: mismatches.append(f['path'])
        if mismatches: warnings.append({'code':'android_bundle_hash_mismatch', 'paths':mismatches})
        warnings.append({'code':'android_bundle_compatibility_not_checked', 'version':manifest.get('schema_version')})
        reviews=read_json(root/'reviewed/atomic_facts.json')['facts']
        old_refs=[]
        for f in reviews:
            matches=[]
            for e in evidence.values():
                if e['line_start'] <= f['line_start'] <= f['line_end'] <= e['line_end'] and f['quote'] in e['quote']:
                    matches.append({'evidence_id':e['evidence_id'],'claim_id':e['claim_id'], 'line_start':e['line_start'], 'line_end':e['line_end']})
            old_refs.append({'reviewed_claim_id':f['claim_id'],'old_parent_exists':bool(db.execute('select 1 from claims where claim_id=?',(f['parent_claim_id'],)).fetchone()),
                             'old_evidence_exists':f['evidence_id'] in evidence,'candidate_matches':matches,'migration_status':'requires_manual_review'})
        return {'status':'passed' if not failures else 'failed', 'dataset_id':outer['dataset_id'],
                'sqlite_sha256':sqlite_sha256, 'source_schema':manifest.get('schema_version'),
                'index_id':index.get('index_id'), 'model_name':index.get('model_name'), 'model_revision':index.get('model_revision'),
                'vector_payload_sha256':vector_payload_sha256,
                'counts':counts, 'norm_min':min(norms) if norms else None,'norm_max':max(norms) if norms else None,
                'derived_text_observations':derived_text_observations,
                'edge_authorization_counts':dict(Counter(e['authorization_status'] for e in edges)),
                'old_review_mappings':old_refs, 'errors':failures, 'warnings':warnings,
                'limits':['structural validation does not prove model provenance, entailment, character knowledge or retrieval quality',
                          'candidate mappings are not authorization grants','cross-cutoff evidence is rejected for roleplay; no safe partial quote inferred']}
    finally:
        db.close()


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--root',type=Path,default=ROOT)
    p.add_argument('--database',type=Path,help='SQLite override for isolated corruption tests')
    p.add_argument('--snapshot',type=Path,help='Previously recorded SQLite and vector byte hashes')
    p.add_argument('--output',type=Path,required=True,help='Audit report outside the source database directory')
    a=p.parse_args()
    if a.output.resolve().is_relative_to(a.root.resolve()):
        print('Audit report must be outside the source database directory', file=sys.stderr)
        return 2
    try:
        result=audit(a.root,a.database,a.snapshot)
    except (OSError,ValueError,KeyError,TypeError,sqlite3.Error,json.JSONDecodeError) as e:
        result={'status':'failed','errors':[{'code':'audit_exception','detail':str(e)}]}
    a.output.parent.mkdir(parents=True,exist_ok=True)
    a.output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'status':result['status'],'errors':len(result['errors']),'warnings':len(result.get('warnings',[]))},ensure_ascii=False))
    return 0 if result['status']=='passed' else 1

if __name__=='__main__':
    sys.exit(main())

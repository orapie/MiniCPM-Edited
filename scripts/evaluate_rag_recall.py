#!/usr/bin/env python3
"""Compare local indexes on a small labeled recall fixture; never assesses authorization."""
import argparse
import hashlib
import json
from pathlib import Path
import time
from prepare_database_bundle import hash_embedding


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--index', type=Path, required=True)
    p.add_argument('--cases', type=Path, default=Path(__file__).parent / 'fixtures/rag_relevance_cases.json')
    p.add_argument('--output', type=Path, required=True)
    a = p.parse_args()
    started = time.perf_counter()
    index = json.loads(a.index.read_text(encoding='utf-8'))
    load_ms = (time.perf_counter() - started) * 1000
    cases = json.loads(a.cases.read_text(encoding='utf-8'))
    if index['dataset_id'] != cases['dataset_id']:
        raise ValueError('Dataset mismatch')
    rows = []
    for case in cases['cases']:
        started = time.perf_counter()
        vector = hash_embedding(case['query'])
        parent_scores = {key: sum(x*y for x, y in zip(vector, value))
                         for key, value in index.get('parent_embeddings', {}).items()}
        ranked = []
        for item in index['items']:
            chunk = item['chunk']
            score = sum(x*y for x, y in zip(vector, item['embedding']))
            parent = chunk['metadata'].get('claim_id')
            if chunk['metadata'].get('record_kind') == 'scene_candidate' and parent in parent_scores:
                score = 0.8 * parent_scores[parent] + 0.2 * score
            ranked.append((score, chunk))
        ranked.sort(key=lambda item: (-item[0], item[1]['id']))
        seen = set()
        candidates = []
        for score, chunk in ranked:
            meta = chunk['metadata']
            parent = meta.get('claim_id', chunk['id'])
            key = (meta['record_kind'], parent)
            if key in seen:
                continue
            seen.add(key)
            if score >= 0.10:
                candidates.append({'id': chunk['id'], 'parent': parent, 'score': round(score, 6)})
            if len(seen) >= 20:  # matches VectorIndex topK before threshold
                break
        expected = set(case['expected_parent_ids'])
        positions = [n+1 for n, c in enumerate(candidates) if c['parent'] in expected]
        rows.append({'id': case['id'], 'split': case['split'], 'character': case['character'],
                     'cutoff': case['cutoff'], 'expected': sorted(expected),
                     'rank': min(positions) if positions else None, 'top20': candidates,
                     'negative_has_candidates': bool(candidates) if not expected else None,
                     'elapsed_ms': round((time.perf_counter() - started)*1000, 3)})
    output = {'dataset_id': index['dataset_id'], 'schema': index['schema_version'],
              'index_sha256': hashlib.sha256(a.index.read_bytes()).hexdigest(),
              'index_bytes': a.index.stat().st_size, 'index_load_ms': round(load_ms, 3), 'cases': rows}
    a.output.parent.mkdir(parents=True, exist_ok=True)
    a.output.write_text(json.dumps(output, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    for row in rows:
        print(row['id'], 'rank=', row['rank'], 'candidates=', len(row['top20']))


if __name__ == '__main__':
    main()

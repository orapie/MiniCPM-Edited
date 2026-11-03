#!/usr/bin/env python3
"""Diagnostic vector/lexical comparison; never changes the production ranker.

Labels identify relevant parent candidates, not authorization or answer correctness.
Use independent, source-reviewed development/validation cases before deployment.
"""
import argparse
import json
import re
import time
from pathlib import Path
from prepare_database_bundle import hash_embedding, sha256


def terms(text):
    result = []
    for match in re.findall(r'[\u3400-\u9fff]+|[A-Za-z0-9_]+', text.lower()):
        if all('\u3400' <= c <= '\u9fff' for c in match):
            result.extend(match[i:i + 2] for i in range(len(match) - 1))
            if len(match) >= 3:
                result.append(match)
        else:
            result.append(match)
    return [term for term in result if len(term) >= 2]


def lexical(query_terms, text):
    if not query_terms:
        return 0.0
    text_terms = set(terms(text))
    return sum(term in text_terms for term in query_terms) / len(query_terms)


def rank(index, case, weight):
    query = hash_embedding(case['query'])
    query_terms = terms(case['query'])
    parent_scores = {key: sum(a * b for a, b in zip(query, value))
                     for key, value in index.get('parent_embeddings', {}).items()}
    candidates = []
    for item in index['items']:
        chunk = item['chunk']
        metadata = chunk['metadata']
        chunk_score = sum(a * b for a, b in zip(query, item['embedding']))
        parent_score = parent_scores.get(metadata.get('claim_id')) if metadata.get('record_kind') == 'scene_candidate' else None
        vector_score = chunk_score if parent_score is None else 0.8 * parent_score + 0.2 * chunk_score
        lexical_score = lexical(query_terms, chunk['text'])
        candidates.append({'id': chunk['id'], 'parent': metadata.get('claim_id', chunk['id']),
                           'vector': vector_score, 'chunk': chunk_score, 'parent_score': parent_score,
                           'lexical': lexical_score, 'score': vector_score + weight * lexical_score})
    candidates.sort(key=lambda row: (-row['score'], row['id']))
    seen, unique = set(), []
    for candidate in candidates:
        if candidate['parent'] in seen:
            continue
        seen.add(candidate['parent'])
        unique.append(candidate)
        if len(unique) == 20:
            break
    # For the baseline this exactly follows the existing score threshold. A
    # new ranker's threshold would need independent calibration, not reuse.
    expected = set(case['expected_parent_ids'])
    return {'id': case['id'], 'split': case['split'], 'expected': sorted(expected),
            'rank': next((i + 1 for i, row in enumerate(unique) if row['parent'] in expected), None),
            'top20': unique}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--index', type=Path, required=True)
    parser.add_argument('--cases', type=Path, default=Path('scripts/fixtures/rag_relevance_cases.json'))
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    index, cases = (json.loads(path.read_text(encoding='utf-8')) for path in (args.index, args.cases))
    if index['dataset_id'] != cases['dataset_id']:
        raise ValueError('dataset mismatch')
    start = time.perf_counter()
    weights = (0.0, 0.1, 0.2, 0.4)
    rows = {str(weight): [rank(index, case, weight) for case in cases['cases']] for weight in weights}
    result = {'dataset_id': index['dataset_id'], 'index_sha256': sha256(args.index),
              'cases_sha256': sha256(args.cases), 'elapsed_ms': round((time.perf_counter() - start) * 1000, 1),
              'caveat': 'Diagnostic only; rank before threshold. No authorization, final prompt or model quality evaluation.',
              'runs': rows}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    for weight, batch in rows.items():
        print('weight', weight, ' '.join(f"{row['id']}={row['rank']}" for row in batch))


if __name__ == '__main__':
    main()

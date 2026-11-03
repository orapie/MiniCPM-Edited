import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from evaluate_rag_rankers import lexical, rank, terms
from prepare_database_bundle import hash_embedding


class RankerDiagnosticTest(unittest.TestCase):
    def test_lexical_counts_query_terms_and_handles_empty_query(self):
        self.assertEqual(1.0, lexical(terms('大学社会学'), '大学社会学'))
        self.assertEqual(0.0, lexical([], '大学社会学'))
        self.assertEqual(0.0, lexical(terms('社会学'), '大学物理'))

    def test_baseline_deduplicates_parent_and_breaks_ties_by_id(self):
        vector = hash_embedding('大学社会学')
        def item(name, parent):
            return {'chunk': {'id': name, 'text': '大学社会学', 'metadata': {
                'claim_id': parent, 'record_kind': 'scene_candidate'}}, 'embedding': vector}
        index = {'parent_embeddings': {'parent-a': vector, 'parent-b': vector},
                 'items': [item('z', 'parent-a'), item('b', 'parent-b'), item('a', 'parent-a')]}
        case = {'id': 'example', 'split': 'development', 'query': '大学社会学',
                'expected_parent_ids': ['parent-b']}
        result = rank(index, case, 0.0)
        self.assertEqual(['a', 'b'], [row['id'] for row in result['top20']])
        self.assertEqual(2, result['rank'])


if __name__ == '__main__':
    unittest.main()

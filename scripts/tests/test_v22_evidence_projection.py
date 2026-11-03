import hashlib
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from v22_evidence_projection import project_quote


def checksum(value): return hashlib.sha256(value.encode('utf-8')).hexdigest()


class EvidenceProjectionTest(unittest.TestCase):
    def test_partial_lines_blank_line_and_supplementary_character(self):
        lines = ['  前文😀甲乙 ', '   ', '\t丙丁后文  ']
        quote = '甲乙丙丁'
        result = project_quote(lines, 1, 3, quote, checksum(quote))
        self.assertEqual('甲乙\n\n丙丁', result['text'])
        self.assertEqual([{'line':1,'start':5,'end':7}, {'line':3,'start':1,'end':3}], result['line_spans'])
        self.assertEqual(quote, ''.join(lines[s['line']-1][s['start']:s['end']] for s in result['line_spans']))
        self.assertEqual(checksum(result['text']), result['text_sha256'])
        self.assertEqual('unicode_code_point', result['offset_unit'])

    def test_wrong_hash_and_ambiguous_quote_rejected(self):
        with self.assertRaisesRegex(ValueError, 'hash mismatch'):
            project_quote(['甲乙'], 1, 1, '甲乙', checksum('wrong'))
        with self.assertRaisesRegex(ValueError, 'not found uniquely'):
            project_quote(['甲乙甲乙'], 1, 1, '甲乙', checksum('甲乙'))
        with self.assertRaisesRegex(ValueError, 'not found uniquely'):
            project_quote(['甲乙'], 1, 1, '丙丁', checksum('丙丁'))

    def test_range_must_match_actual_lines(self):
        with self.assertRaisesRegex(ValueError, 'broader'):
            project_quote(['甲乙', '丙丁'], 1, 2, '甲乙', checksum('甲乙'))
        with self.assertRaisesRegex(ValueError, 'invalid source line range'):
            project_quote(['甲乙'], 0, 1, '甲乙', checksum('甲乙'))
        with self.assertRaisesRegex(ValueError, 'empty'):
            project_quote(['甲乙'], 1, 1, '', checksum(''))


if __name__ == '__main__': unittest.main()

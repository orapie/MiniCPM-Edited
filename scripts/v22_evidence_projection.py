"""Map V2.2 joined-line evidence to an explicit, verifiable source projection.

This module prepares data only. A projected source span is not a knowledge grant.
Offsets are Unicode code points in the unmodified source line; end is exclusive.
"""
import hashlib


def project_quote(lines, line_start, line_end, quote, quote_sha256):
    if not isinstance(line_start, int) or not isinstance(line_end, int) or not (1 <= line_start <= line_end <= len(lines)):
        raise ValueError('invalid source line range')
    if not isinstance(quote, str) or not quote:
        raise ValueError('empty source quote')
    if hashlib.sha256(quote.encode('utf-8')).hexdigest() != quote_sha256:
        raise ValueError('source quote hash mismatch')
    original = lines[line_start - 1:line_end]
    stripped = [line.strip() for line in original]
    joined = ''.join(stripped)
    start = joined.find(quote)
    if start < 0 or joined.find(quote, start + 1) >= 0:
        raise ValueError('source quote not found uniquely within declared lines')
    end = start + len(quote)
    position = 0
    pieces = []
    spans = []
    for index, (raw, trimmed) in enumerate(zip(original, stripped)):
        left = max(0, start - position)
        right = min(len(trimmed), end - position)
        if right > left:
            offset = len(raw) - len(raw.lstrip())
            piece = trimmed[left:right]
            pieces.append(piece)
            spans.append({'line': line_start + index, 'start': offset + left, 'end': offset + right})
        else:
            pieces.append('')
        position += len(trimmed)
    if not spans or spans[0]['line'] != line_start or spans[-1]['line'] != line_end:
        raise ValueError('declared source line range is broader than the actual quote')
    if ''.join(pieces) != quote:
        raise ValueError('source quote reconstruction mismatch')
    text = '\n'.join(pieces)
    return {
        'text': text,
        'text_sha256': hashlib.sha256(text.encode('utf-8')).hexdigest(),
        'original_quote_sha256': quote_sha256,
        'line_start': line_start,
        'line_end': line_end,
        'offset_unit': 'unicode_code_point',
        'line_spans': spans,
        'projection': 'trim_lines_then_preserve_line_boundaries_v1',
    }

#!/usr/bin/env python3
"""Export the portable V2 fact/knowledge-edge subset used by Android.

This is a build-time utility.  The Android app never writes or queries the
source SQLite database; it verifies and reads the generated JSON instead when
the platform SQLite build cannot execute V2's FTS5/trigram MATCH query.
"""

import argparse
import hashlib
import json
import sqlite3
from pathlib import Path


CLAIM_COLUMNS = (
    "claim_id, claim_scope, subject_ids_json, predicate, object_text, statement, "
    "event_id, modality, polarity, valid_from_sequence, valid_to_sequence, "
    "importance, confidence, keywords_json, source_tier, canon_status, verification_status"
)
EDGE_COLUMNS = (
    "knowledge_edge_id, character_id, claim_id, acquisition_method, acquired_at_event, "
    "acquired_story_order, acquired_sequence, confidence, belief_status"
)


def decode_json_columns(record: dict, columns: tuple[str, ...]) -> dict:
    for column in columns:
        record[column] = json.loads(record[column])
    return record


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--database", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    connection = sqlite3.connect(f"file:{args.database}?mode=ro", uri=True)
    connection.row_factory = sqlite3.Row
    try:
        claims = [
            decode_json_columns(dict(row), ("subject_ids_json", "keywords_json"))
            for row in connection.execute(f"SELECT {CLAIM_COLUMNS} FROM claims ORDER BY claim_id")
        ]
        edges = [
            dict(row)
            for row in connection.execute(
                f"SELECT {EDGE_COLUMNS} FROM knowledge_edges "
                "ORDER BY character_id, acquired_sequence, knowledge_edge_id"
            )
        ]
    finally:
        connection.close()

    payload = {
        "schema_version": manifest["schema_version"],
        "source_database_sha256": hashlib.sha256(args.database.read_bytes()).hexdigest(),
        "cutoffs": manifest["cutoffs"],
        "claims": claims,
        "knowledge_edges": edges,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(payload, ensure_ascii=False, separators=(",", ":"), sort_keys=True),
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()

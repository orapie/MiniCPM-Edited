from __future__ import annotations

import argparse
import json
import sqlite3
from pathlib import Path
from typing import Any


BUILDER_ROOT = Path(__file__).resolve().parents[1]
DATA_ROOT = BUILDER_ROOT.parent / "database"
DEFAULT_DATABASE = DATA_ROOT / "knowledge_base_v2" / "character_knowledge_v2.sqlite3"
DEFAULT_OUTPUT = DATA_ROOT / "knowledge_base_v2" / "quality" / "database_audit.json"


def scalar(db: sqlite3.Connection, sql: str, parameters: tuple[Any, ...] = ()) -> Any:
    return db.execute(sql, parameters).fetchone()[0]


def grouped(db: sqlite3.Connection, sql: str) -> dict[str, int]:
    return {str(key): int(count) for key, count in db.execute(sql).fetchall()}


def audit(database: Path) -> dict[str, Any]:
    db = sqlite3.connect(database)
    db.row_factory = sqlite3.Row
    try:
        tables = [row[0] for row in db.execute(
            "SELECT name FROM sqlite_master WHERE type IN ('table','view') AND name NOT LIKE 'sqlite_%' ORDER BY name"
        )]
        counts = {}
        schemas = {}
        for table in tables:
            if table.startswith("retrieval_fts_") or table.startswith("claims_fts_"):
                continue
            counts[table] = scalar(db, f'SELECT count(*) FROM "{table}"')
            schemas[table] = [
                {"name": row[1], "type": row[2], "not_null": bool(row[3]), "primary_key": bool(row[5])}
                for row in db.execute(f'PRAGMA table_info("{table}")')
            ]

        embedding = db.execute(
            "SELECT * FROM embedding_indexes WHERE status='active' ORDER BY created_at DESC LIMIT 1"
        ).fetchone()
        embedding_summary = None
        if embedding:
            embedding_summary = {
                "index_id": embedding["index_id"],
                "model_name": embedding["model_name"],
                "model_revision": embedding["model_revision"],
                "dimension": embedding["dimension"],
                "dtype": embedding["dtype"],
                "normalized": bool(embedding["normalized"]),
                "pooling": embedding["pooling"],
                "query_instruction": embedding["query_instruction"],
                "passage_instruction": embedding["passage_instruction"],
                "content_template": embedding["content_template"],
                "document_count": embedding["document_count"],
                "vector_blob_bytes": scalar(
                    db,
                    "SELECT coalesce(sum(length(vector)),0) FROM evidence_embeddings WHERE index_id=?",
                    (embedding["index_id"],),
                ),
            }

        sample = db.execute(
            """
            SELECT eu.evidence_id, eu.title, eu.fact, eu.source_id, eu.line_start, eu.line_end,
                   eu.topic, eu.question_count
            FROM evidence_units eu
            WHERE eu.fact LIKE '%教社会学%'
            ORDER BY eu.line_start LIMIT 1
            """
        ).fetchone()
        sample_record = dict(sample) if sample else None
        if sample_record:
            sample_record["retrieval_expressions"] = [
                dict(row) for row in db.execute(
                    "SELECT expression_type, text FROM retrieval_expressions WHERE evidence_id=? ORDER BY expression_type, text",
                    (sample_record["evidence_id"],),
                ).fetchall()
            ]
            sample_record["cognition_records"] = [
                dict(row) for row in db.execute(
                    """
                    SELECT character_id, acquisition_method, acquired_at_event, acquired_sequence,
                           belief_status, authorization_status, authorization_basis, confidence
                    FROM knowledge_edges WHERE evidence_id=? ORDER BY character_id
                    """,
                    (sample_record["evidence_id"],),
                ).fetchall()
            ]

        checks = {
            "sqlite_integrity": scalar(db, "PRAGMA integrity_check"),
            "orphan_retrieval_expressions": scalar(db, "SELECT count(*) FROM retrieval_expressions r LEFT JOIN evidence_units e ON e.evidence_id=r.evidence_id WHERE e.evidence_id IS NULL"),
            "orphan_cognition_records": scalar(db, "SELECT count(*) FROM knowledge_edges k LEFT JOIN claims c ON c.claim_id=k.claim_id WHERE c.claim_id IS NULL"),
            "orphan_embeddings": scalar(db, "SELECT count(*) FROM evidence_embeddings v LEFT JOIN evidence_units e ON e.evidence_id=v.evidence_id WHERE e.evidence_id IS NULL"),
            "question_count_out_of_range": scalar(db, "SELECT count(*) FROM evidence_units WHERE question_count NOT BETWEEN 3 AND 5"),
            "approved_without_verified_span": scalar(db, "SELECT count(*) FROM knowledge_edges WHERE authorization_status='approved' AND source_verification_status!='verified_span'"),
        }
        return {
            "database": str(database.resolve()),
            "file_bytes": database.stat().st_size,
            "storage_layers": {
                "source_and_scenes": ["documents", "scenes"],
                "facts_and_evidence": ["events", "claims", "evidence", "evidence_units"],
                "retrieval": ["retrieval_expressions", "retrieval_fts", "embedding_indexes", "evidence_embeddings"],
                "cognition": ["knowledge_edges", "snapshots"],
                "directed_relations": ["relations"],
                "time_bounded_state": ["character_states"],
            },
            "counts": counts,
            "schemas": schemas,
            "distributions": {
                "retrieval_expression_types": grouped(db, "SELECT expression_type,count(*) FROM retrieval_expressions GROUP BY expression_type ORDER BY expression_type"),
                "cognition_authorization": grouped(db, "SELECT authorization_status,count(*) FROM knowledge_edges GROUP BY authorization_status ORDER BY authorization_status"),
                "cognition_belief": grouped(db, "SELECT belief_status,count(*) FROM knowledge_edges GROUP BY belief_status ORDER BY belief_status"),
                "cognition_acquisition": grouped(db, "SELECT acquisition_method,count(*) FROM knowledge_edges GROUP BY acquisition_method ORDER BY acquisition_method"),
                "state_types": grouped(db, "SELECT state_type,count(*) FROM character_states GROUP BY state_type ORDER BY state_type"),
            },
            "active_embedding_index": embedding_summary,
            "sample_evidence_lifecycle": sample_record,
            "checks": checks,
        }
    finally:
        db.close()


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Audit the physical and logical contents of the knowledge database")
    parser.add_argument("--database", type=Path, default=DEFAULT_DATABASE)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    report = audit(args.database)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    rendered = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    args.output.write_text(rendered, encoding="utf-8")
    print(rendered, end="")


if __name__ == "__main__":
    main()

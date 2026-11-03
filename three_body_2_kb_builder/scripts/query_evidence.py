from __future__ import annotations

import argparse
import json
import re
import sqlite3
from functools import lru_cache
from pathlib import Path
from typing import Any


BUILDER_ROOT = Path(__file__).resolve().parents[1]
DATA_ROOT = BUILDER_ROOT.parent / "database"
DEFAULT_DATABASE = DATA_ROOT / "knowledge_base_v2" / "character_knowledge_v2.sqlite3"
RRF_K = 60


def normalize(text: str) -> str:
    return "".join(re.findall(r"[A-Za-z0-9_]+|[\u3400-\u9fff]+", text)).lower()


def match_query(text: str) -> str:
    return " ".join(re.findall(r"[A-Za-z0-9_]{2,}|[\u3400-\u9fff]{3,}", text))


def lexical_clues(text: str) -> list[str]:
    chinese = "".join(re.findall(r"[\u3400-\u9fff]", text))
    stop = {"为什么", "什么", "怎么", "你的", "你是", "的是", "有什", "别人", "叫你", "么特", "滴的", "的材", "料有", "学的", "是什", "么专"}
    clues: list[str] = []
    for width in (3, 2):
        for index in range(max(0, len(chinese) - width + 1)):
            value = chinese[index : index + width]
            if value not in stop and value not in clues:
                clues.append(value)
    clues.extend(token.lower() for token in re.findall(r"[A-Za-z0-9_]{2,}", text))
    return clues[:20]


def eligible_evidence(
    db: sqlite3.Connection,
    npc_id: str,
    cutoff_sequence: int,
    knowledge_policy: str,
) -> dict[str, sqlite3.Row]:
    authorization_sql = "ke.authorization_status = 'approved'" if knowledge_policy == "approved" else "1 = 1"
    belief_values = "'believed', 'known'" if knowledge_policy == "approved" else "'believed', 'known', 'unreviewed'"
    rows = db.execute(
        f"""
        SELECT eu.*, ke.acquisition_method, ke.confidence AS knowledge_confidence,
               ke.belief_status, ke.authorization_status, ke.authorization_basis
        FROM evidence_units eu
        JOIN knowledge_edges ke ON ke.claim_id = eu.claim_id
        WHERE ke.character_id = ?
          AND ke.acquired_sequence <= ?
          AND {authorization_sql}
          AND ke.belief_status IN ({belief_values})
        """,
        (npc_id, cutoff_sequence),
    ).fetchall()
    return {row["evidence_id"]: row for row in rows}


def lexical_ranking(
    db: sqlite3.Connection,
    query: str,
    eligible_ids: set[str],
    candidate_limit: int,
) -> list[dict[str, Any]]:
    fts_query = match_query(query)
    if not fts_query:
        return []
    candidates: list[dict[str, Any]] = []
    scan_limit = max(candidate_limit * 20, 400)
    try:
        candidates.extend(dict(row) for row in db.execute(
            """
            SELECT expression_id, evidence_id, expression_type, text,
                   bm25(retrieval_fts) AS bm25_score
            FROM retrieval_fts
            WHERE retrieval_fts MATCH ?
            ORDER BY bm25_score
            LIMIT ?
            """,
            (fts_query, scan_limit),
        ).fetchall())
    except sqlite3.OperationalError:
        pass

    clues = lexical_clues(query)
    if clues:
        where = " OR ".join("text LIKE ?" for _ in clues)
        parameters: list[Any] = [f"%{clue}%" for clue in clues] + [scan_limit]
        candidates.extend(dict(row) for row in db.execute(
            f"""
            SELECT expression_id, evidence_id, expression_type, text,
                   -0.35 AS bm25_score
            FROM retrieval_expressions
            WHERE {where}
            LIMIT ?
            """,
            parameters,
        ).fetchall())

    best: dict[str, dict[str, Any]] = {}
    normalized_query = normalize(query)
    type_weight = {"predicted_question": 0.45, "title": 0.35, "fact": 0.2, "alias": 0.1}
    for match in candidates:
        evidence_id = match["evidence_id"]
        if evidence_id not in eligible_ids:
            continue
        exact = 1.25 if normalized_query == normalize(match["text"]) else 0.0
        expression_hits = sum(1 for clue in clues if clue in match["text"])
        lexical_score = -float(match["bm25_score"]) + exact + type_weight.get(match["expression_type"], 0.0) + expression_hits * 0.22
        scored = {**match, "channel_score": lexical_score}
        previous = best.get(evidence_id)
        if previous is None or scored["channel_score"] > previous["channel_score"]:
            best[evidence_id] = scored
    return sorted(best.values(), key=lambda item: (-item["channel_score"], item["evidence_id"]))[:candidate_limit]


@lru_cache(maxsize=2)
def load_sentence_model(model_name: str, revision: str):
    from sentence_transformers import SentenceTransformer

    kwargs: dict[str, Any] = {"device": "cpu"}
    if revision and revision != "unresolved":
        kwargs["revision"] = revision
    return SentenceTransformer(model_name, **kwargs)


def semantic_ranking(
    db: sqlite3.Connection,
    query: str,
    eligible_ids: set[str],
    candidate_limit: int,
) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    try:
        import numpy as np
    except ImportError:
        return [], {"status": "unavailable", "reason": "numpy_not_installed"}
    try:
        index = db.execute(
            "SELECT * FROM embedding_indexes WHERE status='active' ORDER BY created_at DESC LIMIT 1"
        ).fetchone()
    except sqlite3.OperationalError:
        return [], {"status": "unavailable", "reason": "semantic_index_missing"}
    if index is None:
        return [], {"status": "unavailable", "reason": "semantic_index_missing"}
    try:
        model = load_sentence_model(index["model_name"], index["model_revision"])
        query_vector = model.encode(
            [index["query_instruction"] + query],
            convert_to_numpy=True,
            normalize_embeddings=True,
            show_progress_bar=False,
        )[0].astype(np.float32, copy=False)
    except Exception as exc:
        return [], {"status": "unavailable", "reason": f"query_encoder_error:{type(exc).__name__}"}
    if query_vector.shape != (index["dimension"],):
        return [], {"status": "invalid", "reason": "query_dimension_mismatch"}

    rows = db.execute(
        "SELECT evidence_id, vector FROM evidence_embeddings WHERE index_id = ?",
        (index["index_id"],),
    ).fetchall()
    scored = []
    for row in rows:
        if row["evidence_id"] not in eligible_ids:
            continue
        vector = np.frombuffer(row["vector"], dtype="<f4")
        if vector.shape != query_vector.shape:
            raise ValueError(f"stored vector dimension mismatch: {row['evidence_id']}")
        scored.append({"evidence_id": row["evidence_id"], "channel_score": float(query_vector @ vector)})
    scored.sort(key=lambda item: (-item["channel_score"], item["evidence_id"]))
    return scored[:candidate_limit], {
        "status": "ready",
        "index_id": index["index_id"],
        "model_name": index["model_name"],
        "model_revision": index["model_revision"],
        "dimension": index["dimension"],
    }


def retrieve(
    database: Path,
    npc_id: str,
    cutoff: str,
    query: str,
    limit: int = 5,
    candidate_limit: int = 20,
    retrieval_mode: str = "hybrid",
    knowledge_policy: str = "approved",
) -> list[dict[str, Any]]:
    db = sqlite3.connect(database)
    db.row_factory = sqlite3.Row
    try:
        cutoff_row = db.execute("SELECT sequence FROM cutoffs WHERE event_id = ?", (cutoff,)).fetchone()
        if cutoff_row is None:
            raise ValueError(f"unknown cutoff: {cutoff}")
        evidence = eligible_evidence(db, npc_id, cutoff_row["sequence"], knowledge_policy)
        eligible_ids = set(evidence)
        lexical = lexical_ranking(db, query, eligible_ids, candidate_limit) if retrieval_mode in {"lexical", "hybrid"} else []
        semantic, semantic_info = semantic_ranking(db, query, eligible_ids, candidate_limit) if retrieval_mode in {"semantic", "hybrid"} else ([], {"status": "not_requested"})
        if retrieval_mode == "semantic" and not semantic:
            raise RuntimeError(f"semantic retrieval unavailable: {semantic_info.get('reason', 'no_candidates')}")

        fused: dict[str, dict[str, Any]] = {}
        for channel, ranking in (("lexical", lexical), ("semantic", semantic)):
            for rank, item in enumerate(ranking, 1):
                record = fused.setdefault(item["evidence_id"], {"rrf_score": 0.0, "channels": {}})
                record["rrf_score"] += 1.0 / (RRF_K + rank)
                record["channels"][channel] = {"rank": rank, "score": round(float(item["channel_score"]), 6)}
                if channel == "lexical":
                    record["matched_expression"] = {"type": item["expression_type"], "text": item["text"]}

        ranked_ids = sorted(
            fused,
            key=lambda evidence_id: (-fused[evidence_id]["rrf_score"], evidence[evidence_id]["line_start"], evidence_id),
        )
        results: list[dict[str, Any]] = []
        for evidence_id in ranked_ids[:limit]:
            row = evidence[evidence_id]
            trace = fused[evidence_id]
            results.append({
                "evidence_id": evidence_id,
                "title": row["title"],
                "fact": row["fact"],
                "source": {"source_id": row["source_id"], "line_start": row["line_start"], "line_end": row["line_end"]},
                "quote": row["quote"],
                "event_id": row["event_id"],
                "topic": row["topic"],
                "cognition": {
                    "character_id": npc_id,
                    "acquisition_method": row["acquisition_method"],
                    "belief_status": row["belief_status"],
                    "authorization_status": row["authorization_status"],
                    "authorization_basis": row["authorization_basis"],
                    "confidence": min(float(row["confidence"]), float(row["knowledge_confidence"])),
                },
                "retrieval": {
                    "mode": retrieval_mode,
                    "rrf_k": RRF_K,
                    "rrf_score": round(trace["rrf_score"], 8),
                    "channels": trace["channels"],
                    "semantic_index": semantic_info,
                    "matched_expression": trace.get("matched_expression"),
                },
                "answer_basis": ["fact", "quote"],
            })
        return results
    finally:
        db.close()


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Hybrid lexical + BGE evidence retrieval with RRF")
    parser.add_argument("--database", type=Path, default=DEFAULT_DATABASE)
    parser.add_argument("--npc", required=True)
    parser.add_argument("--cutoff", required=True)
    parser.add_argument("--query", required=True)
    parser.add_argument("--limit", type=int, default=5)
    parser.add_argument("--candidate-limit", type=int, default=20)
    parser.add_argument("--mode", choices=("lexical", "semantic", "hybrid"), default="hybrid")
    parser.add_argument("--knowledge-policy", choices=("approved", "include_candidates"), default="approved")
    parser.add_argument("--debug", action="store_true")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    results = retrieve(
        args.database,
        args.npc,
        args.cutoff,
        args.query,
        args.limit,
        args.candidate_limit,
        args.mode,
        args.knowledge_policy,
    )
    if not args.debug:
        for item in results:
            item.pop("retrieval", None)
    print(json.dumps({
        "npc": args.npc,
        "cutoff": args.cutoff,
        "query": args.query,
        "knowledge_policy": args.knowledge_policy,
        "deduplicated_by": "evidence_id",
        "results": results,
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()

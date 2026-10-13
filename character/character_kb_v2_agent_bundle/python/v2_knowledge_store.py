from __future__ import annotations

import json
import re
import sqlite3
from dataclasses import dataclass
from pathlib import Path
from typing import Any


@dataclass(frozen=True)
class V2RetrievedClaim:
    claim: dict[str, Any]
    acquisition_method: str
    knowledge_confidence: float
    score: float
    evidence: tuple[dict[str, Any], ...]


@dataclass(frozen=True)
class V2Decision:
    claim_id: str
    allowed: bool
    reason: str
    score: float


def _ngrams(text: str, size: int = 3) -> set[str]:
    normalized = re.sub(r"\s+", "", text.lower())
    normalized = re.sub(r"[，。！？、：；“”‘’（）《》【】,.!?;:'\"()\[\]]", "", normalized)
    chinese = "".join(re.findall(r"[\u3400-\u9fff]", normalized))
    grams = {chinese[index : index + size] for index in range(max(0, len(chinese) - size + 1))}
    grams.update(re.findall(r"[a-z0-9_]{2,}", normalized))
    return {item for item in grams if item}


class SQLiteKnowledgeStoreV2:
    """Read-only V2 SQLite/FTS5 retriever with hard epistemic/time filtering."""

    def __init__(self, database: Path | str) -> None:
        self.database = Path(database).resolve()
        if not self.database.is_file():
            raise FileNotFoundError(self.database)
        self.connection = sqlite3.connect(f"file:{self.database.as_posix()}?mode=ro", uri=True)
        self.connection.row_factory = sqlite3.Row
        version = self.connection.execute(
            "SELECT value FROM metadata WHERE key='schema_version'"
        ).fetchone()
        if version is None or not str(version[0]).startswith("2."):
            raise ValueError("database is not a V2 character knowledge base")

    def close(self) -> None:
        self.connection.close()

    def __enter__(self) -> "SQLiteKnowledgeStoreV2":
        return self

    def __exit__(self, *_: object) -> None:
        self.close()

    def retrieve(
        self,
        character_id: str,
        query: str,
        story_cutoff: str,
        limit: int = 8,
        include_profile: bool = False,
    ) -> tuple[list[V2RetrievedClaim], list[V2Decision]]:
        cutoff = self.connection.execute(
            "SELECT sequence FROM cutoffs WHERE event_id=?", (story_cutoff,)
        ).fetchone()
        if cutoff is None:
            raise ValueError(f"unknown V2 story cutoff: {story_cutoff}")
        cutoff_sequence = int(cutoff[0])
        retrieval_query = self._without_entity_names(query)
        query_grams = _ngrams(retrieval_query or query)
        candidate_rows = self._fts_candidates(retrieval_query or query, query_grams)
        decisions: list[V2Decision] = []
        accepted: list[V2RetrievedClaim] = []
        for row in candidate_rows:
            claim = self._claim_from_row(row)
            statement_grams = _ngrams(claim["statement"] + "".join(claim["keywords"]))
            overlap = query_grams & statement_grams
            score = round(
                (len(overlap) / max(1, len(query_grams))) * 0.65
                + float(claim["importance"]) * 0.2
                + float(claim["confidence"]) * 0.15,
                5,
            )
            edge = self.connection.execute(
                "SELECT * FROM knowledge_edges WHERE character_id=? AND claim_id=?",
                (character_id, claim["claim_id"]),
            ).fetchone()
            allowed, reason = True, "authorized"
            if claim["claim_scope"] == "profile" and not include_profile:
                allowed, reason = False, "role_profile_not_in_story_retrieval"
            elif claim["source_tier"] == "external" and claim["canon_status"] != "accepted":
                allowed, reason = False, "external_not_approved"
            elif claim["source_tier"] not in {"canon", "curated", "external"}:
                allowed, reason = False, "unsupported_source"
            elif claim["canon_status"] not in {"accepted", "auto_accepted"}:
                allowed, reason = False, "claim_requires_review"
            elif edge is None:
                allowed, reason = False, "not_known_by_character"
            elif int(edge["acquired_sequence"]) > cutoff_sequence:
                allowed, reason = False, "future_knowledge"
            elif int(claim["valid_from_sequence"]) > cutoff_sequence:
                allowed, reason = False, "future_claim"
            elif claim["valid_to_sequence"] is not None and int(claim["valid_to_sequence"]) < cutoff_sequence:
                allowed, reason = False, "expired_claim"
            decisions.append(V2Decision(claim["claim_id"], allowed, reason, score))
            if not allowed or edge is None:
                continue
            evidence_rows = self.connection.execute(
                "SELECT source_id,line_start,line_end,quote,verification_status FROM evidence WHERE claim_id=?",
                (claim["claim_id"],),
            ).fetchall()
            accepted.append(
                V2RetrievedClaim(
                    claim=claim,
                    acquisition_method=str(edge["acquisition_method"]),
                    knowledge_confidence=float(edge["confidence"]),
                    score=score,
                    evidence=tuple(dict(item) for item in evidence_rows),
                )
            )
        accepted.sort(
            key=lambda item: (item.score, item.claim["importance"], item.claim["confidence"]),
            reverse=True,
        )
        return accepted[:limit], decisions

    def _fts_candidates(self, query: str, query_grams: set[str]) -> list[sqlite3.Row]:
        combined: dict[str, sqlite3.Row] = {}
        grams = sorted(query_grams, key=len, reverse=True)[:16]
        if grams:
            expression = " OR ".join('"' + item.replace('"', '""') + '"' for item in grams)
            try:
                rows = self.connection.execute(
                    """
                    SELECT c.* FROM claims_fts
                    JOIN claims c ON c.claim_id=claims_fts.claim_id
                    WHERE claims_fts MATCH ?
                    ORDER BY bm25(claims_fts, 0.0, 1.0, 0.4)
                    LIMIT 120
                    """,
                    (expression,),
                ).fetchall()
                combined.update({str(row["claim_id"]): row for row in rows})
            except sqlite3.OperationalError:
                pass
        compact = re.sub(r"\s+", "", query)
        probes = list(dict.fromkeys(
            compact[index : index + 2] for index in range(max(0, len(compact) - 1))
        ))[:12]
        if probes:
            conditions = " OR ".join("statement LIKE ?" for _ in probes)
            rows = self.connection.execute(
                f"SELECT * FROM claims WHERE {conditions} LIMIT 120",
                tuple(f"%{probe}%" for probe in probes),
            ).fetchall()
            combined.update({str(row["claim_id"]): row for row in rows})
        return list(combined.values())

    def _without_entity_names(self, query: str) -> str:
        rows = self.connection.execute(
            "SELECT canonical_name AS name FROM entities UNION SELECT alias AS name FROM aliases"
        ).fetchall()
        cleaned = query
        for row in sorted(rows, key=lambda item: len(str(item["name"])), reverse=True):
            name = str(row["name"])
            if name:
                cleaned = cleaned.replace(name, "")
        return cleaned

    @staticmethod
    def _claim_from_row(row: sqlite3.Row) -> dict[str, Any]:
        value = dict(row)
        value["subject_ids"] = json.loads(value.pop("subject_ids_json"))
        value["keywords"] = json.loads(value.pop("keywords_json"))
        return value

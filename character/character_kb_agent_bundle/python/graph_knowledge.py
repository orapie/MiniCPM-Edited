from __future__ import annotations

import json
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any


@dataclass(frozen=True)
class RetrievedGraphClaim:
    claim: dict[str, Any]
    score: float
    matched_terms: tuple[str, ...]


@dataclass(frozen=True)
class GraphClaimDecision:
    claim_id: str
    allowed: bool
    reason: str
    score: float


def _terms(text: str) -> set[str]:
    normalized = re.sub(r"\s+", "", text.lower())
    normalized = re.sub(r"[，。！？、：；“”‘’（）《》【】,.!?;:'\"()\[\]]", "", normalized)
    terms = set(re.findall(r"[a-z0-9_]{2,}", normalized))
    chinese = "".join(re.findall(r"[\u3400-\u9fff]", normalized))
    terms.update(chinese[index : index + 2] for index in range(max(0, len(chinese) - 1)))
    terms.update(chinese[index : index + 3] for index in range(max(0, len(chinese) - 2)))
    return terms


class GraphKnowledgeStore:
    """Retrieve accepted graph claims while enforcing character and timeline boundaries."""

    def __init__(self, knowledge_base_dir: Path | str) -> None:
        self.root = Path(knowledge_base_dir).resolve()
        manifest_path = self.root / "manifest.json"
        claims_path = self.root / "claims.jsonl"
        if not manifest_path.is_file() or not claims_path.is_file():
            raise FileNotFoundError(f"invalid knowledge base directory: {self.root}")
        self.manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        self.cutoffs = {key: int(value) for key, value in self.manifest.get("cutoffs", {}).items()}
        self.claims: list[dict[str, Any]] = []
        with claims_path.open("r", encoding="utf-8") as stream:
            for line in stream:
                if line.strip():
                    self.claims.append(json.loads(line))

    def retrieve(
        self, npc_id: str, query: str, cutoff: str, limit: int = 8
    ) -> tuple[list[RetrievedGraphClaim], list[GraphClaimDecision]]:
        if cutoff not in self.cutoffs:
            raise ValueError(f"knowledge base has no sequence mapping for cutoff {cutoff}")
        cutoff_sequence = self.cutoffs[cutoff]
        query_terms = _terms(query)
        candidates: list[RetrievedGraphClaim] = []
        decisions: list[GraphClaimDecision] = []
        for claim in self.claims:
            statement_terms = _terms(claim.get("statement", "") + "".join(claim.get("keywords", [])))
            overlap = query_terms & statement_terms
            name_hit = any(term and term in query for term in claim.get("keywords", []))
            if not overlap and not name_hit:
                continue
            lexical = len(overlap) / max(1, len(query_terms))
            score = round(
                lexical * 0.65
                + min(0.2, len(overlap) * 0.03)
                + float(claim.get("importance", 0.5)) * 0.15,
                5,
            )
            reason = "authorized"
            allowed = True
            source_tier = claim.get("source_tier")
            canon_status = claim.get("canon_status")
            if source_tier == "external" and canon_status != "accepted":
                allowed, reason = False, "external_source_not_approved"
            elif source_tier not in {"canon", "curated", "external"}:
                allowed, reason = False, "unsupported_source_tier"
            elif canon_status not in {"accepted", "auto_accepted"}:
                allowed, reason = False, "claim_requires_review"
            elif npc_id not in claim.get("known_by", {}):
                allowed, reason = False, "not_known_by_character"
            elif int(claim.get("sequence_key") or 0) > cutoff_sequence:
                allowed, reason = False, "future_claim"
            decision = GraphClaimDecision(claim["claim_id"], allowed, reason, score)
            decisions.append(decision)
            if allowed:
                candidates.append(
                    RetrievedGraphClaim(claim, score, tuple(sorted(overlap)))
                )
        candidates.sort(
            key=lambda item: (
                item.score,
                float(item.claim.get("importance", 0.5)),
                float(item.claim.get("confidence", 0.5)),
            ),
            reverse=True,
        )
        return candidates[:limit], decisions

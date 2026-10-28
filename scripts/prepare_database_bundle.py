#!/usr/bin/env python3
"""Validate and prepare a portable bundle from one explicit database directory.

No legacy-directory discovery. Scene relevance is exported as candidates only;
only separately reviewed, source-checked atomic facts receive knowledge edges.
"""

import argparse
from collections import Counter
from contextlib import closing
import hashlib
import json
import math
from pathlib import Path, PurePosixPath
import re
import shutil
import sqlite3
import sys
import tempfile


SCHEMA = "1.0"
POLICY = "source_span_and_reviewed_dialogue_v1"
PATHS = {
    "characters": "characters",
    "story_events": "story/story_events.jsonl",
    "v2_manifest": "knowledge_base_v2/manifest.json",
    "v2_database": "knowledge_base_v2/character_knowledge_v2.sqlite3",
    "reviewed_facts": "reviewed/atomic_facts.json",
    "candidate_v2": "runtime/android/scene_candidates.json",
    "runtime_v2": "runtime/android/knowledge_fallback.json",
    "vector_index": "runtime/android/vector/index.json",
}
EMBEDDING = {"type": "hash_ngram", "dimensions": 384, "ngram_min": 2, "ngram_max": 4,
             "hash_algorithm": "blake2b-64", "bucket_byte_order": "big", "sign_byte_index": 4,
             "normalization": "l2", "empty_text": "zero_vector"}
CLAIM_COLUMNS = (
    "claim_id, claim_scope, subject_ids_json, predicate, object_text, statement, "
    "event_id, modality, polarity, valid_from_sequence, valid_to_sequence, "
    "importance, confidence, keywords_json, source_tier, canon_status, verification_status"
)
EDGE_COLUMNS = (
    "knowledge_edge_id, character_id, claim_id, acquisition_method, acquired_at_event, "
    "acquired_story_order, acquired_sequence, confidence, belief_status"
)


def require(condition, message):
    if not condition:
        raise ValueError(message)


def resolve(root, relative):
    require(isinstance(relative, str) and relative, "Empty bundle path")
    parts = PurePosixPath(relative)
    require(not parts.is_absolute() and relative != "." and ".." not in parts.parts and "\\" not in relative
            and ":" not in relative and str(parts) == relative, f"Unsafe bundle path: {relative}")
    target = (root / relative).resolve()
    require(target.is_relative_to(root.resolve()), f"Bundle path escapes root: {relative}")
    return target


def load_json(path):
    require(path.is_file(), f"Missing database file: {path}")
    return json.loads(path.read_text(encoding="utf-8"))


def load_jsonl(path):
    require(path.is_file(), f"Missing database file: {path}")
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2) + "\n", encoding="utf-8")


def fingerprint(manifest):
    # Language-neutral sorted lines, also implemented by DatabaseBundleManifest.kt.
    values = {key: manifest[key] for key in (
        "bundle_schema_version", "dataset_id", "dataset_version", "default_character_id",
        "default_story_cutoff", "authorization_policy",
    )}
    for name in ("capabilities", "paths", "embedding"):
        for key, value in manifest.get(name, {}).items():
            values[f"{name}.{key}"] = str(value).lower() if isinstance(value, bool) else value
    for item in manifest["files"]:
        values[f"files.{item['path']}"] = item["sha256"]
    require(all("\n" not in str(value) and "\r" not in str(value) for item in values.items() for value in item),
            "Manifest values must not contain line breaks")
    payload = "database-bundle-v1\n" + "".join(f"{key}={values[key]}\n" for key in sorted(values))
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def hash_embedding(text):
    """Match Kotlin's UTF-16 n-grams (including split supplementary characters)."""
    text = "".join(char for char in text.lower() if not char.isspace())
    encoded = text.encode("utf-16-le")
    units = [encoded[i:i + 2] for i in range(0, len(encoded), 2)]
    vector = [0.0] * EMBEDDING["dimensions"]
    tokens = [b"".join(units[start:start + size])
              for size in range(2, 5) for start in range(len(units) - size + 1)]
    if not tokens and text:
        tokens = [encoded]
    for token in tokens:
        # JVM replaces an unpaired surrogate with '?' when encoding UTF-8.
        utf8 = token.decode("utf-16-le", errors="surrogatepass").encode("utf-8", errors="replace")
        digest = hashlib.blake2b(utf8, digest_size=8).digest()
        vector[int.from_bytes(digest[:4], "big") % len(vector)] += 1.0 if digest[4] % 2 == 0 else -1.0
    length = math.sqrt(sum(value * value for value in vector))
    return [value / length for value in vector] if length else vector


def build_vector_index(common, claims, evidence, atomic, reviews):
    proof_by_claim = {item["claim_id"]: item for item in evidence}
    reviews_by_id = {item["claim_id"]: item for item in reviews["facts"]}
    items = []
    for kind, records in (("scene_candidate", claims), ("reviewed_fact", atomic)):
        for claim in records:
            proof = proof_by_claim[claim["claim_id"]] if kind == "scene_candidate" else reviews_by_id[claim["claim_id"]]
            metadata = {"dataset_id": common["dataset_id"], "dataset_version": common["dataset_version"],
                        "claim_id": claim["claim_id"], "record_kind": kind, "authorization_required": "true",
                        "source_database_sha256": common["source_database_sha256"]}
            if kind == "reviewed_fact":
                metadata.update(character_id=proof["character_id"], parent_claim_id=claim["parent_claim_id"])
            chunk = {"id": claim["claim_id"], "text": claim["statement"],
                     "source_path": "database/" + PATHS["v2_database"],
                     "start": proof["line_start"], "end": proof["line_end"], "metadata": metadata}
            items.append({"chunk": chunk, "embedding": hash_embedding(chunk["text"])})
    return {"schema_version": "1.1", "dataset_id": common["dataset_id"],
            "dataset_version": common["dataset_version"], "source_database_sha256": common["source_database_sha256"],
            "embedding": EMBEDDING, "items": sorted(items, key=lambda item: item["chunk"]["id"])}


def read_source(root):
    root = root.resolve()
    source_manifest = load_json(resolve(root, PATHS["v2_manifest"]))
    require(source_manifest.get("schema_version") == "2.0.0" and
            source_manifest.get("knowledge_base_version") == "v2", "Unsupported source V2 schema")
    database = resolve(root, PATHS["v2_database"])
    require(database.is_file(), f"Missing database file: {database}")
    with closing(sqlite3.connect(database.as_uri() + "?mode=ro", uri=True)) as connection:
        connection.row_factory = sqlite3.Row
        require(connection.execute("PRAGMA integrity_check").fetchone()[0] == "ok", "SQLite integrity check failed")
        claims = [dict(row) for row in connection.execute(f"SELECT {CLAIM_COLUMNS} FROM claims ORDER BY claim_id")]
        for claim in claims:
            for field in ("subject_ids_json", "keywords_json"):
                claim[field] = json.loads(claim[field])
        edges = [dict(row) for row in connection.execute(
            f"SELECT {EDGE_COLUMNS} FROM knowledge_edges ORDER BY character_id, acquired_sequence, knowledge_edge_id")]
        evidence = [dict(row) for row in connection.execute("SELECT * FROM evidence ORDER BY evidence_id")]
        source_documents = [dict(row) for row in connection.execute("SELECT * FROM documents ORDER BY document_id")]
        cutoffs = {row["event_id"]: {"sequence": row["sequence"], "story_order": row["story_order"]}
                   for row in connection.execute("SELECT * FROM cutoffs")}
        relation_count = connection.execute("SELECT count(*) FROM relations").fetchone()[0]
        # Source JSONL may be edited independently of SQLite. Detect stale mirrors
        # rather than silently exporting a mixture of different database versions.
        for table in ("aliases", "claims", "entities", "entity_mentions", "events", "knowledge_edges", "relations"):
            rows = [dict(row) for row in connection.execute(f"SELECT * FROM {table}")]
            key = next(iter(rows[0])) if rows else None
            source_rows = load_jsonl(resolve(root, f"knowledge_base_v2/graph/{table}.jsonl"))
            require(len(rows) == len(source_rows), f"Graph / SQLite count mismatch: {table}")
            normalized = {}
            for row in rows:
                value = {(name[:-5] if name.endswith("_json") else name):
                         (json.loads(field) if name.endswith("_json") else field) for name, field in row.items()}
                normalized[value[key]] = value
            require(len({row[key] for row in source_rows}) == len(source_rows), f"Duplicate graph IDs: {table}")
            for row in source_rows:
                require(row[key] in normalized and all(normalized[row[key]].get(name) == value for name, value in row.items()),
                        f"Graph / SQLite content mismatch: {table}")
    require(source_manifest["cutoffs"] == cutoffs, "SQLite / manifest cutoff mismatch")
    counts = source_manifest["counts"]
    require(counts["claims"] == len(claims) and counts["knowledge_edges"] == len(edges)
            and counts["evidence"] == len(evidence) and counts["relations"] == relation_count,
            "Source manifest counts do not match SQLite")
    card_dir = resolve(root, PATHS["characters"])
    require(card_dir.is_dir(), f"Missing characters directory: {card_dir}")
    cards = [load_json(resolve(root, path.relative_to(root).as_posix())) for path in sorted(card_dir.glob("*.json"))]
    card_ids = [card["npc_id"] for card in cards]
    require(cards and len(set(card_ids)) == len(cards), "Missing or duplicate character IDs")
    require(all(card["knowledge_scope"]["story_cutoff"] in cutoffs for card in cards), "Unknown card cutoff")
    events = load_jsonl(resolve(root, PATHS["story_events"]))
    event_ids = {event["event_id"] for event in events}
    claim_ids = {claim["claim_id"] for claim in claims}
    require(len(claim_ids) == len(claims) and len(event_ids) == len(events), "Duplicate claim/event IDs")
    require(event_ids == set(cutoffs), "Event / cutoff mismatch")
    require(all(claim["event_id"] in event_ids for claim in claims), "Claim has unknown event")
    require(all(edge["character_id"] in card_ids and edge["claim_id"] in claim_ids and
                edge["acquired_at_event"] in event_ids for edge in edges), "Invalid knowledge edge reference")
    for card in cards:
        refs = card["knowledge_scope"].get("world_fact_refs", [])
        require(set(refs) <= event_ids, f"Unknown event in character {card['npc_id']}")
        require(all(memory["event_id"] in event_ids for memory in card.get("memory_index", [])),
                f"Unknown memory event in character {card['npc_id']}")

    # Use the corpus embedded in this very bundle, never the Windows origin path
    # or an old external text directory. A verified source span is not a knowledge grant.
    corpus = load_jsonl(resolve(root, "knowledge_base_v2/corpus/documents.jsonl"))
    require(len({item["source_id"] for item in corpus}) == len(corpus), "Duplicate corpus source IDs")
    metadata = [{key: value for key, value in document.items() if key != "text"} for document in corpus]
    require(sorted(metadata, key=lambda item: item["document_id"]) == source_documents, "Corpus / SQLite metadata mismatch")
    for document in corpus:
        require(hashlib.sha256(document["text"].encode("utf-8")).hexdigest() == document["sha256"] and
                len(document["text"]) == document["char_count"] and
                len(document["text"].split("\n")) == document["line_count"], "Corpus text/hash mismatch")
    documents = {document["source_id"]: document["text"].splitlines() for document in corpus}
    graph_evidence = load_jsonl(resolve(root, "knowledge_base_v2/graph/evidence.jsonl"))
    require({row["evidence_id"]: row for row in graph_evidence} ==
            {row["evidence_id"]: row for row in evidence}, "Evidence JSONL / SQLite mismatch")
    evidence_ids = {row["evidence_id"] for row in evidence}
    require(len(evidence_ids) == len(evidence), "Duplicate evidence IDs")
    claims_by_id = {claim["claim_id"]: claim for claim in claims}
    for item in evidence:
        require(item["claim_id"] in claims_by_id, "Evidence has unknown claim")
        lines = documents.get(item["source_id"], [])
        start, end = item["line_start"], item["line_end"]
        require(1 <= start <= end <= len(lines), f"Evidence range invalid: {item['evidence_id']}")
        quoted = "\n".join(lines[start - 1:end]).strip()
        require(quoted == item["quote"] and hashlib.sha256(quoted.encode("utf-8")).hexdigest() == item["sha256"],
                f"Evidence source/hash mismatch: {item['evidence_id']}")
        claim = claims_by_id[item["claim_id"]]
        require(claim["source_tier"] == "canon" and claim["canon_status"] == "accepted" and
                claim["verification_status"] == "source_span_verified" and claim["statement"] == quoted,
                f"Unsupported or unverified source claim: {claim['claim_id']}")
    require({item["claim_id"] for item in evidence} == claim_ids, "Claim without source evidence")
    return source_manifest, claims, edges, evidence, documents, cards, events, relation_count


def authorize_reviews(root, dataset_id, source, reviews):
    manifest, claims, _, evidence, documents, cards, _, _ = source
    require(reviews.get("schema_version") == SCHEMA and reviews.get("dataset_id") == dataset_id,
            "Reviewed facts schema/dataset mismatch")
    claims_by_id = {claim["claim_id"]: claim for claim in claims}
    evidence_by_id = {item["evidence_id"]: item for item in evidence}
    character_ids = {card["npc_id"] for card in cards}
    accepted, edges, used = [], [], set()
    for fact in reviews["facts"]:
        claim_id = fact["claim_id"]
        require(re.fullmatch(r"[a-z0-9_-]+", claim_id) and claim_id not in used and claim_id not in claims_by_id,
                f"Invalid/duplicate reviewed claim ID: {claim_id}")
        used.add(claim_id)
        require(fact["character_id"] in character_ids, f"Unknown reviewed character: {claim_id}")
        require(fact["review_method"] == "dialogue_context_inspection" and
                fact.get("reviewed_by") and len(fact.get("knowledge_basis", "")) >= 10,
                f"Missing explicit epistemic review: {claim_id}")
        require(fact["acquisition_method"] in {"direct", "witnessed", "reported", "inferred"},
                f"Scene relevance is not authorization: {claim_id}")
        require(0 < len(fact["statement"]) <= 120, f"Atomic statement exceeds 120 chars: {claim_id}")
        parent = claims_by_id[fact["parent_claim_id"]]
        proof = evidence_by_id[fact["evidence_id"]]
        start, end = fact["line_start"], fact["line_end"]
        require(proof["claim_id"] == parent["claim_id"] and
                proof["line_start"] <= start <= end <= proof["line_end"], f"Review evidence range invalid: {claim_id}")
        lines = documents[proof["source_id"]]
        quote = "\n".join(lines[start - 1:end]).strip()
        require(fact["quote"] == quote and quote in proof["quote"], f"Review quote mismatch: {claim_id}")
        require(parent["valid_from_sequence"] <= end <= manifest["cutoffs"][parent["event_id"]]["sequence"],
                f"Review acquisition outside parent event: {claim_id}")
        accepted.append({
            "claim_id": claim_id, "claim_scope": "story", "subject_ids_json": [fact["character_id"]],
            "predicate": "reviewed_atomic_fact", "object_text": fact["statement"], "statement": fact["statement"],
            "event_id": parent["event_id"], "modality": "reported" if fact["acquisition_method"] == "reported" else "asserted",
            "polarity": "positive", "valid_from_sequence": end, "valid_to_sequence": None,
            "importance": 0.8, "confidence": 1.0, "keywords_json": fact["keywords"],
            "source_tier": parent["source_tier"], "canon_status": parent["canon_status"],
            "verification_status": parent["verification_status"], "evidence_ref": f"{proof['source_id']}:L{start}-L{end}",
            "parent_claim_id": parent["claim_id"], "evidence_id": proof["evidence_id"],
            "authorization_review": {key: fact[key] for key in ("review_method", "reviewed_by", "knowledge_basis")},
        })
        edges.append({
            "knowledge_edge_id": f"reviewed-edge-{claim_id}", "character_id": fact["character_id"], "claim_id": claim_id,
            "acquisition_method": fact["acquisition_method"], "acquired_at_event": parent["event_id"],
            "acquired_story_order": manifest["cutoffs"][parent["event_id"]]["story_order"],
            "acquired_sequence": end, "confidence": 1.0, "belief_status": "believed",
        })
    require(accepted, "No reviewed authorized facts; do not activate an empty character bundle")
    return accepted, edges


def prepare(root, dataset_id, version, default_character):
    root = root.resolve()
    require(root.is_dir(), f"Database source does not exist: {root}")
    require(re.fullmatch(r"[a-z0-9_-]+", dataset_id), "Invalid dataset_id")
    require(version and "\n" not in version and "\r" not in version, "Invalid dataset_version")
    source = read_source(root)
    manifest, claims, edges, evidence, _, cards, events, relations = source
    reviews = load_json(resolve(root, PATHS["reviewed_facts"]))
    atomic, reviewed_edges = authorize_reviews(root, dataset_id, source, reviews)
    card = next((card for card in cards if card["npc_id"] == default_character), None)
    require(card is not None, "Default character not in current database")
    database_hash = sha256(resolve(root, PATHS["v2_database"]))
    common = {"schema_version": "2.0.0", "dataset_id": dataset_id, "dataset_version": version,
              "source_database_sha256": database_hash, "cutoffs": manifest["cutoffs"]}
    portable_evidence = [{key: value for key, value in proof.items() if key != "quote"} for proof in evidence]
    candidates = dict(common, usage="candidate_only_not_character_knowledge", claims=claims,
                      knowledge_edges=edges, evidence=portable_evidence)
    runtime = dict(common, authorization_policy=POLICY, claims=atomic, knowledge_edges=reviewed_edges)
    vector_index = build_vector_index(common, claims, evidence, atomic, reviews)
    lengths = [len(claim["statement"]) for claim in claims]
    report = {
        "schema_version": SCHEMA, "dataset_id": dataset_id, "source_database_sha256": database_hash,
        "source_valid": True, "candidate_retrieval_ready": True, "authorized_sample_ready": True,
        "android_runtime_connected": False,
        "counts": {"characters": len(cards), "events": len(events), "source_claims": len(claims),
                   "source_edges": len(edges), "source_evidence": len(evidence), "source_relations": relations,
                   "authorized_claims": len(atomic), "authorized_edges": len(reviewed_edges)},
        "source_statuses": {"tiers": dict(Counter(c["source_tier"] for c in claims)),
                            "verification": dict(Counter(c["verification_status"] for c in claims)),
                            "acquisition": dict(Counter(e["acquisition_method"] for e in edges)),
                            "belief": dict(Counter(e["belief_status"] for e in edges))},
        "statement_chars": {"min": min(lengths), "max": max(lengths), "over_180": sum(x > 180 for x in lengths)},
        "legacy_story_direct_edges_not_authorized": sum(
            k.get("source") == "direct" for event in events for k in event.get("knowledge_by_character", {}).values()),
        "vector_items": len(vector_index["items"]),
        "remaining_work": ["apk_installation", "runtime_consumer_migration",
                           "full_character_epistemic_review", "time_bounded_profile_and_state_review"],
        "policy": "Source span verification proves text, not character knowledge. Only explicit reviewed atomic facts are authorized.",
    }
    # Check all output paths before writing any of them; existing symlinks may not escape root.
    outputs = {PATHS["candidate_v2"]: candidates, PATHS["runtime_v2"]: runtime, PATHS["vector_index"]: vector_index,
               "runtime/android/compatibility_report.json": report}
    for relative in [*outputs, "manifest.json"]:
        resolve(root, relative)
    for relative, value in outputs.items():
        write_json(resolve(root, relative), value)
    files = [PATHS[key] for key in ("story_events", "v2_manifest", "v2_database", "reviewed_facts", "candidate_v2", "runtime_v2", "vector_index")]
    files += [path.relative_to(root).as_posix() for path in sorted(resolve(root, PATHS["characters"]).glob("*.json"))]
    bundle = {
        "bundle_schema_version": SCHEMA, "dataset_id": dataset_id, "dataset_version": version,
        "default_character_id": default_character, "default_story_cutoff": card["knowledge_scope"]["story_cutoff"],
        "authorization_policy": POLICY, "capabilities": {"vector": True, "v2": True, "v1": False, "timed_state": False},
        "embedding": EMBEDDING,
        "paths": PATHS, "files": [{"path": path, "sha256": sha256(resolve(root, path))} for path in sorted(files)],
    }
    bundle["dataset_fingerprint"] = fingerprint(bundle)
    write_json(resolve(root, "manifest.json"), bundle)
    check(root)
    return bundle, report


def check(root):
    root = root.resolve()
    bundle = load_json(resolve(root, "manifest.json"))
    require(bundle["bundle_schema_version"] == SCHEMA and bundle["authorization_policy"] == POLICY, "Unsupported bundle contract")
    require(bundle["capabilities"] == {"vector": True, "v2": True, "v1": False, "timed_state": False},
            "Unsupported bundle capabilities")
    require(bundle["embedding"] == EMBEDDING, "Embedding configuration mismatch")
    files = bundle["files"]
    require(len({item["path"] for item in files}) == len(files), "Duplicate file manifest entries")
    for item in files:
        path = resolve(root, item["path"])
        require(path.is_file() and sha256(path) == item["sha256"], f"Bundle file/hash mismatch: {item['path']}")
    for relative in bundle["paths"].values():
        require(resolve(root, relative).exists(), f"Missing declared bundle path: {relative}")
        if resolve(root, relative).is_file():
            require(relative in {item["path"] for item in files}, f"Unhashed declared file: {relative}")
    require(bundle["dataset_fingerprint"] == fingerprint(bundle), "Bundle fingerprint mismatch")
    require(bundle["paths"] == PATHS, "Unsupported bundle path contract")
    source = read_source(root)
    card = next((card for card in source[5] if card["npc_id"] == bundle["default_character_id"]), None)
    require(card and bundle["default_story_cutoff"] == card["knowledge_scope"]["story_cutoff"], "Invalid default character/cutoff")
    for path in resolve(root, PATHS["characters"]).glob("*.json"):
        require(path.relative_to(root).as_posix() in {item["path"] for item in files}, "Unhashed character file")
    reviews = load_json(resolve(root, PATHS["reviewed_facts"]))
    atomic, edges = authorize_reviews(root, bundle["dataset_id"], source, reviews)
    runtime = load_json(resolve(root, PATHS["runtime_v2"]))
    require(runtime["authorization_policy"] == POLICY, "Export authorization policy mismatch")
    require(runtime["claims"] == atomic and runtime["knowledge_edges"] == edges, "Authorized export mismatch")
    require(runtime["source_database_sha256"] == sha256(resolve(root, PATHS["v2_database"])), "Export source hash mismatch")
    for output in (runtime, load_json(resolve(root, PATHS["candidate_v2"]))):
        require(output["schema_version"] == "2.0.0" and output["dataset_id"] == bundle["dataset_id"]
                and output["dataset_version"] == bundle["dataset_version"] and output["cutoffs"] == source[0]["cutoffs"]
                and output["source_database_sha256"] == runtime["source_database_sha256"], "Export identity/cutoff mismatch")
    candidates = load_json(resolve(root, PATHS["candidate_v2"]))
    require(candidates["claims"] == source[1] and candidates["knowledge_edges"] == source[2] and
            candidates["evidence"] == [{k: v for k, v in proof.items() if k != "quote"} for proof in source[3]] and
            candidates["usage"] == "candidate_only_not_character_knowledge", "Source candidate export mismatch")
    expected_vector = build_vector_index(runtime, source[1], source[3], atomic, reviews)
    require(load_json(resolve(root, PATHS["vector_index"])) == expected_vector,
            "Vector index configuration/content/embedding mismatch; regenerate database exports")
    return bundle


def stage_apk_assets(source, output):
    """Copy exactly the checked package, replacing only this task-owned output tree."""
    require(not output.is_symlink(), "Generated assets directory cannot be a symlink")
    source, output = source.resolve(), output.resolve()
    require(output.name == "databaseAssets" and output.parent.name == "generated",
            "Generated output must end in generated/databaseAssets")
    require(source != output and not source.is_relative_to(output) and not output.is_relative_to(source),
            "Generated assets must not overlap the source database")
    owner = ".database-assets-owner"
    if output.exists() and any(output.iterdir()):
        require((output / owner).is_file() and (output / owner).read_text() == "database-generated-assets-v1\n",
                "Refusing to replace an unowned generated directory")
    manifest = check(source)  # Fail before touching an existing output if the source is invalid.
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".database-assets-", dir=output.parent) as temporary:
        staged = Path(temporary) / "assets"
        package = staged / "database"
        package.mkdir(parents=True)
        (staged / owner).write_text("database-generated-assets-v1\n")
        for relative in ["manifest.json", *[item["path"] for item in manifest["files"]]]:
            target = resolve(package, relative)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(resolve(source, relative), target)
        if output.exists():
            shutil.rmtree(output)
        shutil.move(str(staged), str(output))
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=Path(__file__).resolve().parents[1] / "database")
    parser.add_argument("--dataset-id")
    parser.add_argument("--dataset-version")
    parser.add_argument("--default-character")
    parser.add_argument("--output-assets", type=Path, help="Validate source, copy controlled files to an isolated build output")
    parser.add_argument("--check", action="store_true", help="Read-only validation of prepared source and exports")
    args = parser.parse_args()
    try:
        root = args.source.resolve()
        require(not (args.check and args.output_assets), "Choose --check or --output-assets")
        if args.output_assets:
            bundle = stage_apk_assets(root, args.output_assets)
            print(f"PACKAGED dataset={bundle['dataset_id']} fingerprint={bundle['dataset_fingerprint']}")
        elif args.check:
            bundle = check(root)
            print(f"VALID dataset={bundle['dataset_id']} fingerprint={bundle['dataset_fingerprint']}")
        else:
            existing = load_json(resolve(root, "manifest.json")) if resolve(root, "manifest.json").is_file() else {}
            bundle, report = prepare(root, args.dataset_id or existing.get("dataset_id", "three_body_2"),
                                     args.dataset_version or existing.get("dataset_version", "2026-10-27-phase1"),
                                     args.default_character or existing.get("default_character_id", "luo_ji"))
            print(f"PREPARED dataset={bundle['dataset_id']} source_claims={report['counts']['source_claims']} "
                  f"authorized_claims={report['counts']['authorized_claims']} fingerprint={bundle['dataset_fingerprint']}")
    except (ValueError, KeyError, OSError, sqlite3.Error) as error:
        print(f"DATABASE_BUNDLE_ERROR: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())

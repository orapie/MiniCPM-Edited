from __future__ import annotations

import argparse
import hashlib
import json
import sqlite3
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


BUILDER_ROOT = Path(__file__).resolve().parents[1]
DATA_ROOT = BUILDER_ROOT.parent / "database"
DEFAULT_DATABASE = DATA_ROOT / "knowledge_base_v2" / "character_knowledge_v2.sqlite3"
DEFAULT_MANIFEST = DATA_ROOT / "knowledge_base_v2" / "manifest.json"
DEFAULT_INDEX_MANIFEST = DATA_ROOT / "knowledge_base_v2" / "index" / "semantic_index_manifest.json"
DEFAULT_MODEL = "BAAI/bge-small-zh-v1.5"
DEFAULT_MODEL_REVISION = "7999e1d3359715c523056ef9478215996d62a620"
QUERY_INSTRUCTION = "为这个句子生成表示以用于检索相关文章："
CONTENT_TEMPLATE = "{title}\n{fact}"


def sha256_text(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def resolve_revision(model_name: str) -> str:
    try:
        from huggingface_hub import HfApi

        return str(HfApi().model_info(model_name).sha)
    except Exception:
        return "unresolved"


def build_index(
    database: Path,
    model_name: str,
    model_revision: str,
    batch_size: int,
    device: str,
    manifest_path: Path,
    index_manifest_path: Path,
) -> dict[str, Any]:
    try:
        import numpy as np
        from sentence_transformers import SentenceTransformer
    except ImportError as exc:
        raise SystemExit("需要 sentence-transformers 与 numpy：python -m pip install sentence-transformers") from exc

    db = sqlite3.connect(database)
    db.row_factory = sqlite3.Row
    try:
        rows = db.execute(
            "SELECT evidence_id, title, fact FROM evidence_units ORDER BY evidence_id"
        ).fetchall()
        if not rows:
            raise SystemExit("数据库中没有 evidence_units，请先运行 build_knowledge_base_v2.py")

        texts = [CONTENT_TEMPLATE.format(title=row["title"], fact=row["fact"]) for row in rows]
        revision = resolve_revision(model_name) if model_revision == "latest" else model_revision
        model = SentenceTransformer(model_name, revision=revision, device=device)
        vectors = model.encode(
            texts,
            batch_size=batch_size,
            show_progress_bar=True,
            convert_to_numpy=True,
            normalize_embeddings=True,
        ).astype(np.float32, copy=False)
        if vectors.ndim != 2 or vectors.shape[0] != len(rows):
            raise RuntimeError(f"unexpected embedding shape: {vectors.shape}")

        dimension = int(vectors.shape[1])
        index_payload = "\n".join(
            f"{row['evidence_id']}\t{sha256_text(text)}" for row, text in zip(rows, texts, strict=True)
        )
        content_sha256 = sha256_text(index_payload)
        index_id = "semantic-" + sha256_text(
            f"{model_name}\x1f{revision}\x1f{dimension}\x1f{CONTENT_TEMPLATE}\x1f{content_sha256}"
        )[:16]
        created_at = datetime.now(timezone.utc).replace(microsecond=0).isoformat()

        db.executescript(
            """
            CREATE TABLE IF NOT EXISTS embedding_indexes(
                index_id TEXT PRIMARY KEY,
                model_name TEXT NOT NULL,
                model_revision TEXT NOT NULL,
                dimension INTEGER NOT NULL,
                dtype TEXT NOT NULL,
                pooling TEXT NOT NULL,
                normalized INTEGER NOT NULL,
                query_instruction TEXT NOT NULL,
                passage_instruction TEXT NOT NULL,
                content_template TEXT NOT NULL,
                content_sha256 TEXT NOT NULL,
                document_count INTEGER NOT NULL,
                created_at TEXT NOT NULL,
                status TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS evidence_embeddings(
                index_id TEXT NOT NULL,
                evidence_id TEXT NOT NULL,
                content_sha256 TEXT NOT NULL,
                vector BLOB NOT NULL,
                PRIMARY KEY(index_id, evidence_id)
            );
            CREATE INDEX IF NOT EXISTS idx_embeddings_evidence
                ON evidence_embeddings(evidence_id);
            """
        )
        db.execute("UPDATE embedding_indexes SET status='superseded' WHERE status='active'")
        db.execute("DELETE FROM evidence_embeddings WHERE index_id = ?", (index_id,))
        db.execute("DELETE FROM embedding_indexes WHERE index_id = ?", (index_id,))
        db.execute(
            "INSERT INTO embedding_indexes VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            (
                index_id,
                model_name,
                revision,
                dimension,
                "float32_le",
                "cls",
                1,
                QUERY_INSTRUCTION,
                "",
                CONTENT_TEMPLATE,
                content_sha256,
                len(rows),
                created_at,
                "active",
            ),
        )
        db.executemany(
            "INSERT INTO evidence_embeddings VALUES (?, ?, ?, ?)",
            [
                (
                    index_id,
                    row["evidence_id"],
                    sha256_text(text),
                    sqlite3.Binary(vector.astype("<f4", copy=False).tobytes()),
                )
                for row, text, vector in zip(rows, texts, vectors, strict=True)
            ],
        )
        db.commit()
    finally:
        db.close()

    report = {
        "index_id": index_id,
        "model_name": model_name,
        "model_revision": revision,
        "dimension": dimension,
        "dtype": "float32_le",
        "pooling": "cls",
        "normalized": True,
        "query_instruction": QUERY_INSTRUCTION,
        "passage_instruction": "",
        "content_template": CONTENT_TEMPLATE,
        "content_sha256": content_sha256,
        "document_count": len(rows),
        "storage": "SQLite evidence_embeddings.vector BLOB",
        "created_at": created_at,
    }
    index_manifest_path.parent.mkdir(parents=True, exist_ok=True)
    index_manifest_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    if manifest_path.is_file():
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest["semantic_index"] = report
        manifest.setdefault("runtime", {})["retrieval_strategy"] = "lexical_bm25_plus_dense_rrf"
        manifest["runtime"]["rrf_k"] = 60
        manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return report


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Build BGE evidence embeddings inside the SQLite database")
    parser.add_argument("--database", type=Path, default=DEFAULT_DATABASE)
    parser.add_argument("--model", default=DEFAULT_MODEL)
    parser.add_argument("--revision", default=DEFAULT_MODEL_REVISION, help="精确模型 revision；使用 latest 会在线解析当前 SHA")
    parser.add_argument("--batch-size", type=int, default=32)
    parser.add_argument("--device", default="cpu")
    parser.add_argument("--manifest", type=Path, default=DEFAULT_MANIFEST)
    parser.add_argument("--index-manifest", type=Path, default=DEFAULT_INDEX_MANIFEST)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    report = build_index(
        args.database,
        args.model,
        args.revision,
        args.batch_size,
        args.device,
        args.manifest,
        args.index_manifest,
    )
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()

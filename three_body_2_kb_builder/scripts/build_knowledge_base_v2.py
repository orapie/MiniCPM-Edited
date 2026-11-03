from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sqlite3
from pathlib import Path
from typing import Any, Iterable


BUILDER_ROOT = Path(__file__).resolve().parents[1]
REPOSITORY_ROOT = BUILDER_ROOT.parent
DATA_ROOT = REPOSITORY_ROOT / "database"
DEFAULT_SOURCE = REPOSITORY_ROOT / "raw_data" / "three_body_2.txt"
DEFAULT_OUTPUT = DATA_ROOT / "knowledge_base_v2"
DEFAULT_COGNITION_OVERRIDES = BUILDER_ROOT / "curation" / "cognition_overrides.json"
SCHEMA_VERSION = "2.2.0"


def read_novel(path: Path) -> str:
    raw = path.read_bytes()
    for encoding in ("utf-8-sig", "gb18030"):
        try:
            return raw.decode(encoding)
        except UnicodeDecodeError:
            continue
    raise ValueError(f"无法识别小说编码：{path}")


def stable_id(prefix: str, *parts: object) -> str:
    payload = "\x1f".join(str(part) for part in parts)
    return f"{prefix}-{hashlib.sha256(payload.encode('utf-8')).hexdigest()[:16]}"


def write_json(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def write_jsonl(path: Path, records: Iterable[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="\n") as stream:
        for record in records:
            stream.write(json.dumps(record, ensure_ascii=False, separators=(",", ":")) + "\n")


def compact(text: str, limit: int = 260) -> str:
    value = re.sub(r"\s+", " ", text).strip()
    return value if len(value) <= limit else value[:limit].rstrip() + "……"


def terms(text: str, limit: int = 24) -> list[str]:
    candidates = re.findall(r"[A-Za-z0-9_]{2,}|[\u3400-\u9fff]{2,6}", text)
    stop = {"这个", "那个", "自己", "已经", "没有", "不是", "可以", "因为", "所以", "什么", "一个", "他们", "我们"}
    result: list[str] = []
    for term in candidates:
        if term not in stop and term not in result:
            result.append(term)
    return result[:limit]


def segment_scenes(text: str, source_id: str, document_id: str, target_chars: int) -> list[dict[str, Any]]:
    lines = text.splitlines()
    scenes: list[dict[str, Any]] = []
    start = 0
    while start < len(lines):
        while start < len(lines) and not lines[start].strip():
            start += 1
        if start >= len(lines):
            break
        end, size = start, 0
        while end < len(lines):
            size += len(lines[end]) + 1
            end += 1
            if size >= target_chars:
                break
        body = "\n".join(lines[start:end]).strip()
        if body:
            index = len(scenes) + 1
            scenes.append({
                "scene_id": f"scene-{index:04d}",
                "document_id": document_id,
                "source_id": source_id,
                "scene_index": index,
                "line_start": start + 1,
                "line_end": end,
                "sequence_start": start + 1,
                "sequence_end": end,
                "segmentation_method": "paragraph_target_chars",
                "text": body,
            })
        start = end
    return scenes


def chunk_scene(scene: dict[str, Any], target_chars: int = 420, max_chars: int = 720) -> list[dict[str, Any]]:
    """Split a scene into short, source-preserving evidence candidates."""
    pieces: list[tuple[int, str]] = []
    for offset, line in enumerate(str(scene["text"]).splitlines()):
        line_number = int(scene["line_start"]) + offset
        clean = line.strip()
        if not clean:
            continue
        sentences = [part.strip() for part in re.split(r"(?<=[。！？；!?])", clean) if part.strip()]
        if not sentences:
            sentences = [clean]
        for sentence in sentences:
            if len(sentence) <= max_chars:
                pieces.append((line_number, sentence))
                continue
            for start in range(0, len(sentence), max_chars):
                pieces.append((line_number, sentence[start : start + max_chars]))

    chunks: list[dict[str, Any]] = []
    buffer: list[str] = []
    line_start = line_end = 0
    for line_number, piece in pieces:
        candidate_size = sum(len(item) for item in buffer) + len(piece)
        if buffer and candidate_size > max_chars:
            chunks.append({"line_start": line_start, "line_end": line_end, "quote": "".join(buffer)})
            buffer = []
        if not buffer:
            line_start = line_number
        buffer.append(piece)
        line_end = line_number
        if sum(len(item) for item in buffer) >= target_chars:
            chunks.append({"line_start": line_start, "line_end": line_end, "quote": "".join(buffer)})
            buffer = []
    if buffer:
        chunks.append({"line_start": line_start, "line_end": line_end, "quote": "".join(buffer)})
    return chunks


TOPIC_RULES: list[tuple[str, tuple[str, ...]]] = [
    ("职业与学术背景", ("职业", "大学", "教授", "专业", "教社会学", "天文学", "物理学家", "国画")),
    ("身份与职位", ("身份", "面壁者", "执剑人", "政委", "舰长", "警察", "博士", "军官")),
    ("家庭与人物关系", ("父亲", "母亲", "妻子", "丈夫", "孩子", "女儿", "朋友", "爱人", "关系")),
    ("计划、目标与决策", ("计划", "目标", "面壁", "逃亡", "逃离", "叛逃", "保存", "威慑", "咒语", "决定", "选择", "战略")),
    ("科学理论与技术判断", ("理论", "物理", "宇宙", "水滴", "材料", "强互作用力", "强相互作用力", "恒星", "技术", "实验", "科学")),
    ("态度与情绪", ("害怕", "恐惧", "愤怒", "悲哀", "信心", "希望", "失望", "爱", "怀念")),
    ("行动与事件", ("攻击", "发射", "到达", "离开", "进入", "保护", "杀", "救", "劫持", "追杀")),
]


def classify_topic(text: str) -> str:
    # A fragment can mention a person's position while mainly describing a
    # scientific conclusion or a plan. Score all signals instead of letting
    # the first generic word (for example "军官") decide the topic.
    scores = [
        (sum(text.count(signal) for signal in signals), index, topic)
        for index, (topic, signals) in enumerate(TOPIC_RULES)
    ]
    score, _, topic = max(scores, key=lambda item: (item[0], -item[1]))
    if score:
        return topic
    return "对话与经历" if "“" in text or "”" in text else "相关经历与事实"


def predicted_questions(name: str, topic: str, quote: str) -> list[str]:
    if "水滴" in quote and ("强互作用力" in quote or "强相互作用力" in quote):
        questions = [
            "水滴的材料有什么特点？",
            "水滴为什么坚不可摧？",
            "水滴是由什么材料构成的？",
            "强相互作用力材料有多坚固？",
            f"{name}对水滴材料得出了什么结论？",
        ]
    elif "自然选择" in quote and any(signal in quote for signal in ("逃亡", "逃离", "叛逃")):
        questions = [
            "你的真实计划是什么？",
            f"{name}为什么劫持自然选择号？",
            f"{name}为什么要逃离太阳系？",
            "你为什么认为人类必败？",
            "你想怎样为人类文明保留希望？",
        ]
    elif topic == "职业与学术背景":
        questions = [
            f"{name}以前做什么工作？",
            "你以前做什么工作？",
            "你教什么课？",
            "你研究什么专业？",
            "你是天文学家吗？",
        ]
    elif topic == "身份与职位":
        questions = [f"{name}是谁？", "你是什么身份？", f"{name}担任什么职位？", f"别人怎么称呼{name}？", f"{name}在组织中负责什么？"]
    elif topic == "家庭与人物关系":
        questions = [f"{name}和对方是什么关系？", "你怎么看待这个人？", f"{name}最在意的人是谁？", f"这段关系如何影响{name}？", "你为什么信任或在意对方？"]
    elif topic == "计划、目标与决策":
        questions = [f"{name}的计划是什么？", "你为什么这样决定？", f"{name}真正的目标是什么？", "这个方案要怎么执行？", "你为什么隐瞒自己的计划？"]
    elif topic == "科学理论与技术判断":
        questions = [f"{name}得出了什么科学结论？", "这个理论是什么意思？", "你为什么这样判断？", "这项技术或材料有什么特点？", "这个发现会造成什么后果？"]
    elif topic == "态度与情绪":
        questions = [f"{name}当时是什么感受？", "你为什么会这样想？", f"{name}对此持什么态度？", "这件事让你害怕还是有希望？", f"什么触发了{name}的情绪？"]
    elif topic == "行动与事件":
        questions = [f"{name}当时做了什么？", "你为什么采取这个行动？", "这件事是怎么发生的？", f"{name}的行动造成了什么结果？", "当时谁参与了这件事？"]
    else:
        questions = [f"{name}在这段经历中发生了什么？", "你当时说了什么？", "你为什么这么说？", f"{name}如何看待这件事？", "这件事的关键事实是什么？"]
    # A few lexical hints make predicted questions less generic without becoming answer evidence.
    hints = terms(quote, 3)
    if hints and topic != "职业与学术背景":
        questions[-1] = f"{name}与{'、'.join(hints)}之间发生了什么？"
    return list(dict.fromkeys(questions))[:5]


def evidence_title(subject: str, topic: str, quote: str) -> str:
    if "水滴" in quote and ("强互作用力" in quote or "强相互作用力" in quote):
        return f"{subject}对水滴强相互作用力材料及强度的判断"
    if "自然选择" in quote and any(signal in quote for signal in ("逃亡", "逃离", "叛逃")):
        return f"{subject}劫持自然选择号逃亡并保存人类文明的计划"
    return f"{subject}的{topic}"


def build_cognition_records(
    evidence_units: list[dict[str, Any]],
    canonical_names: dict[str, str],
    aliases_by_id: dict[str, list[str]],
) -> list[dict[str, Any]]:
    """Create auditable cognition drafts without equating scene presence with knowledge."""
    records: list[dict[str, Any]] = []
    speech_verbs = "说|问|答|回答|告诉|解释|指出|承认|表示|命令|喊|补充|想|意识到|知道|认为|看到|听到"
    for unit in evidence_units:
        quote = unit["quote"]
        explicit_ids = set(unit["explicit_entity_ids"])
        for character_id in unit["entity_ids"]:
            surfaces = [canonical_names.get(character_id, ""), *aliases_by_id.get(character_id, [])]
            surfaces = [surface for surface in surfaces if surface and surface in quote]
            cue = None
            for surface in surfaces:
                if re.search(rf"{re.escape(surface)}[”’]?(?:{speech_verbs})", quote):
                    cue = "named_speech_or_cognition_cue"
                    break
                if re.search(rf"{re.escape(surface)}\s*[：:]", quote):
                    cue = "named_transcript_speaker"
                    break
                if re.search(rf"[“‘][^”’]{{0,24}}{re.escape(surface)}(?:啊|呀|老师|博士|同志|先生)", quote):
                    cue = "directly_addressed_in_dialogue"
                    break
                if quote.count(surface) >= 2 and quote.count("“") >= 2:
                    cue = "active_dialogue_participant"
                    break
            approved = character_id in explicit_ids and cue is not None
            records.append({
                "knowledge_edge_id": stable_id("knows", character_id, unit["claim_id"]),
                "character_id": character_id,
                "claim_id": unit["claim_id"],
                "evidence_id": unit["evidence_id"],
                "acquisition_method": cue or "scene_context_candidate",
                "event_id": unit["event_id"],
                "story_order": unit["story_order"],
                "sequence": unit["sequence"],
                "confidence": min(unit["confidence"], 0.92 if approved else 0.45),
                "belief_status": "believed" if approved else "unreviewed",
                "authorization_status": "approved" if approved else "candidate",
                "authorization_basis": "dialogue_or_cognition_cue" if approved else "presence_is_not_knowledge",
                "source_verification_status": "verified_span",
            })
    return records


def apply_cognition_overrides(
    records: list[dict[str, Any]],
    overrides_path: Path,
) -> int:
    if not overrides_path.is_file():
        return 0
    overrides = json.loads(overrides_path.read_text(encoding="utf-8"))
    by_key = {(record["character_id"], record["evidence_id"]): record for record in records}
    applied = 0
    for override in overrides:
        key = (override["character_id"], override["evidence_id"])
        if key not in by_key:
            raise ValueError(f"认知审核覆盖找不到对应记录：{key}")
        record = by_key[key]
        record.update({
            "acquisition_method": override["acquisition_method"],
            "confidence": float(override["confidence"]),
            "belief_status": override["belief_status"],
            "authorization_status": "approved",
            "authorization_basis": "reviewed_source_span:" + override["review_reason"],
        })
        applied += 1
    return applied


def build_character_states(
    cards: list[dict[str, Any]],
    cutoffs: dict[str, dict[str, int]],
) -> list[dict[str, Any]]:
    states: list[dict[str, Any]] = []
    for card in cards:
        character_id = card["npc_id"]
        cutoff = card["knowledge_scope"]["story_cutoff"]
        dynamic = card.get("dynamic_state", {})
        values = {
            "scene": dynamic.get("scene"),
            "emotion": dynamic.get("emotion"),
            "quest_state": dynamic.get("quest_state"),
            "current_goal": dynamic.get("current_goal"),
            "affinity": dynamic.get("affinity"),
        }
        for state_type, value in values.items():
            if value is None:
                continue
            states.append({
                "state_id": stable_id("state", character_id, state_type, cutoff),
                "character_id": character_id,
                "state_type": state_type,
                "value": value,
                "valid_from_event": cutoff,
                "valid_from_sequence": cutoffs[cutoff]["sequence"],
                "valid_to_event": None,
                "valid_to_sequence": None,
                "source_tier": "curated_card",
                "verification_status": "accepted",
            })
    return states


def build_evidence_units(
    scenes: list[dict[str, Any]],
    scene_event: dict[str, str],
    name_lookup: dict[str, str],
    canonical_names: dict[str, str],
    aliases_by_id: dict[str, list[str]],
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    surfaces = sorted(name_lookup.items(), key=lambda item: len(item[0]), reverse=True)
    units: list[dict[str, Any]] = []
    expressions: list[dict[str, Any]] = []
    for scene in scenes:
        event_id = scene_event.get(scene["scene_id"])
        if event_id is None:
            continue
        explicit_lines: list[int] = []
        scene_entities: list[str] = []
        for offset, line in enumerate(str(scene["text"]).splitlines()):
            for surface, entity_id in surfaces:
                if surface in line:
                    explicit_lines.append(int(scene["line_start"]) + offset)
                    if entity_id not in scene_entities:
                        scene_entities.append(entity_id)
        for chunk in chunk_scene(scene):
            explicit = list(dict.fromkeys(entity_id for surface, entity_id in surfaces if surface in chunk["quote"]))
            distance = min((min(abs(chunk["line_start"] - line), abs(chunk["line_end"] - line)) for line in explicit_lines), default=999)
            if not explicit and distance > 2:
                continue
            entity_ids = explicit or scene_entities
            if not entity_ids:
                continue
            display_names = [canonical_names[item] for item in entity_ids if item in canonical_names]
            primary_name = display_names[0]
            topic = classify_topic(chunk["quote"])
            subject = "与".join(display_names[:2])
            title = evidence_title(subject, topic, chunk["quote"])
            fact = f"{title}：{compact(chunk['quote'], 300)}"
            evidence_id = stable_id("evd", scene["scene_id"], chunk["line_start"], chunk["line_end"], chunk["quote"])
            questions = predicted_questions(primary_name, topic, chunk["quote"])
            aliases = list(dict.fromkeys(alias for entity_id in entity_ids for alias in aliases_by_id.get(entity_id, [])))
            unit = {
                "evidence_id": evidence_id,
                "claim_id": stable_id("claim", evidence_id),
                "scene_id": scene["scene_id"],
                "event_id": event_id,
                "title": title,
                "fact": fact,
                "source_id": scene["source_id"],
                "line_start": chunk["line_start"],
                "line_end": chunk["line_end"],
                "quote": chunk["quote"],
                "entity_ids": entity_ids,
                "explicit_entity_ids": explicit,
                "aliases": aliases,
                "topic": topic,
                "story_order": int(event_id.split("-")[1]),
                "sequence": chunk["line_start"],
                "confidence": 0.9 if explicit else 0.78,
                "question_count": len(questions),
            }
            units.append(unit)
            expression_values: list[tuple[str, str]] = [("title", title), ("fact", fact)]
            expression_values.extend(("alias", f"{alias} {topic}") for alias in aliases)
            expression_values.extend(("predicted_question", question) for question in questions)
            seen: set[str] = set()
            for expression_type, value in expression_values:
                normalized = re.sub(r"\s+", " ", value).strip()
                if not normalized or normalized in seen:
                    continue
                seen.add(normalized)
                expressions.append({
                    "expression_id": stable_id("expr", evidence_id, expression_type, normalized),
                    "evidence_id": evidence_id,
                    "expression_type": expression_type,
                    "text": normalized,
                })
    return units, expressions


def build_sqlite(
    path: Path,
    document: dict[str, Any],
    scenes: list[dict[str, Any]],
    entities: list[dict[str, Any]],
    aliases: list[dict[str, Any]],
    mentions: list[dict[str, Any]],
    events: list[dict[str, Any]],
    claims: list[dict[str, Any]],
    evidence: list[dict[str, Any]],
    evidence_units: list[dict[str, Any]],
    retrieval_expressions: list[dict[str, Any]],
    knowledge_edges: list[dict[str, Any]],
    relations: list[dict[str, Any]],
    character_states: list[dict[str, Any]],
    snapshots: list[dict[str, Any]],
    cutoffs: dict[str, dict[str, int]],
) -> None:
    temp = path.with_suffix(".tmp.sqlite3")
    if temp.exists():
        temp.unlink()
    db = sqlite3.connect(temp)
    try:
        db.executescript("""
        PRAGMA foreign_keys=ON;
        CREATE TABLE metadata(key TEXT PRIMARY KEY, value TEXT NOT NULL);
        CREATE TABLE cutoffs(event_id TEXT PRIMARY KEY, story_order INTEGER NOT NULL, sequence INTEGER NOT NULL);
        CREATE TABLE documents(document_id TEXT PRIMARY KEY, source_id TEXT UNIQUE NOT NULL, origin TEXT NOT NULL, source_tier TEXT NOT NULL, sha256 TEXT NOT NULL, line_count INTEGER NOT NULL, char_count INTEGER NOT NULL);
        CREATE TABLE scenes(scene_id TEXT PRIMARY KEY, document_id TEXT NOT NULL, source_id TEXT NOT NULL, scene_index INTEGER NOT NULL, line_start INTEGER NOT NULL, line_end INTEGER NOT NULL, sequence_start INTEGER NOT NULL, sequence_end INTEGER NOT NULL, text TEXT NOT NULL);
        CREATE TABLE entities(entity_id TEXT PRIMARY KEY, entity_type TEXT NOT NULL, canonical_name TEXT NOT NULL, identity_kind TEXT NOT NULL, resolution_status TEXT NOT NULL, description TEXT NOT NULL);
        CREATE TABLE aliases(alias_id TEXT PRIMARY KEY, entity_id TEXT NOT NULL, alias TEXT NOT NULL, alias_type TEXT NOT NULL, confidence REAL NOT NULL);
        CREATE TABLE entity_mentions(mention_id TEXT PRIMARY KEY, scene_id TEXT NOT NULL, surface TEXT NOT NULL, entity_id TEXT, line INTEGER NOT NULL, resolution_status TEXT NOT NULL, resolution_method TEXT NOT NULL, confidence REAL NOT NULL);
        CREATE TABLE utterances(utterance_id TEXT PRIMARY KEY, scene_id TEXT NOT NULL, speaker_id TEXT, speaker_resolution_method TEXT NOT NULL, line_start INTEGER NOT NULL, line_end INTEGER NOT NULL, text TEXT NOT NULL, confidence REAL NOT NULL);
        CREATE TABLE events(event_id TEXT PRIMARY KEY, event_type TEXT NOT NULL, summary TEXT NOT NULL, story_order INTEGER, narrative_sequence INTEGER NOT NULL, participants_json TEXT NOT NULL, witnesses_json TEXT NOT NULL, informed_json TEXT NOT NULL, source_tier TEXT NOT NULL, verification_status TEXT NOT NULL);
        CREATE TABLE claims(claim_id TEXT PRIMARY KEY, claim_scope TEXT NOT NULL, subject_ids_json TEXT NOT NULL, predicate TEXT NOT NULL, object_text TEXT NOT NULL, statement TEXT NOT NULL, event_id TEXT, modality TEXT NOT NULL, polarity TEXT NOT NULL, valid_from_sequence INTEGER NOT NULL, valid_to_sequence INTEGER, importance REAL NOT NULL, confidence REAL NOT NULL, keywords_json TEXT NOT NULL, source_tier TEXT NOT NULL, canon_status TEXT NOT NULL, verification_status TEXT NOT NULL);
        CREATE TABLE evidence(evidence_id TEXT PRIMARY KEY, claim_id TEXT NOT NULL, source_id TEXT NOT NULL, line_start INTEGER NOT NULL, line_end INTEGER NOT NULL, quote TEXT NOT NULL, sha256 TEXT NOT NULL, verification_method TEXT NOT NULL, verification_status TEXT NOT NULL);
        CREATE TABLE evidence_units(evidence_id TEXT PRIMARY KEY, claim_id TEXT NOT NULL, scene_id TEXT NOT NULL, event_id TEXT NOT NULL, title TEXT NOT NULL, fact TEXT NOT NULL, source_id TEXT NOT NULL, line_start INTEGER NOT NULL, line_end INTEGER NOT NULL, quote TEXT NOT NULL, entity_ids_json TEXT NOT NULL, explicit_entity_ids_json TEXT NOT NULL, aliases_json TEXT NOT NULL, topic TEXT NOT NULL, story_order INTEGER NOT NULL, sequence INTEGER NOT NULL, confidence REAL NOT NULL, question_count INTEGER NOT NULL);
        CREATE TABLE retrieval_expressions(expression_id TEXT PRIMARY KEY, evidence_id TEXT NOT NULL, expression_type TEXT NOT NULL, text TEXT NOT NULL);
        CREATE TABLE knowledge_edges(knowledge_edge_id TEXT PRIMARY KEY, character_id TEXT NOT NULL, claim_id TEXT NOT NULL, evidence_id TEXT NOT NULL, acquisition_method TEXT NOT NULL, acquired_at_event TEXT, acquired_story_order INTEGER, acquired_sequence INTEGER NOT NULL, confidence REAL NOT NULL, belief_status TEXT NOT NULL, authorization_status TEXT NOT NULL, authorization_basis TEXT NOT NULL, source_verification_status TEXT NOT NULL);
        CREATE TABLE relations(relation_id TEXT PRIMARY KEY, source_entity_id TEXT NOT NULL, target_entity_id TEXT NOT NULL, relation_type TEXT NOT NULL, description TEXT NOT NULL, valid_from_sequence INTEGER NOT NULL, valid_to_sequence INTEGER, confidence REAL NOT NULL, source_tier TEXT NOT NULL, canon_status TEXT NOT NULL, evidence_refs_json TEXT NOT NULL);
        CREATE TABLE character_states(state_id TEXT PRIMARY KEY, character_id TEXT NOT NULL, state_type TEXT NOT NULL, value_json TEXT NOT NULL, valid_from_event TEXT NOT NULL, valid_from_sequence INTEGER NOT NULL, valid_to_event TEXT, valid_to_sequence INTEGER, source_tier TEXT NOT NULL, verification_status TEXT NOT NULL);
        CREATE TABLE snapshots(snapshot_id TEXT PRIMARY KEY, character_id TEXT NOT NULL, story_cutoff TEXT NOT NULL, cutoff_sequence INTEGER NOT NULL, claim_ids_json TEXT NOT NULL, claim_count INTEGER NOT NULL);
        CREATE INDEX idx_claims_event ON claims(event_id);
        CREATE INDEX idx_knowledge_character_sequence ON knowledge_edges(character_id, acquired_sequence);
        CREATE INDEX idx_knowledge_claim ON knowledge_edges(claim_id);
        CREATE INDEX idx_evidence_scene ON evidence_units(scene_id, story_order);
        CREATE INDEX idx_evidence_event ON evidence_units(event_id);
        CREATE INDEX idx_expression_evidence ON retrieval_expressions(evidence_id);
        CREATE VIRTUAL TABLE claims_fts USING fts5(claim_id UNINDEXED, statement, keywords, tokenize='trigram');
        CREATE VIRTUAL TABLE retrieval_fts USING fts5(expression_id UNINDEXED, evidence_id UNINDEXED, expression_type UNINDEXED, text, tokenize='trigram');
        """)
        db.executemany("INSERT INTO metadata VALUES (?, ?)", [
            ("schema_version", SCHEMA_VERSION),
            ("knowledge_base_version", "v2"),
            ("retrieval_unit", "evidence_fragment"),
            ("retrieval_strategy", "doc2query_style_expansion_deduplicated_by_evidence_id"),
            ("fts_tokenizer", "trigram"),
        ])
        db.executemany("INSERT INTO cutoffs VALUES (?, ?, ?)", [(key, item["story_order"], item["sequence"]) for key, item in cutoffs.items()])
        db.execute("INSERT INTO documents VALUES (?, ?, ?, ?, ?, ?, ?)", (document["document_id"], document["source_id"], document["origin"], document["source_tier"], document["sha256"], document["line_count"], document["char_count"]))
        db.executemany("INSERT INTO scenes VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", [(s["scene_id"], s["document_id"], s["source_id"], s["scene_index"], s["line_start"], s["line_end"], s["sequence_start"], s["sequence_end"], s["text"]) for s in scenes])
        db.executemany("INSERT INTO entities VALUES (?, ?, ?, ?, ?, ?)", [(e["entity_id"], e["entity_type"], e["canonical_name"], e["identity_kind"], e["resolution_status"], e["description"]) for e in entities])
        db.executemany("INSERT INTO aliases VALUES (?, ?, ?, ?, ?)", [(a["alias_id"], a["entity_id"], a["alias"], a["alias_type"], a["confidence"]) for a in aliases])
        db.executemany("INSERT INTO entity_mentions VALUES (?, ?, ?, ?, ?, ?, ?, ?)", [(m["mention_id"], m["scene_id"], m["surface"], m["entity_id"], m["line"], m["resolution_status"], m["resolution_method"], m["confidence"]) for m in mentions])
        db.executemany("INSERT INTO events VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", [(e["event_id"], e["event_type"], e["summary"], e["story_order"], e["narrative_sequence"], json.dumps(e["participants"], ensure_ascii=False), "[]", "[]", "canon", "source_span_verified") for e in events])
        db.executemany("INSERT INTO claims VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", [(c["claim_id"], "story", json.dumps(c["subject_ids"], ensure_ascii=False), "evidence_fact", c["fact"], c["fact"], c["event_id"], "asserted", "positive", c["sequence"], None, c["importance"], c["confidence"], json.dumps(c["keywords"], ensure_ascii=False), "canon", "accepted", "source_span_verified") for c in claims])
        db.executemany("INSERT INTO claims_fts VALUES (?, ?, ?)", [(c["claim_id"], c["fact"], " ".join(c["keywords"])) for c in claims])
        db.executemany("INSERT INTO evidence VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", [(e["evidence_id"], e["claim_id"], e["source_id"], e["line_start"], e["line_end"], e["quote"], e["sha256"], "direct_source_fragment", "verified") for e in evidence])
        db.executemany("INSERT INTO evidence_units VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", [(u["evidence_id"], u["claim_id"], u["scene_id"], u["event_id"], u["title"], u["fact"], u["source_id"], u["line_start"], u["line_end"], u["quote"], json.dumps(u["entity_ids"], ensure_ascii=False), json.dumps(u["explicit_entity_ids"], ensure_ascii=False), json.dumps(u["aliases"], ensure_ascii=False), u["topic"], u["story_order"], u["sequence"], u["confidence"], u["question_count"]) for u in evidence_units])
        db.executemany("INSERT INTO retrieval_expressions VALUES (?, ?, ?, ?)", [(e["expression_id"], e["evidence_id"], e["expression_type"], e["text"]) for e in retrieval_expressions])
        db.executemany("INSERT INTO retrieval_fts VALUES (?, ?, ?, ?)", [(e["expression_id"], e["evidence_id"], e["expression_type"], e["text"]) for e in retrieval_expressions])
        db.executemany("INSERT INTO knowledge_edges VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", [(k["knowledge_edge_id"], k["character_id"], k["claim_id"], k["evidence_id"], k["acquisition_method"], k["event_id"], k["story_order"], k["sequence"], k["confidence"], k["belief_status"], k["authorization_status"], k["authorization_basis"], k["source_verification_status"]) for k in knowledge_edges])
        db.executemany("INSERT INTO relations VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", [(r["relation_id"], r["source_entity_id"], r["target_entity_id"], r["relation_type"], r["description"], 1, None, 1.0, "curated", "accepted", json.dumps(r["evidence_refs"], ensure_ascii=False)) for r in relations])
        db.executemany("INSERT INTO character_states VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", [(s["state_id"], s["character_id"], s["state_type"], json.dumps(s["value"], ensure_ascii=False), s["valid_from_event"], s["valid_from_sequence"], s["valid_to_event"], s["valid_to_sequence"], s["source_tier"], s["verification_status"]) for s in character_states])
        db.executemany("INSERT INTO snapshots VALUES (?, ?, ?, ?, ?, ?)", [(s["snapshot_id"], s["character_id"], s["story_cutoff"], s["cutoff_sequence"], json.dumps(s["claim_ids"], ensure_ascii=False), s["claim_count"]) for s in snapshots])
        db.commit()
    finally:
        db.close()
    os.replace(temp, path)


def build(source: Path, cards_dir: Path, output: Path, scene_target_chars: int, cognition_overrides: Path) -> dict[str, Any]:
    text = read_novel(source)
    lines = text.splitlines()
    cards = [json.loads(path.read_text(encoding="utf-8")) for path in sorted(cards_dir.glob("*.json"))]
    if len(cards) != 5:
        raise SystemExit(f"需要 5 张角色卡；当前找到 {len(cards)} 张：{cards_dir}")

    normalized = "\n".join(line.rstrip() for line in lines)
    document_id = stable_id("doc", source.name, normalized)
    document = {"document_id": document_id, "source_id": source.name, "origin": str(source.resolve()), "source_tier": "canon", "sha256": hashlib.sha256(normalized.encode("utf-8")).hexdigest(), "line_count": len(lines), "char_count": len(normalized), "text": normalized}
    scenes = segment_scenes(normalized, source.name, document_id, scene_target_chars)

    entities: list[dict[str, Any]] = []
    aliases: list[dict[str, Any]] = []
    name_lookup: dict[str, str] = {}
    canonical_names: dict[str, str] = {}
    aliases_by_id: dict[str, list[str]] = {}
    for card in cards:
        npc_id = card["npc_id"]
        identity = card["identity_core"]
        canonical_names[npc_id] = identity["name"]
        aliases_by_id[npc_id] = list(identity["aliases"])
        entities.append({"entity_id": npc_id, "entity_type": "character", "canonical_name": identity["name"], "identity_kind": "canonical_character", "resolution_status": "curated", "description": identity["worldview_position"]})
        name_lookup[identity["name"]] = npc_id
        for alias in identity["aliases"]:
            name_lookup[alias] = npc_id
            aliases.append({"alias_id": stable_id("alias", npc_id, alias), "entity_id": npc_id, "alias": alias, "alias_type": "declared_alias", "confidence": 1.0})

    surfaces = sorted(name_lookup.items(), key=lambda item: len(item[0]), reverse=True)
    mentions: list[dict[str, Any]] = []
    events: list[dict[str, Any]] = []
    cutoffs: dict[str, dict[str, int]] = {}
    scene_event: dict[str, str] = {}
    events_by_character: dict[str, list[str]] = {card["npc_id"]: [] for card in cards}
    for scene in scenes:
        participants: list[str] = []
        for surface, entity_id in surfaces:
            for match in re.finditer(re.escape(surface), scene["text"]):
                if entity_id not in participants:
                    participants.append(entity_id)
                line = int(scene["line_start"]) + scene["text"][: match.start()].count("\n")
                mentions.append({"mention_id": stable_id("mention", scene["scene_id"], surface, match.start()), "scene_id": scene["scene_id"], "surface": surface, "entity_id": entity_id, "line": line, "resolution_status": "resolved", "resolution_method": "curated_name_or_alias_exact_match", "confidence": 1.0})
        if not participants:
            continue
        event_id = f"evt-{len(events) + 1:03d}"
        scene_event[scene["scene_id"]] = event_id
        event = {"event_id": event_id, "event_type": "character_scene", "summary": compact(scene["text"]), "story_order": len(events) + 1, "narrative_sequence": scene["sequence_start"], "participants": participants}
        events.append(event)
        cutoffs[event_id] = {"story_order": event["story_order"], "sequence": scene["sequence_end"]}
        for character_id in participants:
            events_by_character[character_id].append(event_id)

    evidence_units, retrieval_expressions = build_evidence_units(scenes, scene_event, name_lookup, canonical_names, aliases_by_id)
    claims = [{"claim_id": unit["claim_id"], "subject_ids": unit["entity_ids"], "event_id": unit["event_id"], "fact": unit["fact"], "sequence": unit["sequence"], "importance": min(1.0, 0.62 + 0.06 * len(unit["entity_ids"])), "confidence": unit["confidence"], "keywords": terms(unit["title"] + " " + unit["fact"])} for unit in evidence_units]
    evidence = [{"evidence_id": unit["evidence_id"], "claim_id": unit["claim_id"], "source_id": unit["source_id"], "line_start": unit["line_start"], "line_end": unit["line_end"], "quote": unit["quote"], "sha256": hashlib.sha256(unit["quote"].encode("utf-8")).hexdigest()} for unit in evidence_units]
    knowledge_edges = build_cognition_records(evidence_units, canonical_names, aliases_by_id)
    applied_cognition_overrides = apply_cognition_overrides(knowledge_edges, cognition_overrides)

    relations: list[dict[str, Any]] = []
    entity_ids = {entity["entity_id"] for entity in entities}
    for card in cards:
        for relation in card["relationships"]:
            target_id = relation["target_npc_id"] or stable_id("entity", relation["target_name"])
            if target_id not in entity_ids:
                entities.append({"entity_id": target_id, "entity_type": "character", "canonical_name": relation["target_name"], "identity_kind": "relationship_target", "resolution_status": "curated_label", "description": relation["basis"]})
                entity_ids.add(target_id)
            relations.append({"relation_id": stable_id("rel", card["npc_id"], target_id, relation["relationship_type"]), "source_entity_id": card["npc_id"], "target_entity_id": target_id, "relation_type": relation["relationship_type"], "description": relation["attitude"], "evidence_refs": relation["evidence_refs"]})

    units_by_event: dict[str, list[dict[str, Any]]] = {}
    for unit in evidence_units:
        units_by_event.setdefault(unit["event_id"], []).append(unit)
    snapshots: list[dict[str, Any]] = []
    for card in cards:
        approved_edges = [
            edge for edge in knowledge_edges
            if edge["character_id"] == card["npc_id"] and edge["authorization_status"] == "approved"
        ]
        refs = list(dict.fromkeys(edge["event_id"] for edge in approved_edges)) or [events[0]["event_id"]]
        declared_cutoff = card.get("knowledge_scope", {}).get("story_cutoff")
        cutoff = declared_cutoff if declared_cutoff in cutoffs else refs[-1]
        card["knowledge_scope"]["world_fact_refs"] = refs
        card["knowledge_scope"]["story_cutoff"] = cutoff
        memory_refs = refs if len(refs) <= 8 else [refs[round(i * (len(refs) - 1) / 7)] for i in range(8)]
        card["episodic_memory"] = []
        for event_id in memory_refs:
            unit = units_by_event[event_id][0]
            card["episodic_memory"].append({"fact_ref": event_id, "valid_from": event_id, "valid_to": None, "source": "direct", "visibility": [card["npc_id"]], "confidence": unit["confidence"], "evidence_ref": f"{source.name}:L{unit['line_start']}-L{unit['line_end']}"})
        (cards_dir / f"{card['npc_id']}.json").write_text(json.dumps(card, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        claim_ids = list(dict.fromkeys(edge["claim_id"] for edge in approved_edges))
        snapshots.append({"snapshot_id": stable_id("snapshot", card["npc_id"], cutoff), "character_id": card["npc_id"], "story_cutoff": cutoff, "cutoff_sequence": cutoffs[cutoff]["sequence"], "claim_ids": claim_ids, "claim_count": len(claim_ids)})
    character_states = build_character_states(cards, cutoffs)

    output.mkdir(parents=True, exist_ok=True)
    write_jsonl(output / "corpus" / "documents.jsonl", [document])
    write_jsonl(output / "corpus" / "scenes.jsonl", scenes)
    write_jsonl(output / "corpus" / "evidence_units.jsonl", evidence_units)
    write_jsonl(output / "corpus" / "utterances.jsonl", [])
    write_jsonl(output / "graph" / "entities.jsonl", entities)
    write_jsonl(output / "graph" / "aliases.jsonl", aliases)
    write_jsonl(output / "graph" / "entity_mentions.jsonl", mentions)
    write_jsonl(output / "graph" / "events.jsonl", events)
    write_jsonl(output / "graph" / "claims.jsonl", claims)
    write_jsonl(output / "graph" / "evidence.jsonl", evidence)
    write_jsonl(output / "graph" / "knowledge_edges.jsonl", knowledge_edges)
    write_jsonl(output / "graph" / "relations.jsonl", relations)
    write_jsonl(output / "graph" / "character_states.jsonl", character_states)
    write_jsonl(output / "index" / "retrieval_expressions.jsonl", retrieval_expressions)
    write_jsonl(output / "quality" / "conflicts.jsonl", [])
    snapshots_dir = output / "snapshots"
    if snapshots_dir.is_dir():
        for stale in snapshots_dir.glob("*.json"):
            stale.unlink()
    for snapshot in snapshots:
        write_json(snapshots_dir / f"{snapshot['character_id']}__{snapshot['story_cutoff']}.json", snapshot)

    event_source = {event["event_id"]: next(scene for scene in scenes if scene_event.get(scene["scene_id"]) == event["event_id"]) for event in events}
    story_records = []
    for event in events:
        scene = event_source[event["event_id"]]
        participants = event["participants"]
        story_records.append({"event_id": event["event_id"], "time_index": event["story_order"], "fact": event["summary"], "participants": participants, "witnesses": [], "informed_characters": [], "available_after": event["event_id"], "source_type": "narration", "importance": min(1.0, 0.55 + 0.08 * len(participants)), "confidence": 0.9, "visibility": participants, "keywords": terms(event["summary"]), "evidence_ref": f"{source.name}:L{scene['line_start']}-L{scene['line_end']}", "knowledge_by_character": {character_id: {"source": "direct", "available_after": event["event_id"], "confidence": 0.9} for character_id in participants}})
    write_jsonl(output.parent / "story" / "story_events.jsonl", story_records)

    sqlite_path = output / "character_knowledge_v2.sqlite3"
    build_sqlite(sqlite_path, document, scenes, entities, aliases, mentions, events, claims, evidence, evidence_units, retrieval_expressions, knowledge_edges, relations, character_states, snapshots, cutoffs)
    average_quote = round(sum(len(unit["quote"]) for unit in evidence_units) / len(evidence_units), 1)
    manifest = {
        "schema_version": SCHEMA_VERSION,
        "knowledge_base_version": "v2",
        "title": "三体Ⅱ：黑暗森林",
        "build_mode": "three_layer_evidence_doc2query_style",
        "source": str(source.resolve()),
        "retrieval_design": {
            "layers": ["raw_scene", "evidence_fragment", "retrieval_expression"],
            "answer_basis": ["evidence_unit.fact", "evidence_unit.quote"],
            "retrieval_only": ["title", "alias", "predicted_question"],
            "deduplication_key": "evidence_id",
            "questions_per_evidence": "3-5",
            "cognition": ["character_id", "acquired_at_event", "acquired_sequence", "acquisition_method", "confidence"],
            "cognition_override_file": str(cognition_overrides.resolve()) if cognition_overrides.is_file() else None,
            "applied_cognition_overrides": applied_cognition_overrides,
        },
        "counts": {"documents": 1, "scenes": len(scenes), "evidence_units": len(evidence_units), "retrieval_expressions": len(retrieval_expressions), "predicted_questions": sum(1 for item in retrieval_expressions if item["expression_type"] == "predicted_question"), "resolved_mentions": len(mentions), "entities": len(entities), "aliases": len(aliases), "events": len(events), "claims": len(claims), "evidence": len(evidence), "knowledge_edges": len(knowledge_edges), "approved_knowledge_edges": sum(1 for edge in knowledge_edges if edge["authorization_status"] == "approved"), "candidate_knowledge_edges": sum(1 for edge in knowledge_edges if edge["authorization_status"] == "candidate"), "relations": len(relations), "character_states": len(character_states), "snapshots": len(snapshots)},
        "metrics": {"average_scene_chars": round(sum(len(scene["text"]) for scene in scenes) / len(scenes), 1), "average_evidence_chars": average_quote, "max_evidence_chars": max(len(unit["quote"]) for unit in evidence_units)},
        "cutoffs": cutoffs,
        "runtime": {"database": "character_knowledge_v2.sqlite3", "primary_fts_table": "retrieval_fts", "legacy_fts_table": "claims_fts", "fts_tokenizer": "trigram", "minimum_inputs": ["character_id", "query", "story_cutoff"]},
        "validation": {"passed": bool(evidence_units and retrieval_expressions and len(cards) == 5 and all(3 <= unit["question_count"] <= 5 for unit in evidence_units)), "error_count": 0, "errors": []},
    }
    write_json(output / "manifest.json", manifest)
    write_json(output / "quality" / "validation.json", manifest["validation"])
    return {"version": "v2.2", "output_dir": str(output.resolve()), "sqlite": str(sqlite_path.resolve()), **manifest["counts"], **manifest["metrics"], "validation_passed": manifest["validation"]["passed"]}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Build the Three-Body II evidence-fragment knowledge base")
    parser.add_argument("--three-body-2", action="store_true", help="兼容旧命令；当前脚本始终构建《三体Ⅱ》")
    parser.add_argument("--source", action="append", dest="sources")
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--cards", type=Path, default=DATA_ROOT / "characters")
    parser.add_argument("--scene-target-chars", type=int, default=2200)
    parser.add_argument("--cognition-overrides", type=Path, default=DEFAULT_COGNITION_OVERRIDES)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    source = Path(args.sources[0]) if args.sources else DEFAULT_SOURCE
    print(json.dumps(build(source, args.cards, args.output, args.scene_target_chars, args.cognition_overrides), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()

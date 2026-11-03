from __future__ import annotations

import argparse
import json
from pathlib import Path

from query_evidence import DEFAULT_DATABASE, retrieve


CASES = [
    {"group": "baseline", "npc": "luo_ji", "cutoff": "evt-125", "query": "你以前做什么工作？", "expected": ["天文学", "社会学"]},
    {"group": "baseline", "npc": "luo_ji", "cutoff": "evt-125", "query": "你教什么课？", "expected": ["社会学"]},
    {"group": "baseline", "npc": "luo_ji", "cutoff": "evt-125", "query": "你是天文学家吗？", "expected": ["天文学"]},
    {"group": "baseline", "npc": "shi_qiang", "cutoff": "evt-118", "query": "为什么别人叫你大史？", "expected": ["大史"]},
    {"group": "baseline", "npc": "zhang_beihai", "cutoff": "evt-110", "query": "你的真实计划是什么？", "expected": ["自然选择", "逃"]},
    {"group": "baseline", "npc": "ding_yi", "cutoff": "evt-113", "query": "水滴的材料有什么特点？", "expected": ["水滴", "材料"]},
    {"group": "baseline", "npc": "zhuang_yan", "cutoff": "evt-125", "query": "你学的是什么专业？", "expected": ["国画"]},
    {"group": "paraphrase", "npc": "shi_qiang", "cutoff": "evt-118", "query": "大家平时咋称呼你？", "expected": ["大史"]},
    {"group": "paraphrase", "npc": "zhang_beihai", "cutoff": "evt-110", "query": "你带着战舰跑路究竟想保住什么？", "expected": ["自然选择", "人类文明"]},
    {"group": "paraphrase", "npc": "ding_yi", "cutoff": "evt-113", "query": "那滴眼泪似的东西凭什么那么硬？", "expected": ["水滴", "强度"]},
    {"group": "paraphrase", "npc": "zhuang_yan", "cutoff": "evt-125", "query": "你念的是哪门艺术？", "expected": ["国画"]},
]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run retrieval regression cases")
    parser.add_argument("--database", type=Path, default=DEFAULT_DATABASE)
    parser.add_argument("--output", type=Path)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    runs = {}
    for mode in ("lexical", "semantic", "hybrid"):
        details = []
        passed = 0
        group_passed = {"baseline": 0, "paraphrase": 0}
        group_total = {"baseline": 0, "paraphrase": 0}
        for case in CASES:
            results = retrieve(args.database, case["npc"], case["cutoff"], case["query"], limit=3, retrieval_mode=mode)
            combined = "\n".join(item["fact"] + "\n" + item["quote"] for item in results)
            missing = [term for term in case["expected"] if term not in combined]
            ok = not missing
            passed += int(ok)
            group_passed[case["group"]] += int(ok)
            group_total[case["group"]] += 1
            details.append({"group": case["group"], "query": case["query"], "npc": case["npc"], "passed": ok, "missing": missing, "top_evidence": results[0]["evidence_id"] if results else None, "top_title": results[0]["title"] if results else None})
        runs[mode] = {
            "cases": len(CASES),
            "passed": passed,
            "recall_at_3": round(passed / len(CASES), 4),
            "recall_by_group": {group: round(group_passed[group] / group_total[group], 4) for group in group_total},
            "details": details,
        }
    report = {"database": str(args.database.resolve()), "knowledge_policy": "approved", "runs": runs}
    rendered = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(rendered, encoding="utf-8")
    print(rendered, end="")
    baseline_ok = runs["hybrid"]["recall_by_group"]["baseline"] == 1.0
    raise SystemExit(0 if baseline_ok else 1)


if __name__ == "__main__":
    main()

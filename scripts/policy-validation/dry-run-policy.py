#!/usr/bin/env python3
"""Conservative title-level policy v1 dry-run; never publishes records."""

import argparse
import collections
import hashlib
import json
import re


POLICY_VERSION = "discovery-candidate-v1.0.0"
STRONG = set("HS010100 HS010200 HS010300 HS010400 HS010500 HS010600 EX010100 AC030200 VE040100 VE040200".split())
CONDITIONAL = set("HS010700 HS011100 HS011200 EX040200 HS020100 HS020300 HS030100 VE070100 VE070200 VE070300 VE090100 VE090400 FD040400 FD050200 SH050100 SH060100 SH060200 NA040700 VE010100 VE010900 EX060100 EX060300".split())
CAFE = "FD050100"
VILLAGE = "EX030100"
SIGNAL = re.compile(r"한옥|고택|궁궐|고궁|민속|전통|향교|서원|국악|한복|한지|다도|차문화|문화재|왕릉|종택|초가|돌담|옛길|한과|떡만들기|전통주")
STRONG_NEGATIVE = re.compile(r"드론|케이블카|카지노|골프|워터파크|키즈카페|복합쇼핑몰")


def classify(record):
    title = (record.get("title") or "").strip()
    code = record.get("lclsSystm3") or ""
    try:
        x, y = float(record.get("mapx") or 0), float(record.get("mapy") or 0)
    except (TypeError, ValueError):
        x = y = 0
    if not record.get("contentid") or not title or not (124 <= x <= 132 and 33 <= y <= 39):
        return "EXCLUDE", "SOURCE_INVALID", None
    if STRONG_NEGATIVE.search(title):
        return "EXCLUDE", "UNRELATED_FACILITY", None
    if code in STRONG:
        role = "CORE_TRADITIONAL_PLACE" if code.startswith("HS") or code == "AC030200" else "TRADITIONAL_EXPERIENCE"
        return "INCLUDE", "STRONG_TAXONOMY_PENDING_DETAIL_GATE", role
    if code in CONDITIONAL:
        if SIGNAL.search(title):
            return "INCLUDE", "CONDITIONAL_TITLE_SIGNAL_PENDING_DETAIL_GATE", "CORE_TRADITIONAL_PLACE" if code.startswith("HS") else "SURROUNDING_CULTURE"
        return "REVIEW", "CONDITIONAL_NEEDS_CONTEXT", None
    if code in (CAFE, VILLAGE):
        if SIGNAL.search(title):
            return "REVIEW", "BROAD_CLASS_TITLE_SIGNAL_NEEDS_EVIDENCE", None
        return "EXCLUDE", "BROAD_CLASS_NO_TRADITION_SIGNAL", None
    if SIGNAL.search(title) and not code.startswith(("EV", "LS", "C01")):
        return "REVIEW", "KEYWORD_RESCUE_NEEDS_EVIDENCE", None
    return "EXCLUDE", "RESCUE_NO_PLACE_EVIDENCE", None


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("tourapi_capture")
    parser.add_argument("db_snapshot")
    args = parser.parse_args()
    capture = json.load(open(args.tourapi_capture, encoding="utf-8"))
    old = {r["contentid"] for r in map(json.loads, open(args.db_snapshot, encoding="utf-8")) if r["status"] == "ACTIVE"}
    selected = {r["contentid"]: r for c in capture["captures"][:34] for r in c["records"]}
    rescued = {r["contentid"]: r for c in capture["captures"][34:] for r in c["records"]}
    all_records = selected | rescued
    by_decision = collections.defaultdict(set)
    by_reason = collections.Counter()
    by_code = collections.defaultdict(collections.Counter)
    samples = collections.defaultdict(list)
    for content_id, record in all_records.items():
        decision, reason, role = classify(record)
        by_decision[decision].add(content_id)
        by_reason[reason] += 1
        by_code[record.get("lclsSystm3") or "UNKNOWN"][decision] += 1
        if len(samples[decision]) < 15:
            samples[decision].append({"contentId": content_id, "title": record["title"], "sourceCode": record.get("lclsSystm3"), "reason": reason, "role": role})
    result = {
        "policyVersion": POLICY_VERSION,
        "capturedAt": capture["capturedAt"],
        "candidateCount": len(all_records),
        "counts": {decision: len(ids) for decision, ids in by_decision.items()},
        "reasons": dict(by_reason),
        "oldPublicIntersection": {decision: len(ids & old) for decision, ids in by_decision.items()},
        "oldPublicOutsideCandidate": len(old - all_records.keys()),
        "newIncludeOutsideOldPublic": len(by_decision["INCLUDE"] - old),
        "bySourceCode": {code: dict(counts) for code, counts in sorted(by_code.items())},
        "samples": dict(samples),
        "decisionSetSha256": {decision: hashlib.sha256("\n".join(sorted(ids)).encode()).hexdigest() for decision, ids in by_decision.items()},
    }
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()

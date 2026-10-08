#!/usr/bin/env python3
"""Summarize one measured W10 cohort; synthetic input can never pass the gate."""

import argparse
import json
from pathlib import Path


METRICS = ("jobs", "jobsWithHits", "searchCalls", "cacheHits", "resultHits",
           "failures", "retries", "rateLimited", "timeouts", "elapsedMs", "estimatedCost")


def read_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def read_jsonl(path):
    return [json.loads(line) for line in Path(path).read_text(encoding="utf-8").splitlines() if line.strip()]


def report(manifest, database, worker_rows, judgments):
    stage = manifest.get("stage")
    if stage not in (100, 1000) or manifest.get("expectedJobs") != stage:
        raise ValueError("stage and expectedJobs must both be 100 or 1000")
    if database.get("pilotReason") != manifest.get("pilotReason"):
        raise ValueError("database evidence belongs to another cohort")
    if not manifest.get("pilotReason", "").startswith("PILOT_691_"):
        raise ValueError("pilotReason must isolate this cohort")
    if not worker_rows or any(any(key not in row for key in METRICS) for row in worker_rows):
        raise ValueError("worker metrics are missing")
    totals = {key: sum(row[key] for row in worker_rows) for key in METRICS}
    if any(value < 0 for value in totals.values()):
        raise ValueError("negative worker metric")
    completed = database["succeededJobs"] + database["failedJobs"] + database["quarantinedJobs"]
    if database["totalJobs"] > stage or completed > database["totalJobs"]:
        raise ValueError("cohort counts are inconsistent")
    if totals["jobs"] < completed:
        raise ValueError("worker metrics do not cover completed jobs")
    public_ids = set(database["publicRelationIds"])
    if len(public_ids) != database["publicRelations"]:
        raise ValueError("public relation IDs do not match count")
    seen = set()
    for row in judgments:
        relation = row.get("relationId")
        if relation not in public_ids or relation in seen or row.get("verdict") not in ("VALID", "INVALID"):
            raise ValueError("invalid or duplicate manual judgment")
        if not row.get("reviewer") or not row.get("reviewedAt"):
            raise ValueError("manual judgment needs reviewer and reviewedAt")
        seen.add(relation)
    valid = sum(row["verdict"] == "VALID" for row in judgments)
    precision = valid / len(judgments) if judgments else None
    duration = database.get("durationSeconds")
    throughput = completed * 60 / duration if isinstance(duration, (int, float)) and duration > 0 else None
    result = {
        "stage": stage, "pilotReason": manifest["pilotReason"],
        "measurementKind": "LIVE" if manifest.get("providerMode") == "http-json"
                           and manifest.get("environment") == "staging" else "FIXTURE",
        "expectedJobs": stage, "observedJobs": database["totalJobs"], "completedJobs": completed,
        "throughputPlacesPerMinute": throughput,
        "estimatedSearchCost": totals["estimatedCost"], "searchCalls": totals["searchCalls"],
        "cacheHits": totals["cacheHits"], "retryCount": totals["retries"],
        "rateLimitedCount": totals["rateLimited"], "timeoutCount": totals["timeouts"],
        "evidenceDiscoveryRate": database["jobsWithEvidence"] / completed if completed else None,
        "reviewRate": database["reviewRequiredRuns"] / database["validationRuns"] if database["validationRuns"] else None,
        "failureRate": (database["failedJobs"] + database["quarantinedJobs"]) / completed if completed else None,
        "publicRelations": database["publicRelations"], "publicPlaces": database["publicPlaces"],
        "distinctRegions": database["distinctRegions"], "distinctWorks": database["distinctWorks"],
        "manuallyJudgedRelations": len(judgments), "precision": precision,
        "manualCoverage": len(judgments) / len(public_ids) if public_ids else None,
    }
    complete = database["totalJobs"] == stage and completed == stage
    threshold = manifest.get("minimumPrecision", 0.95)
    if not isinstance(threshold, (int, float)) or not 0 <= threshold <= 1:
        raise ValueError("minimumPrecision must be between 0 and 1")
    result["gate"] = "READY_FOR_OPERATOR_REVIEW" if (result["measurementKind"] == "LIVE"
        and complete and len(judgments) == len(public_ids) and public_ids
        and precision >= threshold and database["failedJobs"] == 0
        and database["quarantinedJobs"] == 0) else "BLOCKED"
    result["blockers"] = []
    if result["measurementKind"] != "LIVE": result["blockers"].append("LIVE_PROVIDER_NOT_MEASURED")
    if not complete: result["blockers"].append("COHORT_INCOMPLETE")
    if not public_ids: result["blockers"].append("NO_PUBLIC_RELATIONS")
    if len(judgments) != len(public_ids): result["blockers"].append("MANUAL_PRECISION_INCOMPLETE")
    if precision is not None and precision < threshold: result["blockers"].append("PRECISION_BELOW_THRESHOLD")
    if database["failedJobs"] or database["quarantinedJobs"]: result["blockers"].append("FAILED_JOBS")
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True)
    parser.add_argument("--database", required=True)
    parser.add_argument("--worker-metrics", required=True)
    parser.add_argument("--judgments", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    result = report(read_json(args.manifest), read_json(args.database),
                    read_jsonl(args.worker_metrics), read_jsonl(args.judgments))
    Path(args.output).write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(result["gate"])
    return 0 if result["gate"] == "READY_FOR_OPERATOR_REVIEW" else 2


if __name__ == "__main__":
    raise SystemExit(main())

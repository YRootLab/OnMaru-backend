#!/usr/bin/env python3
"""Release evidence boundary; numerical policy belongs to pinned Toolkit v0.1.3."""
from __future__ import annotations

import argparse
import json
import math
import re
from pathlib import Path

from pipeline_toolkit.compare.module_benchmark import EvaluationTarget, compare_module_benchmarks
from pipeline_toolkit.contracts.module_evidence import (
    EnvironmentIdentity, ModuleBenchmarkEvidence, ResourceUsage, RunProvenance,
    validate_module_evidence,
)

TOOLKIT_REF = "59b3344ecdd4460451e4e67db973d6dfd4afa8b5"
IDENTITY_FIELDS = (
    "runner_image", "java_version", "python_version", "cache_state",
    "database_fixture", "cpu_memory_profile", "dependency_mode", "config_catalog_hash",
)


def require(condition, reason):
    if not condition:
        raise ValueError(reason)


def label(value):
    require(isinstance(value, str) and re.fullmatch(r"[A-Za-z0-9_. :/+\-]{1,160}", value), "invalid_label")
    return value


def number(value):
    require(type(value) in (int, float) and math.isfinite(value) and value >= 0, "invalid_metric")
    return value


def parse_record(raw, repository, commit_sha):
    require(isinstance(raw, dict), "invalid_record")
    run_id = raw.get("run_id")
    attempt = raw.get("run_attempt")
    require(isinstance(run_id, str) and re.fullmatch(r"[1-9][0-9]*", run_id), "invalid_run_id")
    require(type(attempt) is int and attempt > 0, "invalid_run_attempt")
    root = f"https://github.com/{repository}/actions/runs/{run_id}"
    require(raw.get("actions_url") == f"{root}/attempts/{attempt}", "unsafe_actions_url")
    manifest = raw.get("manifest_url")
    require(isinstance(manifest, str) and re.fullmatch(re.escape(root) + r"/artifacts/[1-9][0-9]*", manifest), "unsafe_manifest_url")
    require(raw.get("artifact_uri") == manifest, "missing_or_mismatched_artifact")
    provenance = raw.get("provenance", {})
    require(provenance.get("repository") == repository and provenance.get("commit_sha") == commit_sha, "release_identity_mismatch")
    require(provenance.get("workflow") == "Module Benchmark", "workflow_mismatch")
    require(raw.get("metric_name") == "module.wall_clock" and raw.get("unit") == "seconds", "metric_boundary_mismatch")
    require(type(raw.get("complete")) is bool, "invalid_complete")
    status = raw.get("status")
    require(status in ("success", "failed", "incomplete", "inconclusive", "cancelled"), "invalid_status")
    identity = raw.get("environment_identity", {})
    resource = raw.get("resource", {})
    item = ModuleBenchmarkEvidence(
        module_id=label(raw.get("module_id")), run_id=run_id,
        metric_name="module.wall_clock", metric_value=number(raw["metric_value"]) if raw.get("metric_value") is not None else None,
        unit="seconds", resource=ResourceUsage(**{
            key: number(resource[key]) if resource.get(key) is not None else None
            for key in ("cpu_seconds", "max_rss_bytes")
        }),
        provenance=RunProvenance(repository, commit_sha, "Module Benchmark", label(provenance.get("job"))),
        artifact_uri=manifest,
        environment_identity=EnvironmentIdentity(**{key: label(identity.get(key)) for key in IDENTITY_FIELDS}),
        status="incomplete" if status == "cancelled" else status,
        complete=raw["complete"],
    )
    validate_module_evidence(item)
    source = {"run_id": run_id, "run_attempt": attempt, "actions_url": raw["actions_url"],
              "manifest_url": manifest, "status": status, "complete": item.complete,
              "metric_value": item.metric_value}
    return item, source


def read_side(path, side, tag, commit_sha, repository, exclusions):
    evidence, sources = [], []
    try:
        require(re.fullmatch(r"v[0-9]+\.[0-9]+\.[0-9]+", tag or ""), "missing_release_tag")
        require(re.fullmatch(r"[a-f0-9]{40}", commit_sha or ""), "missing_release_sha")
        require(path.stat().st_size <= 1_000_000, "oversized_evidence")
        envelope = json.loads(path.read_text())
        require(isinstance(envelope, dict) and envelope.get("schema_version") == 1, "invalid_schema")
        require(envelope.get("release_tag") == tag and envelope.get("commit_sha") == commit_sha, "release_identity_mismatch")
        records = envelope.get("records")
        require(isinstance(records, list) and len(records) <= 3, "invalid_selected_run_count")
    except (OSError, ValueError, TypeError, AttributeError):
        exclusions.append({"side": side, "reason": "missing_or_invalid_release_evidence"})
        return evidence, sources
    for index, raw in enumerate(records):
        try:
            item, source = parse_record(raw, repository, commit_sha)
        except (ValueError, TypeError, AttributeError, KeyError):
            # No raw values or exception text: evidence can contain credentials.
            exclusions.append({"side": side, "index": index, "reason": "invalid_record"})
            continue
        evidence.append(item)
        sources.append(source)
        if not item.eligible_for_performance_sample:
            exclusions.append({"side": side, "index": index, "reason": "unsuccessful_or_incomplete"})
    return evidence, sources


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("baseline", "candidate", "output"):
        parser.add_argument(f"--{name}", type=Path, required=True)
    for name in ("baseline-tag", "baseline-sha", "candidate-tag", "candidate-sha", "repository"):
        parser.add_argument(f"--{name}", required=True)
    parser.add_argument("--github-output", type=Path)
    args = parser.parse_args()
    require(re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", args.repository), "invalid_repository")
    exclusions = []
    baseline, baseline_sources = read_side(args.baseline, "baseline", args.baseline_tag, args.baseline_sha, args.repository, exclusions)
    candidate, candidate_sources = read_side(args.candidate, "candidate", args.candidate_tag, args.candidate_sha, args.repository, exclusions)
    result = compare_module_benchmarks(baseline, candidate, target=EvaluationTarget.RELEASE).to_dict()
    # v0.1.3 returns comparable/none for a zero baseline. Undefined delta cannot authorize promotion.
    if result["classification"] == "comparable" and result["relative_delta"] is None:
        result.update(classification="inconclusive", reason="undefined_relative_delta", policy_outcome="none")
    cross_side_runs = {item.run_id for item in baseline} & {item.run_id for item in candidate}
    if cross_side_runs:
        result.update(classification="inconclusive", reason="overlapping_release_runs", policy_outcome="none")
    gate = "blocked"
    if result["classification"] == "comparable":
        if result["policy_outcome"] == "approval_hold":
            gate = "approval_hold"
        elif result["policy_outcome"] == "none":
            gate = "pass"
    result.update(schema_version=1, toolkit_ref=TOOLKIT_REF, gate=gate,
                  release_identity={"baseline_tag": args.baseline_tag, "baseline_sha": args.baseline_sha,
                                    "candidate_tag": args.candidate_tag, "candidate_sha": args.candidate_sha},
                  exclusions=exclusions, sources={"baseline": baseline_sources, "candidate": candidate_sources})
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2, allow_nan=False) + "\n")
    if args.github_output:
        with args.github_output.open("a") as output:
            output.write(f"gate={gate}\n")


if __name__ == "__main__":
    main()

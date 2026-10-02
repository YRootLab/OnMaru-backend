"""Small offline Toolkit boundary double for consumer CI fixture tests.

Local verification uses the real pinned Toolkit when available. This double keeps
the repository's hygiene job independent of a network checkout; it is not shipped
with the trusted post-run workflow.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
import hashlib
import json
import sqlite3
import sys
from types import ModuleType
from urllib.request import Request, urlopen


def _namespace(name, **members):
    module = ModuleType(name)
    module.__path__ = []
    module.__dict__.update(members)
    sys.modules[name] = module
    return module


def collect_attempt_jobs(repository, run_id, attempt, fetch_page):
    jobs, total = [], None
    for page in range(1, 5):
        response = fetch_page(f"/repos/{repository}/actions/runs/{run_id}/attempts/{attempt}/jobs?per_page=100&page={page}")
        if total is not None and response["total_count"] != total:
            raise ValueError("changed jobs total")
        total = response["total_count"]
        for job in response["jobs"]:
            if job["run_id"] != run_id or job["run_attempt"] != attempt or any(old["id"] == job["id"] for old in jobs):
                raise ValueError("job identity mismatch")
            jobs.append(job)
        if len(jobs) == total or not response["jobs"]:
            return {"total_count": total, "jobs": jobs}
    raise ValueError("jobs page bound")


def _duration(record):
    start = datetime.fromisoformat(record["started_at"].replace("Z", "+00:00"))
    end = datetime.fromisoformat(record["completed_at"].replace("Z", "+00:00"))
    return (end - start).total_seconds()


def normalize_actions_timeline(run, jobs_response, *, toolkit_ref=None, module_artifacts=None, expected_modules=None):
    jobs = []
    for raw in jobs_response["jobs"]:
        steps = [{key: step.get(key) for key in ("number", "name", "conclusion", "started_at", "completed_at")} | {
            "duration_seconds": _duration(step), "duration_quality": "available",
        } for step in raw.get("steps", [])]
        jobs.append({key: raw.get(key) for key in ("id", "name", "conclusion", "started_at", "completed_at")} | {
            "duration_seconds": _duration(raw), "duration_quality": "available", "steps": steps,
        })
    jobs.sort(key=lambda job: job["id"])
    modules, records = [], []
    for artifact in module_artifacts or []:
        raw = json.loads(artifact["data"].decode("utf-8"))
        if (raw["run_id"], raw["run_attempt"], raw["toolkit_ref"]) != (run["id"], run["run_attempt"], toolkit_ref):
            raise ValueError("module identity mismatch")
        module_id = raw["module_id"]
        if module_id not in (expected_modules or {}):
            raise ValueError("unknown module")
        modules.append({"module_id": module_id, "job_id": expected_modules[module_id],
                        "wall_clock_seconds": raw["wall_clock_seconds"], "exit_code": raw["exit_code"],
                        "complete": raw["complete"], "status": "success" if raw["exit_code"] == 0 else "failed"})
        records.append({"id": artifact["id"], "module_id": module_id, "quality": "complete"})
    result = {
        "schema_version": 1,
        "run": {"id": run["id"], "attempt": run["run_attempt"], "conclusion": run["conclusion"],
                "html_url": run["html_url"], "head_sha": run["head_sha"]},
        "identity": {"repository": run["repository"]["full_name"], "run_id": run["id"],
                     "run_attempt": run["run_attempt"], "head_sha": run["head_sha"], "toolkit_ref": toolkit_ref},
        "jobs": jobs, "modules": modules, "artifacts": records, "toolkit_ref": toolkit_ref,
        "artifact_quality": {"status": "unavailable" if module_artifacts is None else "complete" if modules else "missing", "issues": []},
        "quality": {"status": "complete", "issues": []},
        "metrics": {"observed_job_window_seconds": 8, "sum_job_work_seconds": sum(job["duration_seconds"] for job in jobs)},
    }
    encoded = json.dumps(result, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()
    result["evidence_digest"] = "sha256:" + hashlib.sha256(encoded).hexdigest()
    return result


def render_actions_diagnostics(evidence):
    return f"# Actions run diagnostics\n\nRun: {evidence['run']['id']}; conclusion: {evidence['run']['conclusion']}\n"


def redact(value):
    import re
    return re.sub(r"(?i)(token|password|secret|api[_-]?key|authorization)(\s*[:=]\s*)([^\s,;]+)",
                  lambda match: match[1] + match[2] + "[REDACTED]", value)


@dataclass
class MetricPolicy:
    workflow: str
    environment: str
    jobs: dict
    modules: tuple


@dataclass
class _Identity:
    key: str


@dataclass
class _Bundle:
    identity: _Identity
    ci_conclusion: str
    digest: str
    observed_at_ns: int


def transform_actions_evidence(evidence, policy, *, observed_at_ns):
    raw = f"{evidence['identity']['repository']}:{evidence['run']['id']}:{evidence['run']['attempt']}:{evidence['evidence_digest']}"
    return _Bundle(_Identity(hashlib.sha256(raw.encode()).hexdigest()),
                   evidence["run"]["conclusion"], evidence["evidence_digest"], observed_at_ns)


@dataclass
class ExportConfig:
    endpoint: str
    headers: dict


class SQLiteReplayStore:
    def __init__(self, path):
        self.path = path
        with sqlite3.connect(path) as db:
            db.execute("CREATE TABLE IF NOT EXISTS exports (key TEXT PRIMARY KEY)")


@dataclass
class ExportResult:
    status: str
    reason: str | None
    request_attempts: int
    acknowledged_batches: int
    total_batches: int
    ci_conclusion: str


def export_otlp(bundle, config, store):
    key = hashlib.sha256(f"{bundle.identity.key}:{bundle.observed_at_ns}:{config.endpoint}".encode()).hexdigest()
    with sqlite3.connect(store.path) as db:
        if db.execute("SELECT 1 FROM exports WHERE key=?", (key,)).fetchone():
            return ExportResult("duplicate", None, 0, 1, 1, bundle.ci_conclusion)
    request = Request(config.endpoint + "/v1/metrics", data=b"{}", headers={"Content-Type": "application/json", **config.headers})
    try:
        with urlopen(request, timeout=5) as response:
            if response.status != 200:
                return ExportResult("failed", f"http_{response.status}", 1, 0, 1, bundle.ci_conclusion)
    except Exception:
        return ExportResult("failed", "transport_error", 1, 0, 1, bundle.ci_conclusion)
    with sqlite3.connect(store.path) as db:
        db.execute("INSERT INTO exports VALUES (?)", (key,))
    return ExportResult("exported", None, 1, 1, 1, bundle.ci_conclusion)


_namespace("pipeline_toolkit.github")
_namespace("pipeline_toolkit.github.responses", collect_attempt_jobs=collect_attempt_jobs)
_namespace("pipeline_toolkit.telemetry")
_namespace("pipeline_toolkit.telemetry.timeline", normalize_actions_timeline=normalize_actions_timeline)
_namespace("pipeline_toolkit.telemetry.model", MetricPolicy=MetricPolicy)
_namespace("pipeline_toolkit.telemetry.transform", transform_actions_evidence=transform_actions_evidence)
_namespace("pipeline_toolkit.telemetry.export", ExportConfig=ExportConfig,
           SQLiteReplayStore=SQLiteReplayStore, export_otlp=export_otlp)
_namespace("pipeline_toolkit.reports")
_namespace("pipeline_toolkit.reports.diagnostics", render_actions_diagnostics=render_actions_diagnostics)
_namespace("pipeline_toolkit.security", redact=redact)

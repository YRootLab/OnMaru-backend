#!/usr/bin/env python3
"""Trusted, bounded GitHub Actions evidence collection and optional OTLP export."""

from __future__ import annotations

import argparse
from dataclasses import asdict
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
import sys
import time
from urllib.parse import urlsplit
from urllib.error import HTTPError
from urllib.request import Request, build_opener, HTTPRedirectHandler
import zipfile

from pipeline_toolkit.github.responses import collect_attempt_jobs
from pipeline_toolkit.telemetry.timeline import normalize_actions_timeline
from pipeline_toolkit.reports.diagnostics import render_actions_diagnostics
from pipeline_toolkit.telemetry.model import MetricPolicy
from pipeline_toolkit.telemetry.transform import transform_actions_evidence
from pipeline_toolkit.telemetry.export import ExportConfig, SQLiteReplayStore, export_otlp
from pipeline_toolkit.security import redact


TOOLKIT_REF = "d5b7892875000afc2deba6e6873717974d558ee5"
REPOSITORY = "YRootLab/OnMaru-backend"
WORKFLOWS = {"CI": ".github/workflows/ci.yml", "Module Benchmark": ".github/workflows/module-benchmark.yml"}
MAX_API_BYTES = 4 * 1024 * 1024
MAX_ARCHIVE_BYTES = 16 * 1024 * 1024
MAX_MEMBER_BYTES = 1024 * 1024
MAX_ARCHIVE_MEMBERS = 256
MAX_ARTIFACTS = 256
MODULE_JOB = re.compile(r"^(?:benchmark / )?module-test \(([A-Za-z0-9][A-Za-z0-9_.-]{0,127})\)$")
MODULE_ARTIFACT = re.compile(r"^module-evidence-([A-Za-z0-9][A-Za-z0-9_.-]{0,127})$")


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):
        return None


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate JSON key")
        result[key] = value
    return result


def bounded_json(raw):
    if len(raw) > MAX_API_BYTES:
        raise ValueError("API response exceeds bound")
    return json.loads(raw.decode("utf-8"), object_pairs_hook=unique_object)


def api_base(value):
    parsed = urlsplit(value)
    if parsed.scheme != "https" and not (parsed.scheme == "http" and parsed.hostname in {"127.0.0.1", "localhost", "::1"}):
        raise ValueError("unsafe API origin")
    if not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment or parsed.path not in {"", "/"}:
        raise ValueError("unsafe API origin")
    return value.rstrip("/")


def archive_location(value, api_origin):
    """Accept one short-lived GitHub artifact storage URL, never credentials."""
    if not isinstance(value, str) or len(value) > 8192 or any(ord(char) <= 32 or ord(char) == 127 for char in value) or "\\" in value:
        raise ValueError("unsafe archive location")
    parsed = urlsplit(value)
    origin = urlsplit(api_origin)
    fixture = (origin.scheme == "http" and origin.hostname in {"127.0.0.1", "localhost", "::1"}
               and parsed.scheme == "http" and parsed.netloc == origin.netloc)
    storage = (parsed.scheme == "https" and parsed.port in {None, 443}
               and (parsed.hostname == "results-receiver.actions.githubusercontent.com"
                    or (parsed.hostname or "").endswith(".blob.core.windows.net")))
    if (not (fixture or storage) or not parsed.hostname or parsed.username or parsed.password
            or parsed.fragment or not parsed.path.startswith("/") or len(parsed.query) > 4096
            or any(part in {".", ".."} for part in parsed.path.split("/"))):
        raise ValueError("unsafe archive location")
    return value


class GitHubAPI:
    def __init__(self, origin, token):
        self.origin = api_base(origin)
        if not token:
            raise ValueError("missing GitHub token")
        self.token = token
        self.opener = build_opener(NoRedirect)

    def get(self, path, limit=MAX_API_BYTES):
        if not path.startswith("/repos/YRootLab/OnMaru-backend/") or ".." in path or "//" in path:
            raise ValueError("unsafe API path")
        request = Request(self.origin + path, headers={
            "Authorization": "Bearer " + self.token,
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
        })
        with self.opener.open(request, timeout=10) as response:
            if response.geturl() != self.origin + path:
                raise ValueError("API origin changed")
            raw = response.read(limit + 1)
        if len(raw) > limit:
            raise ValueError("API response exceeds bound")
        return raw

    def json(self, path):
        return bounded_json(self.get(path))

    def archive(self, path):
        if not re.fullmatch(r"/repos/YRootLab/OnMaru-backend/actions/artifacts/[1-9][0-9]*/zip", path):
            raise ValueError("unsafe archive path")
        request = Request(self.origin + path, headers={
            "Authorization": "Bearer " + self.token,
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
        })
        try:
            with self.opener.open(request, timeout=10) as response:
                if response.geturl() != self.origin + path:
                    raise ValueError("API origin changed")
                raw = response.read(MAX_ARCHIVE_BYTES + 1)
        except HTTPError as error:
            if error.code != 302:
                raise
            location = archive_location(error.headers.get("Location"), self.origin)
            # The signed storage URL carries its own authority. Never forward the API token.
            with self.opener.open(Request(location), timeout=10) as response:
                if response.geturl() != location:
                    raise ValueError("archive origin changed")
                raw = response.read(MAX_ARCHIVE_BYTES + 1)
        if len(raw) > MAX_ARCHIVE_BYTES:
            raise ValueError("archive exceeds bound")
        return raw


def validate_run(run, repository, run_id, attempt, workflow):
    if repository != REPOSITORY or workflow not in WORKFLOWS:
        raise ValueError("unsupported repository or workflow")
    if not isinstance(run, dict) or (run.get("id"), run.get("run_attempt")) != (run_id, attempt):
        raise ValueError("run identity mismatch")
    if run.get("repository", {}).get("full_name") != repository:
        raise ValueError("repository mismatch")
    if run.get("name") != workflow or run.get("path") != WORKFLOWS[workflow]:
        raise ValueError("workflow mismatch")
    if run.get("status") != "completed" or run.get("conclusion") not in {"success", "failure", "cancelled", "timed_out", "skipped", "neutral", "action_required"}:
        raise ValueError("run is not completed")
    allowed_events = {"CI": {"push", "pull_request", "workflow_dispatch"}, "Module Benchmark": {"push", "workflow_dispatch"}}
    if run.get("event") not in allowed_events[workflow]:
        raise ValueError("unexpected source event")
    if run.get("html_url") != f"https://github.com/{repository}/actions/runs/{run_id}":
        raise ValueError("source URL mismatch")


def artifact_list(api, run_id):
    artifacts, total, seen = [], None, set()
    for page in range(1, MAX_ARTIFACTS + 2):
        response = api.json(f"/repos/{REPOSITORY}/actions/runs/{run_id}/artifacts?per_page=100&page={page}")
        count, batch = response.get("total_count"), response.get("artifacts")
        if type(count) is not int or not 0 <= count <= MAX_ARTIFACTS or not isinstance(batch, list) or len(batch) > 100:
            raise ValueError("invalid artifact pagination")
        if total is not None and total != count:
            raise ValueError("artifact total changed")
        total = count
        if len(artifacts) + len(batch) > total:
            raise ValueError("artifact count mismatch")
        for artifact in batch:
            if not isinstance(artifact, dict) or type(artifact.get("id")) is not int or artifact["id"] <= 0 or artifact["id"] in seen:
                raise ValueError("duplicate or invalid artifact id")
            seen.add(artifact["id"])
        artifacts.extend(batch)
        if len(artifacts) == total or not batch:
            if len(artifacts) != total:
                raise ValueError("partial artifact listing")
            return artifacts
    raise ValueError("artifact pagination bound")


def archive_member(archive):
    if len(archive) > MAX_ARCHIVE_BYTES:
        raise ValueError("archive exceeds bound")
    with zipfile.ZipFile(io.BytesIO(archive)) as zipped:
        members = zipped.infolist()
        if len(members) > MAX_ARCHIVE_MEMBERS:
            raise ValueError("archive member count exceeds bound")
        names, total, selected = set(), 0, None
        for member in members:
            path = PurePosixPath(member.filename)
            mode = member.external_attr >> 16
            if (member.filename in names or member.filename.startswith("/") or "\\" in member.filename
                    or any(part in {".", ".."} for part in member.filename.split("/"))
                    or len(path.parts) != 1 or not path.name or member.is_dir()
                    or (mode and not stat.S_ISREG(mode) and not (mode & 0o170000 == 0))):
                raise ValueError("unsafe archive member")
            names.add(member.filename)
            total += member.file_size
            if member.file_size > MAX_MEMBER_BYTES or total > MAX_ARCHIVE_BYTES:
                raise ValueError("archive decompression bound")
            if member.filename == "execution.json":
                selected = member
        if selected is None:
            raise ValueError("missing execution member")
        with zipped.open(selected) as stream:
            content = stream.read(MAX_MEMBER_BYTES + 1)
        if len(content) > MAX_MEMBER_BYTES:
            raise ValueError("execution member exceeds bound")
        return content


def module_evidence(api, run_id, jobs):
    expected = {}
    for job in jobs["jobs"]:
        match = MODULE_JOB.fullmatch(job.get("name") or "")
        if match:
            if match[1] in expected:
                raise ValueError("duplicate module job")
            expected[match[1]] = job["id"]
    artifacts, member_bytes = [], 0
    for artifact in artifact_list(api, run_id):
        name = artifact.get("name")
        match = MODULE_ARTIFACT.fullmatch(name) if isinstance(name, str) else None
        if not match:
            continue
        size = artifact.get("size_in_bytes")
        if type(size) is not int or not 0 < size <= MAX_ARCHIVE_BYTES or artifact.get("expired") is not False:
            raise ValueError("invalid module archive metadata")
        archive = api.archive(f"/repos/{REPOSITORY}/actions/artifacts/{artifact['id']}/zip")
        content = archive_member(archive)
        member_bytes += len(content)
        if member_bytes > MAX_ARCHIVE_BYTES:
            raise ValueError("module evidence exceeds bound")
        parsed = bounded_json(content)
        if not isinstance(parsed, dict) or parsed.get("module_id") != match[1]:
            raise ValueError("module archive identity mismatch")
        artifacts.append({"id": artifact["id"], "data": content})
    return artifacts, expected


def redacted_jobs(response):
    safe = []
    for job in response["jobs"]:
        normalized = dict(job)
        for field in ("name", "conclusion"):
            if isinstance(normalized.get(field), str):
                normalized[field] = redact(normalized[field])
        normalized["steps"] = []
        for step in job.get("steps", []):
            item = dict(step)
            for field in ("name", "conclusion"):
                if isinstance(item.get(field), str):
                    item[field] = redact(item[field])
            normalized["steps"].append(item)
        safe.append(normalized)
    return {"total_count": response["total_count"], "jobs": safe}


def write_json(path, value):
    path.write_text(json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(",", ":")) + "\n", encoding="utf-8")


def restore_replay_state(api, directory, collection):
    """Restore the last trusted diagnostic for this source identity, if one exists."""
    artifact_name = f"ci-observability-diagnostic-{collection['run_id']}-{collection['attempt']}"
    response = api.json(f"/repos/{REPOSITORY}/actions/artifacts?name={artifact_name}&per_page=100&page=1")
    count, artifacts = response.get("total_count"), response.get("artifacts")
    if type(count) is not int or not 0 <= count <= MAX_ARTIFACTS or not isinstance(artifacts, list) or len(artifacts) > 100 or len(artifacts) > count:
        raise ValueError("invalid replay listing")
    for artifact in sorted(artifacts, key=lambda item: item.get("id", 0), reverse=True):
        if not isinstance(artifact, dict) or artifact.get("name") != artifact_name or artifact.get("expired") is not False:
            continue
        artifact_id, size = artifact.get("id"), artifact.get("size_in_bytes")
        source = artifact.get("workflow_run")
        source_id = source.get("id") if isinstance(source, dict) else None
        if any(type(value) is not int or value <= 0 for value in (artifact_id, source_id, size)) or size > MAX_ARCHIVE_BYTES:
            raise ValueError("invalid replay artifact metadata")
        run = api.json(f"/repos/{REPOSITORY}/actions/runs/{source_id}")
        if (run.get("id") != source_id or run.get("name") != "CI Observability"
                or run.get("path") != ".github/workflows/ci-observability.yml"
                or run.get("repository", {}).get("full_name") != REPOSITORY or run.get("status") != "completed"):
            continue
        archive = api.archive(f"/repos/{REPOSITORY}/actions/artifacts/{artifact_id}/zip")
        with zipfile.ZipFile(io.BytesIO(archive)) as zipped:
            members = zipped.infolist()
            if len(members) > 16 or sum(item.file_size for item in members) > MAX_ARCHIVE_BYTES:
                raise ValueError("replay archive bound")
            names = set()
            for item in members:
                name = item.filename
                mode = item.external_attr >> 16
                if name in names or name.startswith("/") or "\\" in name or "/" in name or name in {".", ".."} or item.is_dir() or (mode and stat.S_IFMT(mode) not in {0, stat.S_IFREG}) or item.file_size > MAX_ARCHIVE_BYTES:
                    raise ValueError("unsafe replay archive")
                names.add(name)
            if not {"collection.json", "replay.sqlite"} <= names:
                raise ValueError("incomplete replay state")
            old = bounded_json(zipped.read("collection.json"))
            if any(old.get(key) != collection.get(key) for key in ("repository", "workflow", "run_id", "attempt", "evidence_digest", "toolkit_ref")):
                continue
            when = old.get("observed_at_ns")
            state = zipped.read("replay.sqlite")
            if type(when) is not int or when <= 0 or len(state) > MAX_ARCHIVE_BYTES or not state.startswith(b"SQLite format 3\x00"):
                raise ValueError("invalid replay state")
            (directory / "replay.sqlite").write_bytes(state)
            collection["observed_at_ns"] = when
            write_json(directory / "collection.json", collection)
            return True
    return False


def collect(args):
    destination = Path(args.out_dir)
    destination.mkdir(parents=True, exist_ok=True)
    try:
        if args.repository != REPOSITORY or args.workflow not in WORKFLOWS:
            raise ValueError("unsupported source")
        api = GitHubAPI(args.api_base_url, os.environ.get("GITHUB_TOKEN"))
        run = api.json(f"/repos/{REPOSITORY}/actions/runs/{args.run_id}/attempts/{args.attempt}")
        validate_run(run, args.repository, args.run_id, args.attempt, args.workflow)
        jobs = collect_attempt_jobs(REPOSITORY, args.run_id, args.attempt, api.json)
        if args.workflow == "Module Benchmark":
            artifacts, expected = module_evidence(api, args.run_id, jobs)
            evidence = normalize_actions_timeline(run, redacted_jobs(jobs), toolkit_ref=TOOLKIT_REF,
                                                  module_artifacts=artifacts, expected_modules=expected)
        else:
            evidence = normalize_actions_timeline(run, redacted_jobs(jobs), toolkit_ref=TOOLKIT_REF)
        write_json(destination / "evidence.json", evidence)
        write_json(destination / "collection.json", {
            "repository": REPOSITORY, "workflow": args.workflow, "run_id": args.run_id,
            "attempt": args.attempt, "evidence_digest": evidence["evidence_digest"],
            "observed_at_ns": time.time_ns(), "toolkit_ref": TOOLKIT_REF,
        })
        (destination / "diagnostics.md").write_text(render_actions_diagnostics(evidence), encoding="utf-8")
        return 0
    except Exception:
        write_json(destination / "collection-error.json", {"status": "failed", "reason": "collection_error"})
        (destination / "diagnostics.md").write_text("# Actions run diagnostics\n\nCollection failed; source conclusion was not changed.\n", encoding="utf-8")
        return 1


def export(args):
    directory = Path(args.dir)
    try:
        evidence = json.loads((directory / "evidence.json").read_text(encoding="utf-8"), object_pairs_hook=unique_object)
        collection = json.loads((directory / "collection.json").read_text(encoding="utf-8"), object_pairs_hook=unique_object)
        if collection.get("evidence_digest") != evidence.get("evidence_digest") or collection.get("run_id") != evidence["run"]["id"] or collection.get("attempt") != evidence["run"]["attempt"]:
            raise ValueError("collection identity mismatch")
        if args.restore_from_api and not (directory / "replay.sqlite").exists():
            restore_replay_state(GitHubAPI(args.restore_from_api, os.environ.get("GITHUB_TOKEN")), directory, collection)
        workflow_label = {"CI": "ci", "Module Benchmark": "module_benchmark"}.get(collection.get("workflow"))
        if (workflow_label is None or collection.get("repository") != REPOSITORY
                or collection.get("toolkit_ref") != TOOLKIT_REF):
            raise ValueError("collection workflow identity mismatch")
        policy = MetricPolicy(workflow=workflow_label, environment="test", jobs={}, modules=("catalog", "insights", "identity", "journey", "community", "audio", "operations"))
        bundle = transform_actions_evidence(evidence, policy, observed_at_ns=collection["observed_at_ns"])
        headers = json.loads(args.headers_json or "{}", object_pairs_hook=unique_object)
        if not isinstance(headers, dict):
            raise ValueError("invalid headers")
        config = ExportConfig(args.endpoint, headers=headers)
        state = directory / "replay.sqlite"
        result = export_otlp(bundle, config, SQLiteReplayStore(state))
        write_json(directory / "export-result.json", asdict(result))
        write_json(directory / "replay-state.json", {
            "identity_key": bundle.identity.key,
            "evidence_digest": evidence["evidence_digest"],
            "destination_key": hashlib.sha256(config.endpoint.rstrip("/").encode("utf-8")).hexdigest(),
            "checkpoint_file": "replay.sqlite",
            "observed_at_ns": collection["observed_at_ns"],
            "status": result.status,
        })
        with (directory / "diagnostics.md").open("a", encoding="utf-8") as stream:
            stream.write(f"\nExport status: {result.status}; reason: {result.reason or 'none'}; source conclusion: {result.ci_conclusion or 'unknown'}\n")
        return 0
    except Exception:
        conclusion = None
        try:
            conclusion = evidence["run"]["conclusion"]
        except Exception:
            pass
        write_json(directory / "export-result.json", {"status": "failed", "reason": "export_setup_error", "ci_conclusion": conclusion})
        return 1


def main():
    parser = argparse.ArgumentParser()
    commands = parser.add_subparsers(dest="command", required=True)
    collecting = commands.add_parser("collect")
    collecting.add_argument("--repository", required=True)
    collecting.add_argument("--workflow", required=True, choices=tuple(WORKFLOWS))
    collecting.add_argument("--run-id", required=True, type=int)
    collecting.add_argument("--attempt", required=True, type=int)
    collecting.add_argument("--api-base-url", default="https://api.github.com")
    collecting.add_argument("--out-dir", required=True)
    exporting = commands.add_parser("export")
    exporting.add_argument("--dir", required=True)
    exporting.add_argument("--endpoint", required=True)
    exporting.add_argument("--headers-json", default="{}")
    exporting.add_argument("--restore-from-api")
    args = parser.parse_args()
    if args.command == "collect":
        if args.run_id <= 0 or args.attempt <= 0:
            return 1
        return collect(args)
    return export(args)


if __name__ == "__main__":
    sys.exit(main())

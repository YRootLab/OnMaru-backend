#!/usr/bin/env python3
"""Run the opt-in AI pytest worker profile and persist comparable CI evidence."""

import argparse
import importlib.util
import json
import os
import subprocess
import sys
import tempfile
import time
from pathlib import Path

AI_ROOT = Path(__file__).resolve().parents[1]


def parse_arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence-dir", type=Path, required=True)
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--force-worker-unavailable", action="store_true", help=argparse.SUPPRESS)
    parser.add_argument("--force-isolation-failure", action="store_true", help=argparse.SUPPRESS)
    parser.add_argument("pytest_args", nargs=argparse.REMAINDER)
    return parser.parse_args()


def requested_workers() -> str | None:
    value = os.environ.get("ONMARU_PYTEST_WORKERS", "").strip()
    if not value:
        return None
    if value == "auto" or value.isdigit() and int(value) > 0:
        return value
    raise ValueError("ONMARU_PYTEST_WORKERS must be 'auto' or a positive integer")


def worker_isolation_is_healthy(workers: str) -> bool:
    with tempfile.TemporaryDirectory(prefix="onmaru-pytest-xdist-") as temporary_directory:
        probe = Path(temporary_directory) / "test_xdist_isolation.py"
        probe.write_text(
            "import pytest\n\n"
            "@pytest.mark.parametrize('case', range(2))\n"
            "def test_worker_isolation(worker_id, case):\n"
            "    assert worker_id.startswith('gw')\n",
            encoding="utf-8",
        )
        result = subprocess.run(
            [sys.executable, "-m", "pytest", "-q", "-n", workers, str(probe)],
            cwd=AI_ROOT,
            check=False,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
        )
    return result.returncode == 0


def select_profile(arguments: argparse.Namespace, workers: str | None) -> tuple[str, str | None]:
    if workers is None:
        return "serial", "workers-not-requested"
    if arguments.force_worker_unavailable or importlib.util.find_spec("xdist") is None:
        return "serial", "pytest-xdist-unavailable"
    if arguments.force_isolation_failure or not worker_isolation_is_healthy(workers):
        return "serial", "isolation-check-failed"
    return "workers", None


def main() -> int:
    arguments = parse_arguments()
    try:
        workers = requested_workers()
    except ValueError as error:
        print(str(error), file=sys.stderr)
        return 2

    started = time.perf_counter()
    profile, fallback_reason = select_profile(arguments, workers)
    evidence_directory = arguments.evidence_dir.resolve()
    evidence_directory.mkdir(parents=True, exist_ok=True)
    junit_xml = evidence_directory / "pytest-junit.xml"
    pytest_args = arguments.pytest_args
    if pytest_args[:1] == ["--"]:
        pytest_args = pytest_args[1:]
    command = [sys.executable, "-m", "pytest", f"--junitxml={junit_xml}"]
    if profile == "workers":
        command.extend(["-n", workers or "auto"])
    command.extend(pytest_args)

    exit_code = 0
    if not arguments.dry_run:
        exit_code = subprocess.run(command, cwd=AI_ROOT, check=False).returncode

    evidence = {
        "selected_profile": profile,
        "requested_workers": workers,
        "fallback_reason": fallback_reason,
        "junit_xml": str(junit_xml),
        "duration_ms": round((time.perf_counter() - started) * 1000, 3),
        "exit_code": exit_code,
        "command": command,
    }
    (evidence_directory / "pytest-duration.json").write_text(
        json.dumps(evidence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(json.dumps(evidence, ensure_ascii=False))
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())

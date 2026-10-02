#!/usr/bin/env python3
"""Thin, dry-run-first adapter for the pinned pipeline-toolkit experiment CLI."""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Any


TOOLKIT_REF = "9c6f0033a5ec2429085b29d56ebdb3caca94bbcd"
MAX_STDOUT_BYTES = 64 * 1024
SECRET_KEY = re.compile(r"(?:authorization|credential|password|secret|token)", re.IGNORECASE)
SECRET_TEXT = (
    (re.compile(r"(?i)(authorization\s*[:=]\s*bearer\s+)\S+"), r"\1[REDACTED]"),
    (re.compile(r"\bgh[opsu]_[A-Za-z0-9_]{8,}\b"), "[REDACTED]"),
)


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description=__doc__)
    result.add_argument(
        "action",
        nargs="?",
        default="dry-run",
        choices=("dry-run", "dispatch", "wait", "compare"),
    )
    result.add_argument("--repo-root", type=Path)
    result.add_argument("--scope", choices=("ci", "test"), default="ci")
    result.add_argument("--reason", default="OnMaru pipeline experiment")
    result.add_argument("--authorize-dispatch", action="store_true")
    result.add_argument("--receipt", type=Path)
    result.add_argument("--timeout", type=float, default=1800.0)
    result.add_argument("--poll-interval", type=float, default=5.0)
    result.add_argument("--input", type=Path)
    result.add_argument("--format", choices=("json", "markdown"), default="json")
    return result


def repository_root() -> Path:
    return Path(__file__).resolve().parents[3]


def fail(code: str, explanation: str, **details: Any) -> int:
    payload: dict[str, Any] = {
        "error": {"code": code, "explanation_ko": explanation},
    }
    if details:
        payload["error"].update(details)
    print(json.dumps(payload, ensure_ascii=False, sort_keys=True))
    return 2


def toolkit_argv(args: argparse.Namespace) -> list[str]:
    command = ["experiment", args.action]
    if args.action in ("dry-run", "dispatch"):
        root = (args.repo_root or repository_root()).resolve()
        command.extend(
            ["--repo-root", str(root), "--scope", args.scope, "--reason", args.reason]
        )
    elif args.action == "wait":
        if args.receipt is None:
            raise ValueError("wait_receipt_required")
        command.extend(
            [
                "--receipt",
                str(args.receipt),
                "--timeout",
                str(args.timeout),
                "--poll-interval",
                str(args.poll_interval),
            ]
        )
    else:
        if args.input is None:
            raise ValueError("compare_input_required")
        command.extend(["--input", str(args.input), "--format", args.format])
    return command


def sanitize_json(value: Any, key: str = "") -> Any:
    if SECRET_KEY.search(key):
        return "[REDACTED]"
    if isinstance(value, dict):
        return {item_key: sanitize_json(item_value, str(item_key)) for item_key, item_value in value.items()}
    if isinstance(value, list):
        return [sanitize_json(item) for item in value]
    if isinstance(value, str):
        return sanitize_text(value)
    return value


def sanitize_text(value: str) -> str:
    result = value
    for pattern, replacement in SECRET_TEXT:
        result = pattern.sub(replacement, result)
    return result


def render_bounded(raw: bytes) -> bytes:
    if len(raw) > MAX_STDOUT_BYTES:
        raise OverflowError("toolkit_output_too_large")
    text = raw.decode("utf-8", errors="replace")
    try:
        parsed = json.loads(text)
    except json.JSONDecodeError:
        rendered = sanitize_text(text)
    else:
        rendered = json.dumps(sanitize_json(parsed), ensure_ascii=False, sort_keys=True) + "\n"
    encoded = rendered.encode("utf-8")
    if len(encoded) > MAX_STDOUT_BYTES:
        raise OverflowError("toolkit_output_too_large")
    return encoded


def invoke(binary: str, argv: list[str]) -> tuple[int, bytes]:
    resolved = shutil.which(binary) if os.sep not in binary else binary
    if not resolved:
        raise FileNotFoundError(binary)
    with tempfile.TemporaryFile() as stdout_file:
        process = subprocess.run(
            [resolved, *argv],
            stdin=subprocess.DEVNULL,
            stdout=stdout_file,
            stderr=subprocess.DEVNULL,
            check=False,
            shell=False,
        )
        stdout_file.seek(0)
        raw = stdout_file.read(MAX_STDOUT_BYTES + 1)
    return process.returncode, render_bounded(raw)


def main(argv: list[str] | None = None) -> int:
    args = parser().parse_args(argv)
    configured_ref = os.environ.get("ONMARU_PIPELINE_TOOLKIT_REF", TOOLKIT_REF)
    if configured_ref != TOOLKIT_REF:
        return fail(
            "toolkit_ref_mismatch",
            "설치된 Toolkit을 저장소의 고정 커밋과 맞춘 뒤 다시 시도하세요.",
            expected_ref=TOOLKIT_REF,
        )
    if args.action == "dispatch" and not args.authorize_dispatch:
        return fail(
            "dispatch_authorization_required",
            "명시적인 현재 대화의 dispatch 요청을 확인한 호출에서만 --authorize-dispatch를 전달하세요.",
        )
    try:
        command = toolkit_argv(args)
        returncode, output = invoke(
            os.environ.get("ONMARU_PIPELINE_TOOLKIT_BIN", "pipeline-toolkit"), command
        )
    except ValueError as error:
        return fail(str(error), "작업에 필요한 입력 파일을 명시하세요.")
    except FileNotFoundError:
        return fail(
            "toolkit_not_installed",
            "고정 커밋의 pipeline-toolkit 실행 파일을 설치하거나 ONMARU_PIPELINE_TOOLKIT_BIN으로 지정하세요.",
            expected_ref=TOOLKIT_REF,
        )
    except (OSError, OverflowError) as error:
        code = str(error) if isinstance(error, OverflowError) else "toolkit_invocation_failed"
        return fail(code, "Toolkit 출력 상한 또는 로컬 실행 환경을 확인하세요.")

    if returncode not in (0, 2):
        return fail(
            "unexpected_toolkit_exit",
            "Toolkit이 안정 계약 밖의 종료 코드를 반환했습니다. 원본 evidence를 확인하고 dispatch를 반복하지 마세요.",
            toolkit_exit=returncode,
        )
    sys.stdout.buffer.write(output)
    return returncode


if __name__ == "__main__":
    raise SystemExit(main())

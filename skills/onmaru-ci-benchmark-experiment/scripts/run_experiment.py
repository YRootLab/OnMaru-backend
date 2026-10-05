#!/usr/bin/env python3
"""Thin, dry-run-first adapter for the pinned pipeline-toolkit experiment CLI."""

from __future__ import annotations

import argparse
import json
import math
import os
import re
import selectors
import shutil
import signal
import subprocess
import sys
import time
from pathlib import Path
from typing import Any


TOOLKIT_REF = "7ecbb89aae771604d9c1c532cf123f239e279110"
TOOLKIT_REPOSITORY = "https://github.com/YRootLab/OnMaru-backend-ci-toolkit"
MAX_STDOUT_BYTES = 64 * 1024
SECRET_KEY = re.compile(r"(?:api[_-]?key|authorization|credential|password|secret|token)", re.IGNORECASE)
SECRET_TEXT = (
    (re.compile(r"(?i)\b(authorization[ \t]*[:=][ \t]*)(?:basic|bearer)[ \t]+[^\s'\"&,;]+"), r"\1[REDACTED]"),
    (re.compile(r"\bgh[opsu]_[A-Za-z0-9_]{8,}\b"), "[REDACTED]"),
    (re.compile(r"\bgithub_pat_[A-Za-z0-9_]{8,}\b"), "github_pat_[REDACTED]"),
    (
        re.compile(
            r"(?i)\b(authorization|api[_-]?key|access[_-]?token|refresh[_-]?token|client[_-]?secret|credential|password|secret|token)"
            r"(\s*[:=]\s*)(?P<quote>['\"])[^\r\n]{0,4096}?(?P=quote)"
        ),
        r"\1\2\g<quote>[REDACTED]\g<quote>",
    ),
    (
        re.compile(
            r"(?i)\b(authorization|api[_-]?key|access[_-]?token|refresh[_-]?token|client[_-]?secret|credential|password|secret|token)"
            r"(\s*[:=]\s*)(?!['\"])[^\s&,;]+"
        ),
        r"\1\2[REDACTED]",
    ),
)


class InvocationAborted(Exception):
    def __init__(self, reason: str):
        super().__init__(reason)
        self.reason = reason


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
    result.add_argument("--bootstrap-integration", action="store_true")
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
        if args.action == "dispatch" and args.bootstrap_integration:
            command.append("--bootstrap-integration")
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


def resolve_binary(binary: str) -> Path:
    resolved = shutil.which(binary) if os.sep not in binary else binary
    if not resolved:
        raise FileNotFoundError(binary)
    return Path(resolved).resolve()


def normalized_repository(value: object) -> str:
    if not isinstance(value, str):
        return ""
    return value.rstrip("/").removesuffix(".git")


def sanitized_child_environment() -> dict[str, str]:
    environment = dict(os.environ)
    environment.pop("PYTHONPATH", None)
    environment.pop("PYTHONHOME", None)
    return environment


def installed_direct_url(binary: Path) -> dict[str, Any]:
    with binary.open("rb") as stream:
        first_line = stream.readline(512).decode("utf-8", errors="strict").strip()
        entrypoint = stream.read(4097).decode("utf-8", errors="strict")
    if not first_line.startswith("#!"):
        raise ValueError("missing_console_script_shebang")
    interpreter_path = Path(first_line[2:])
    if (
        not interpreter_path.is_absolute()
        or binary.name != "pipeline-toolkit"
        or len(entrypoint.encode("utf-8")) > 4096
        or "from pipeline_toolkit.cli import main" not in entrypoint
    ):
        raise ValueError("unbound_console_script")
    probe = subprocess.run(
        [
            str(interpreter_path),
            "-I",
            "-c",
            (
                "import importlib.metadata as m, importlib.util as u, json, pathlib; "
                "d=m.distribution('onmaru-pipeline-toolkit'); "
                "r=pathlib.Path(d.locate_file('')).resolve(); f=pathlib.Path(u.find_spec('pipeline_toolkit').origin).resolve(); "
                "rel=f.relative_to(r); "
                "print(json.dumps({'direct_url':json.loads(d.read_text('direct_url.json') or '{}'),"
                "'distribution_root':str(r),'module_file':str(f),'relative_module':rel.as_posix(),"
                "'module_owned':rel.as_posix() in {str(x) for x in (d.files or [])}}))"
            ),
        ],
        stdin=subprocess.DEVNULL,
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
        env=sanitized_child_environment(),
        timeout=10,
        check=False,
    )
    if probe.returncode != 0 or len(probe.stdout) > 4096:
        raise ValueError("direct_url_unavailable")
    identity = json.loads(probe.stdout)
    if not isinstance(identity, dict):
        raise ValueError("invalid_direct_url")
    distribution_root = Path(identity.get("distribution_root", ""))
    module_file = Path(identity.get("module_file", ""))
    relative_identity = identity.get("relative_module")
    try:
        relative_module = module_file.relative_to(distribution_root)
    except ValueError as error:
        raise ValueError("module_outside_distribution") from error
    if (
        not relative_module.parts
        or relative_module.parts[0] != "pipeline_toolkit"
        or relative_identity != relative_module.as_posix()
        or identity.get("module_owned") is not True
    ):
        raise ValueError("module_distribution_mismatch")
    direct_url = identity.get("direct_url")
    if not isinstance(direct_url, dict):
        raise ValueError("invalid_direct_url")
    return direct_url


def verify_installation(binary: Path) -> None:
    direct_url = installed_direct_url(binary)
    vcs = direct_url.get("vcs_info")
    if not (
        normalized_repository(direct_url.get("url")) == TOOLKIT_REPOSITORY
        and isinstance(vcs, dict)
        and vcs.get("vcs") == "git"
        and vcs.get("commit_id") == TOOLKIT_REF
    ):
        raise ValueError("unpinned_toolkit_install")


def stop_process(process: subprocess.Popen[bytes]) -> None:
    try:
        os.killpg(process.pid, signal.SIGTERM)
    except ProcessLookupError:
        pass
    try:
        process.wait(timeout=0.1)
    except subprocess.TimeoutExpired:
        pass
    try:
        os.killpg(process.pid, signal.SIGKILL)
    except ProcessLookupError:
        pass
    try:
        process.wait(timeout=0.5)
    except subprocess.TimeoutExpired:
        pass


def invoke(binary: Path, argv: list[str], timeout: float) -> tuple[int, bytes]:
    process = subprocess.Popen(
        [str(binary), *argv],
        stdin=subprocess.DEVNULL,
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
        env=sanitized_child_environment(),
        shell=False,
        start_new_session=True,
    )
    if process.stdout is None:
        stop_process(process)
        raise InvocationAborted("stdout_unavailable")
    chunks: list[bytes] = []
    size = 0
    deadline = time.monotonic() + timeout
    selector = selectors.DefaultSelector()
    selector.register(process.stdout, selectors.EVENT_READ)
    try:
        while selector.get_map():
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise InvocationAborted("timeout")
            for key, _ in selector.select(min(0.1, remaining)):
                chunk = os.read(key.fd, 16 * 1024)
                if not chunk:
                    selector.unregister(key.fileobj)
                    continue
                size += len(chunk)
                if size > MAX_STDOUT_BYTES:
                    raise InvocationAborted("output_too_large")
                chunks.append(chunk)
        try:
            returncode = process.wait(timeout=0.5)
        except subprocess.TimeoutExpired as error:
            raise InvocationAborted("timeout") from error
    except InvocationAborted:
        stop_process(process)
        raise
    finally:
        selector.close()
        process.stdout.close()
    return returncode, render_bounded(b"".join(chunks))


def operation_timeout(args: argparse.Namespace) -> float:
    if args.action == "wait" and (not math.isfinite(args.timeout) or args.timeout <= 0):
        raise ValueError("invalid_timeout_limit")
    default = min(args.timeout + 30.0, 3630.0) if args.action == "wait" else 60.0
    configured = os.environ.get("ONMARU_PIPELINE_TOOLKIT_TIMEOUT_SECONDS")
    if configured is None:
        return default
    value = float(configured)
    if not math.isfinite(value) or value < 0.05:
        raise ValueError("invalid_timeout_limit")
    return min(default, value)


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
        binary = resolve_binary(os.environ.get("ONMARU_PIPELINE_TOOLKIT_BIN", "pipeline-toolkit"))
        verify_installation(binary)
        returncode, output = invoke(binary, command, operation_timeout(args))
    except InvocationAborted as error:
        if args.action == "dispatch":
            return fail(
                "dispatch_response_lost",
                "dispatch 상태가 모호합니다. 이미 실행됐을 수 있으므로 절대 다시 dispatch하지 마세요. GitHub Actions에서 수동 확인하세요.",
                reason=error.reason,
            )
        return fail(
            f"toolkit_{error.reason}",
            "Toolkit 작업이 시간 또는 출력 상한에서 중단됐습니다. 입력과 consumer-local evidence를 확인하세요.",
        )
    except ValueError as error:
        if str(error) in ("wait_receipt_required", "compare_input_required"):
            return fail(str(error), "작업에 필요한 입력 파일을 명시하세요.")
        if str(error) == "invalid_timeout_limit":
            return fail(str(error), "실행 timeout 상한은 0.05초 이상의 유한한 값이어야 합니다.")
        return fail(
            "toolkit_install_unverified",
            "실행 파일의 설치 metadata가 고정 Toolkit repository와 commit을 증명하지 못했습니다.",
            expected_ref=TOOLKIT_REF,
        )
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

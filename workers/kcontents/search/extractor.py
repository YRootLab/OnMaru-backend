"""Explicit local JSON extractor command with bounded I/O and no shell."""

from __future__ import annotations

import json
import os
import selectors
import subprocess
import time
from typing import Any


class ExtractorFailure(Exception):
    def __init__(self, code: str):
        super().__init__(code)
        self.code = code


class CommandExtractor:
    def __init__(self, argv: list[str], timeout_seconds: float = 60,
                 max_input_bytes: int = 64_000, max_output_bytes: int = 64_000):
        if (not argv or not all(isinstance(value, str) and value for value in argv)
                or not os.path.isabs(argv[0]) or timeout_seconds <= 0 or timeout_seconds > 600
                or max_input_bytes < 1 or max_output_bytes < 1):
            raise ValueError("Invalid extractor command or budget")
        self.argv = list(argv)
        self.timeout_seconds = timeout_seconds
        self.max_input_bytes = max_input_bytes
        self.max_output_bytes = max_output_bytes

    def extract(self, bundle: dict[str, Any]) -> dict[str, Any]:
        encoded = json.dumps(bundle, ensure_ascii=False).encode("utf-8")
        if len(encoded) > self.max_input_bytes:
            raise ExtractorFailure("CLI_FAILURE")
        # Never pass the worker bearer, search provider key or arbitrary process env.
        env = {name: os.environ[name] for name in ("PATH", "HOME", "LANG", "XDG_CONFIG_HOME")
               if name in os.environ}
        env["NO_COLOR"] = "1"
        try:
            process = subprocess.Popen(self.argv, shell=False, stdin=subprocess.PIPE,
                                       stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                       close_fds=True, env=env)
        except OSError:
            raise ExtractorFailure("CLI_FAILURE") from None
        selector = selectors.DefaultSelector()
        output = bytearray()
        deadline = time.monotonic() + self.timeout_seconds
        position = 0
        try:
            assert process.stdin is not None and process.stdout is not None
            os.set_blocking(process.stdin.fileno(), False)
            os.set_blocking(process.stdout.fileno(), False)
            selector.register(process.stdin, selectors.EVENT_WRITE, "input")
            selector.register(process.stdout, selectors.EVENT_READ, "output")
            while selector.get_map():
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise ExtractorFailure("TIMEOUT")
                for key, _ in selector.select(remaining):
                    if key.data == "input":
                        try:
                            position += os.write(key.fileobj.fileno(), encoded[position:position + 4096])
                        except BrokenPipeError:
                            position = len(encoded)
                        if position >= len(encoded):
                            selector.unregister(key.fileobj)
                            key.fileobj.close()
                    else:
                        chunk = os.read(key.fileobj.fileno(), 4096)
                        if not chunk:
                            selector.unregister(key.fileobj)
                            key.fileobj.close()
                        else:
                            output.extend(chunk)
                            if len(output) > self.max_output_bytes:
                                raise ExtractorFailure("INVALID_JSON")
            process.wait(timeout=max(0.01, deadline - time.monotonic()))
            if process.returncode != 0:
                raise ExtractorFailure("CLI_FAILURE")
            value = json.loads(output.decode("utf-8"))
            if not isinstance(value, dict):
                raise ExtractorFailure("INVALID_JSON")
            return value
        except subprocess.TimeoutExpired:
            raise ExtractorFailure("TIMEOUT") from None
        except (UnicodeDecodeError, ValueError):
            raise ExtractorFailure("INVALID_JSON") from None
        finally:
            selector.close()
            if process.poll() is None:
                process.kill()
            process.wait()
            if process.stdin and not process.stdin.closed:
                process.stdin.close()
            if process.stdout and not process.stdout.closed:
                process.stdout.close()

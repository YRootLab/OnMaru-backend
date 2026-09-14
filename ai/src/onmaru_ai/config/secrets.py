from __future__ import annotations

import logging
import os
from collections.abc import Mapping
from dataclasses import dataclass
from typing import Protocol

REQUIRED_SECRET_NAMES = (
    "tourapi.service-key",
    "odii.service-key",
    "gemini.api-key",
    "oauth.client-secret",
    "otlp.exporter-token",
)


@dataclass(frozen=True)
class SecretBundle:
    name: str
    current: str
    previous: str | None = None

    def __post_init__(self) -> None:
        if not self.name.strip():
            raise ValueError("secret name must not be blank")
        if not self.current.strip():
            raise ValueError("current secret must not be blank")
        if self.previous is not None and not self.previous.strip():
            object.__setattr__(self, "previous", None)

    def matches(self, value: str) -> bool:
        return value == self.current or value == self.previous


class SecretProvider(Protocol):
    def get(self, name: str) -> SecretBundle:
        """Return the current and optional previous value for a named secret."""


class FakeSecretProvider:
    def get(self, name: str) -> SecretBundle:
        normalized = name.replace(".", "-")
        return SecretBundle(
            name=name,
            current=f"fake-{normalized}-current",
            previous=f"fake-{normalized}-previous",
        )


class EnvironmentSecretProvider:
    def __init__(self, environ: Mapping[str, str] | None = None) -> None:
        self._environ = os.environ if environ is None else environ

    def get(self, name: str) -> SecretBundle:
        current_key = _env_key(name, "CURRENT")
        current = self._environ.get(current_key)
        if current is None or not current.strip():
            raise RuntimeError(f"missing required secret: {name} (expected env {current_key})")
        return SecretBundle(
            name=name,
            current=current,
            previous=self._environ.get(_env_key(name, "PREVIOUS")),
        )


class SecretRedactor:
    def __init__(self, secrets: list[SecretBundle] | tuple[SecretBundle, ...]) -> None:
        values: set[str] = set()
        for secret in secrets:
            values.add(secret.current)
            if secret.previous is not None:
                values.add(secret.previous)
        self._values = sorted(values, key=len, reverse=True)

    def redact(self, message: str) -> str:
        redacted = message
        for value in self._values:
            redacted = redacted.replace(value, "<REDACTED>")
        return redacted


class RedactingLogFilter(logging.Filter):
    def __init__(self, redactor: SecretRedactor) -> None:
        super().__init__()
        self._redactor = redactor

    def filter(self, record: logging.LogRecord) -> bool:
        record.msg = self._redactor.redact(record.getMessage())
        record.args = ()
        return True


def install_logging_redaction(
    redactor: SecretRedactor,
    logger: logging.Logger | None = None,
) -> None:
    target = logging.getLogger() if logger is None else logger
    redacting_filter = RedactingLogFilter(redactor)
    target.addFilter(redacting_filter)
    for handler in target.handlers:
        handler.addFilter(redacting_filter)


def provider_from_environment() -> SecretProvider:
    source = os.environ.get("ONMARU_SECRETS_SOURCE", "environment").lower()
    if source == "environment":
        return EnvironmentSecretProvider()
    if source == "fake":
        return FakeSecretProvider()
    raise RuntimeError("ONMARU_SECRETS_SOURCE must be fake or environment")


def validate_required_secrets(
    provider: SecretProvider,
    required_names: tuple[str, ...] | list[str] = REQUIRED_SECRET_NAMES,
) -> list[SecretBundle]:
    return [provider.get(name) for name in required_names]


def _env_key(name: str, suffix: str) -> str:
    normalized = name.upper().replace(".", "_").replace("-", "_")
    return f"ONMARU_SECRET_{normalized}_{suffix}"

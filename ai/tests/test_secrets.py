from __future__ import annotations

import asyncio
import logging
from io import StringIO

import pytest
from httpx import ASGITransport, AsyncClient

from onmaru_ai.config.secrets import (
    EnvironmentSecretProvider,
    FakeSecretProvider,
    SecretBundle,
    SecretRedactor,
    install_logging_redaction,
    validate_required_secrets,
)
from onmaru_ai.main import create_app


def test_environment_provider_fails_startup_when_required_secret_is_missing(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.delenv("ONMARU_SECRET_TOURAPI_SERVICE_KEY_CURRENT", raising=False)

    provider = EnvironmentSecretProvider(environ={})

    with pytest.raises(RuntimeError, match="missing required secret"):
        validate_required_secrets(provider, ["tourapi.service-key"])


def test_fake_provider_supplies_current_and_previous_values_for_rotation_overlap() -> None:
    provider = FakeSecretProvider()

    bundle = provider.get("tourapi.service-key")

    assert bundle.current == "fake-tourapi-service-key-current"
    assert bundle.previous == "fake-tourapi-service-key-previous"
    assert bundle.matches("fake-tourapi-service-key-current")
    assert bundle.matches("fake-tourapi-service-key-previous")
    assert not bundle.matches("other-value")


def test_redactor_removes_current_and_previous_secret_values_from_logs() -> None:
    redactor = SecretRedactor(
        [
            SecretBundle("gemini.api-key", "gemini-current-secret", "gemini-previous-secret"),
            SecretBundle("oauth.client-secret", "oauth-current-secret", None),
        ]
    )

    redacted = redactor.redact(
        "current=gemini-current-secret previous=gemini-previous-secret "
        "oauth=oauth-current-secret"
    )

    assert redacted == "current=<REDACTED> previous=<REDACTED> oauth=<REDACTED>"


def test_logging_filter_redacts_current_and_previous_secret_values() -> None:
    stream = StringIO()
    handler = logging.StreamHandler(stream)
    logger = logging.getLogger("onmaru-ai-secret-test")
    logger.handlers = [handler]
    logger.propagate = False
    logger.setLevel(logging.INFO)

    install_logging_redaction(
        SecretRedactor(
            [SecretBundle("gemini.api-key", "gemini-current-secret", "gemini-previous-secret")]
        ),
        logger=logger,
    )

    logger.info("current=%s previous=%s", "gemini-current-secret", "gemini-previous-secret")

    assert stream.getvalue().strip() == "current=<REDACTED> previous=<REDACTED>"


async def _request_ready() -> tuple[int, dict[str, str]]:
    transport = ASGITransport(app=create_app(secret_provider=FakeSecretProvider()))
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.get("/ready")

    return response.status_code, response.json()


def test_create_app_accepts_fake_secret_provider() -> None:
    status_code, body = asyncio.run(_request_ready())

    assert status_code == 200
    assert body == {"status": "ready"}

from fastapi import FastAPI

from onmaru_ai.config.secrets import (
    SecretProvider,
    SecretRedactor,
    install_logging_redaction,
    provider_from_environment,
    validate_required_secrets,
)
from onmaru_ai.observability import TelemetrySink, create_telemetry_sink, install_observability
from onmaru_ai.security import install_internal_auth


def create_app(
    secret_provider: SecretProvider | None = None,
    telemetry_sink: TelemetrySink | None = None,
) -> FastAPI:
    provider = secret_provider or provider_from_environment()
    secrets = validate_required_secrets(provider)
    install_logging_redaction(SecretRedactor(secrets))

    app = FastAPI(title="OnMaru AI")
    install_observability(app, telemetry_sink or create_telemetry_sink())
    install_internal_auth(app, provider)

    @app.get("/health")
    async def health() -> dict[str, str]:
        return {"status": "ok"}

    @app.get("/ready")
    async def readiness() -> dict[str, str]:
        return {"status": "ready"}

    return app


app = create_app()

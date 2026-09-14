from fastapi import FastAPI

from onmaru_ai.config.secrets import (
    SecretProvider,
    SecretRedactor,
    install_logging_redaction,
    provider_from_environment,
    validate_required_secrets,
)
from onmaru_ai.observability import TelemetrySink, create_telemetry_sink, install_observability


def create_app(
    secret_provider: SecretProvider | None = None,
    telemetry_sink: TelemetrySink | None = None,
) -> FastAPI:
    secrets = validate_required_secrets(secret_provider or provider_from_environment())
    install_logging_redaction(SecretRedactor(secrets))

    app = FastAPI(title="OnMaru AI")
    install_observability(app, telemetry_sink or create_telemetry_sink())

    @app.get("/health")
    async def health() -> dict[str, str]:
        return {"status": "ok"}

    @app.get("/ready")
    async def readiness() -> dict[str, str]:
        return {"status": "ready"}

    return app


app = create_app()

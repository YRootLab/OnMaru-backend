import os
from collections.abc import Mapping

from fastapi import FastAPI

from onmaru_ai.config.secrets import (
    SecretProvider,
    SecretRedactor,
    install_logging_redaction,
    provider_from_environment,
    validate_required_secrets,
)
from onmaru_ai.observability import TelemetrySink, create_telemetry_sink, install_observability
from onmaru_ai.rag import InMemoryRagActivationLog
from onmaru_ai.security import install_internal_auth
from onmaru_ai.security.internal_auth import RagEvidenceRetriever


def _rag_feature_enabled(environ: Mapping[str, str] | None = None) -> bool:
    source = os.environ if environ is None else environ
    return source.get("ONMARU_RAG_ENABLED", "").strip().lower() == "true"


def create_app(
    secret_provider: SecretProvider | None = None,
    telemetry_sink: TelemetrySink | None = None,
    rag_activation_log: InMemoryRagActivationLog | None = None,
    rag_retriever: RagEvidenceRetriever | None = None,
    environ: Mapping[str, str] | None = None,
) -> FastAPI:
    provider = secret_provider or provider_from_environment()
    secrets = validate_required_secrets(provider)
    install_logging_redaction(SecretRedactor(secrets))

    app = FastAPI(title="OnMaru AI")
    install_observability(app, telemetry_sink or create_telemetry_sink())
    install_internal_auth(
        app,
        provider,
        rag_activation_log=rag_activation_log,
        rag_retriever=rag_retriever,
        rag_feature_enabled=_rag_feature_enabled(environ),
    )

    @app.get("/health")
    async def health() -> dict[str, str]:
        return {"status": "ok"}

    @app.get("/ready")
    async def readiness() -> dict[str, str]:
        return {"status": "ready"}

    return app


app = create_app()

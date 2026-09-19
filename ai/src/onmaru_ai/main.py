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
from onmaru_ai.providers.gemini.adapter import GeminiAdapter
from onmaru_ai.providers.gemini.models import GeminiConfig, GeminiPricing
from onmaru_ai.providers.gemini.transport import HttpxGeminiTransport
from onmaru_ai.rag import InMemoryRagActivationLog
from onmaru_ai.screenhanok.routes import install_screen_hanok_research
from onmaru_ai.screenhanok.service import ScreenHanokResearchService
from onmaru_ai.security import install_internal_auth
from onmaru_ai.security.internal_auth import RagEvidenceRetriever


def _rag_feature_enabled(environ: Mapping[str, str] | None = None) -> bool:
    source = os.environ if environ is None else environ
    return source.get("ONMARU_RAG_ENABLED", "").strip().lower() == "true"


def _screen_hanok_research_enabled(environ: Mapping[str, str] | None = None) -> bool:
    # ADR-0010 approves fully automated, unreviewed publishing once this is on -- keep it off
    # until a real Gemini API key and cost budget are configured for this environment.
    source = os.environ if environ is None else environ
    return source.get("ONMARU_SCREEN_HANOK_RESEARCH_ENABLED", "").strip().lower() == "true"


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

    if _screen_hanok_research_enabled(environ):
        gemini_secret = provider.get("gemini.api-key")
        gemini_config = GeminiConfig(
            model_alias="screen-hanok-research",
            model_name=(os.environ if environ is None else environ).get(
                "ONMARU_GEMINI_MODEL_NAME", "gemini-2.5-flash"
            ),
            max_output_tokens=4096,
            pricing=GeminiPricing(input_micros_per_million=0, output_micros_per_million=0),
        )
        gemini_adapter = GeminiAdapter(
            gemini_config,
            HttpxGeminiTransport(),
            api_key=gemini_secret.current,
            telemetry_sink=telemetry_sink or create_telemetry_sink(),
        )
        install_screen_hanok_research(app, provider, ScreenHanokResearchService(gemini_adapter))

    @app.get("/health")
    async def health() -> dict[str, str]:
        return {"status": "ok"}

    @app.get("/ready")
    async def readiness() -> dict[str, str]:
        return {"status": "ready"}

    return app


app = create_app()

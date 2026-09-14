from fastapi import FastAPI

from onmaru_ai.observability import TelemetrySink, create_telemetry_sink, install_observability


def create_app(telemetry_sink: TelemetrySink | None = None) -> FastAPI:
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

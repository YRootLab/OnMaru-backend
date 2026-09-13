from fastapi import FastAPI


def create_app() -> FastAPI:
    app = FastAPI(title="OnMaru AI")

    @app.get("/health")
    async def health() -> dict[str, str]:
        return {"status": "ok"}

    @app.get("/ready")
    async def readiness() -> dict[str, str]:
        return {"status": "ready"}

    return app


app = create_app()

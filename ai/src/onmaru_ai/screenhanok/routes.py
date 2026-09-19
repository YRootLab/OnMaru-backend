from __future__ import annotations

from fastapi import FastAPI, Header, HTTPException, Request, status
from fastapi.responses import JSONResponse
from pydantic import ValidationError

from onmaru_ai.config.secrets import SecretProvider
from onmaru_ai.observability.correlation import correlation_from
from onmaru_ai.screenhanok.models import ScreenHanokResearchRequest
from onmaru_ai.screenhanok.service import ScreenHanokResearchService
from onmaru_ai.security.internal_auth import validate_internal_token

REQUIRED_SCOPE = "screen-hanok.research:write"


def install_screen_hanok_research(
    app: FastAPI,
    secret_provider: SecretProvider,
    research_service: ScreenHanokResearchService,
) -> None:
    @app.post(
        "/internal/v1/screen-hanok/research",
        status_code=status.HTTP_200_OK,
        response_model=None,
    )
    async def research(
        request: Request,
        authorization: str = Header(default=""),
    ) -> dict[str, object] | JSONResponse:
        try:
            validate_internal_token(authorization, secret_provider, required_scope=REQUIRED_SCOPE)
        except HTTPException as exception:
            return JSONResponse(status_code=exception.status_code, content=exception.detail)

        context = correlation_from(request)
        try:
            body = ScreenHanokResearchRequest.model_validate(await request.json())
        except (ValueError, ValidationError):
            return _contract_error()
        if body.request_id != context.request_id:
            return _contract_error()

        matches = await research_service.research(body.candidates, request_id=body.request_id)
        return {
            "schemaVersion": "internal.screen-hanok.v1",
            "requestId": body.request_id,
            "matches": [match.model_dump(by_alias=True) for match in matches],
        }


def _contract_error() -> JSONResponse:
    return JSONResponse(
        status_code=422,
        content={
            "schemaVersion": "internal.screen-hanok.v1",
            "code": "INTERNAL_SCREEN_HANOK_CONTRACT_INVALID",
            "message": "INTERNAL_SCREEN_HANOK_CONTRACT_INVALID",
        },
    )

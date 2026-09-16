from __future__ import annotations

import base64
import hashlib
import hmac
import json
import uuid
from collections.abc import Callable
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Any, Literal

from fastapi import FastAPI, Header, HTTPException, Request, status
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field, ValidationError

from onmaru_ai.config.secrets import SecretProvider
from onmaru_ai.observability.correlation import correlation_from

INTERNAL_TOKEN_SECRET_NAME = "internal-ai.service-token"
EXPECTED_ISSUER = "onmaru-spring"
EXPECTED_AUDIENCE = "onmaru-ai"
REQUIRED_SCOPE = "journey.proposal:write"


@dataclass(frozen=True)
class InternalAuthResult:
    issuer: str
    subject: str
    audience: str
    scope: str
    jti: str


class InternalAuthError(ValueError):
    def __init__(self, code: str, status_code: int) -> None:
        super().__init__(code)
        self.code = code
        self.status_code = status_code


class JourneyProposalRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    schema_version: Literal["internal.ai.v1"] = Field(alias="schemaVersion")
    request_id: str = Field(alias="requestId", min_length=1, max_length=128)
    run_id: str = Field(alias="runId", min_length=1, max_length=128)
    candidate_count: int = Field(alias="candidateCount", ge=0, le=12)


def install_internal_auth(app: FastAPI, secret_provider: SecretProvider) -> None:
    @app.post(
        "/internal/v1/journey/proposals",
        status_code=status.HTTP_202_ACCEPTED,
        response_model=None,
    )
    async def create_journey_proposal(
        request: Request,
        authorization: str = Header(default=""),
    ) -> dict[str, object] | JSONResponse:
        try:
            validate_internal_token(authorization, secret_provider)
        except HTTPException as exception:
            return JSONResponse(status_code=exception.status_code, content=exception.detail)
        context = correlation_from(request)
        try:
            body = JourneyProposalRequest.model_validate(await request.json())
        except (ValueError, ValidationError):
            return _contract_error()
        if body.run_id != context.run_id or body.request_id != context.request_id:
            return _contract_error()
        candidate_count = body.candidate_count
        selected_count = min(candidate_count, 3)
        return {
            "schemaVersion": "internal.ai.v1",
            "runId": context.run_id,
            "orderedRefs": [f"place:{index:03d}" for index in range(1, selected_count + 1)],
            "outcome": "PROPOSAL" if selected_count else "NO_RESULTS",
        }


def _contract_error() -> JSONResponse:
    return JSONResponse(
        status_code=422,
        content={
            "schemaVersion": "internal.ai.v1",
            "code": "INTERNAL_AI_CONTRACT_INVALID",
            "message": "INTERNAL_AI_CONTRACT_INVALID",
        },
    )


def validate_internal_token(
    authorization: str,
    secret_provider: SecretProvider,
    now: Callable[[], datetime] | None = None,
) -> InternalAuthResult:
    if not authorization.startswith("Bearer "):
        raise _http_error("INTERNAL_AUTH_MISSING", status.HTTP_401_UNAUTHORIZED)
    token = authorization.removeprefix("Bearer ").strip()
    try:
        header, claims, signing_input, signature = _decode_token(token)
    except (ValueError, json.JSONDecodeError, UnicodeDecodeError):
        raise _http_error("INTERNAL_AUTH_MALFORMED", status.HTTP_401_UNAUTHORIZED) from None

    if header.get("alg") != "HS256":
        raise _http_error("INTERNAL_AUTH_UNSUPPORTED_ALG", status.HTTP_401_UNAUTHORIZED)

    secret = secret_provider.get(INTERNAL_TOKEN_SECRET_NAME)
    current_matches = _signature_matches(secret.current, signing_input, signature)
    previous_matches = secret.previous is not None and _signature_matches(
        secret.previous,
        signing_input,
        signature,
    )
    if not current_matches and not previous_matches:
        raise _http_error("INTERNAL_AUTH_BAD_SIGNATURE", status.HTTP_401_UNAUTHORIZED)

    if claims.get("iss") != EXPECTED_ISSUER or claims.get("sub") != "spring-api":
        raise _http_error("INTERNAL_AUTH_INVALID_ISSUER", status.HTTP_401_UNAUTHORIZED)
    if claims.get("aud") != EXPECTED_AUDIENCE:
        raise _http_error("INTERNAL_AUTH_INVALID_AUDIENCE", status.HTTP_401_UNAUTHORIZED)
    if claims.get("scope") != REQUIRED_SCOPE:
        raise _http_error("INTERNAL_AUTH_FORBIDDEN_SCOPE", status.HTTP_403_FORBIDDEN)

    current_time = int((now or (lambda: datetime.now(UTC)))().timestamp())
    exp = claims.get("exp")
    if not isinstance(exp, int) or exp <= current_time:
        raise _http_error("INTERNAL_AUTH_EXPIRED", status.HTTP_401_UNAUTHORIZED)

    jti = claims.get("jti")
    if not isinstance(jti, str) or not jti:
        raise _http_error("INTERNAL_AUTH_MALFORMED", status.HTTP_401_UNAUTHORIZED)

    return InternalAuthResult(
        issuer=str(claims["iss"]),
        subject=str(claims["sub"]),
        audience=str(claims["aud"]),
        scope=str(claims["scope"]),
        jti=jti,
    )


def create_internal_token(
    *,
    secret: str,
    issuer: str,
    subject: str,
    audience: str,
    scope: str,
    jti: str | None = None,
    issued_at: datetime,
    expires_at: datetime,
) -> str:
    header = {"alg": "HS256", "typ": "JWT", "kid": "current"}
    claims = {
        "iss": issuer,
        "sub": subject,
        "aud": audience,
        "scope": scope,
        "jti": jti or str(uuid.uuid4()),
        "iat": int(issued_at.timestamp()),
        "exp": int(expires_at.timestamp()),
    }
    signing_input = f"{_json_b64(header)}.{_json_b64(claims)}"
    signature = _sign(secret, signing_input)
    return f"{signing_input}.{signature}"


def _http_error(code: str, status_code: int) -> HTTPException:
    return HTTPException(
        status_code=status_code,
        detail={
            "schemaVersion": "internal.ai.v1",
            "code": code,
            "message": code,
        },
    )


def _decode_token(token: str) -> tuple[dict[str, Any], dict[str, Any], str, str]:
    parts = token.split(".")
    if len(parts) != 3:
        raise ValueError("JWT must have three segments")
    header = json.loads(_b64decode(parts[0]))
    claims = json.loads(_b64decode(parts[1]))
    if not isinstance(header, dict) or not isinstance(claims, dict):
        raise ValueError("JWT header and claims must be objects")
    return header, claims, f"{parts[0]}.{parts[1]}", parts[2]


def _signature_matches(secret: str, signing_input: str, signature: str) -> bool:
    return hmac.compare_digest(_sign(secret, signing_input), signature)


def _sign(secret: str, signing_input: str) -> str:
    digest = hmac.new(secret.encode(), signing_input.encode(), hashlib.sha256).digest()
    return _b64encode(digest)


def _json_b64(value: dict[str, Any]) -> str:
    payload = json.dumps(value, separators=(",", ":"), sort_keys=True).encode()
    return _b64encode(payload)


def _b64encode(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).decode().rstrip("=")


def _b64decode(value: str) -> str:
    padded = value + "=" * (-len(value) % 4)
    return base64.urlsafe_b64decode(padded.encode()).decode()

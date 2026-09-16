from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass
from enum import StrEnum
from typing import Any


class GeminiFailureCode(StrEnum):
    AI_QUOTA_EXCEEDED = "AI_QUOTA_EXCEEDED"
    AI_TIMEOUT = "AI_TIMEOUT"
    AI_INVALID_RESPONSE = "AI_INVALID_RESPONSE"
    AI_SERVICE_UNAVAILABLE = "AI_SERVICE_UNAVAILABLE"
    CANCELLED = "CANCELLED"


class GeminiProviderError(RuntimeError):
    def __init__(self, code: GeminiFailureCode) -> None:
        self.code = code
        super().__init__(code.value)


@dataclass(frozen=True)
class GeminiPricing:
    input_micros_per_million: int
    output_micros_per_million: int

    def __post_init__(self) -> None:
        if self.input_micros_per_million < 0 or self.output_micros_per_million < 0:
            raise ValueError("Gemini pricing must not be negative")


@dataclass(frozen=True)
class GeminiConfig:
    model_alias: str
    model_name: str
    max_output_tokens: int
    pricing: GeminiPricing
    endpoint: str = "https://generativelanguage.googleapis.com"


@dataclass(frozen=True)
class GeminiPrompt:
    policy_block: str
    few_shot_block: str
    data_block: Mapping[str, Any]
    prompt_version: str
    adapter_version: str
    candidate_revision: str
    taxonomy_version: str
    safety_policy_version: str


@dataclass(frozen=True)
class GeminiTransportRequest:
    url: str
    headers: Mapping[str, str]
    body: Mapping[str, Any]
    timeout_seconds: float


@dataclass(frozen=True)
class GeminiTransportResponse:
    status_code: int
    body: Any


@dataclass(frozen=True)
class GeminiUsage:
    prompt_tokens: int
    output_tokens: int
    thought_tokens: int
    total_tokens: int
    estimated_cost_micros: int


@dataclass(frozen=True)
class GeminiResult:
    proposal: Mapping[str, Any]
    usage: GeminiUsage
    model_version: str

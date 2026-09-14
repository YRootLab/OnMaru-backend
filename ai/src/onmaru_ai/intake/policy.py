from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass
from enum import StrEnum

POLICY_VERSION = "intake-policy-v1"

HTML_PATTERN = re.compile(r"<\s*/?\s*(script|iframe|img|svg|style|a|html|body)\b", re.IGNORECASE)
PHONE_PATTERN = re.compile(r"(?<!\d)01[016789][-\s]?\d{3,4}[-\s]?\d{4}(?!\d)")
EMAIL_PATTERN = re.compile(r"\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b", re.IGNORECASE)
PROMPT_INJECTION_PATTERNS = (
    re.compile(r"이전\s*지시", re.IGNORECASE),
    re.compile(r"시스템\s*프롬프트", re.IGNORECASE),
    re.compile(r"ignore\s+(all\s+)?previous\s+instructions", re.IGNORECASE),
)

REGION_KEYWORDS = {
    "전주": "KR-45-JEONJU",
    "서울": "KR-11",
    "경주": "KR-47-GYEONGJU",
    "안동": "KR-47-ANDONG",
    "부산": "KR-26",
}

THEME_KEYWORDS = (
    ("HANOK_CAFE", ("한옥 카페", "한옥카페")),
    ("HANOK_STAY", ("한옥 숙소", "한옥숙박", "한옥 스테이")),
    ("HANOK_EXPERIENCE", ("한옥 체험",)),
    ("HANOK", ("한옥", "고택", "한옥마을")),
    ("MARKET", ("전통시장", "시장")),
    ("ODII", ("오디", "odii", "오디오")),
    ("WALK", ("산책", "걷", "도보")),
    ("REST", ("휴식", "쉬는", "힐링")),
)

MOOD_KEYWORDS = (
    ("QUIET", ("조용", "한적")),
    ("LIVELY", ("활기", "북적")),
    ("RAINY_DAY", ("비 오는", "비오는", "우천")),
    ("HEALING", ("힐링", "회복", "쉬는")),
)

UNSUPPORTED_KEYWORDS = (
    "시험",
    "답안",
    "숙제",
    "코딩",
    "주식",
    "법률",
    "의학",
    "날씨",
    "교통",
)


class IntakeDecisionType(StrEnum):
    SAFETY_BLOCKED = "SAFETY_BLOCKED"
    PRIVACY_REDACT_REQUIRED = "PRIVACY_REDACT_REQUIRED"
    JOURNEY_SCOPE_UNSUPPORTED = "JOURNEY_SCOPE_UNSUPPORTED"
    ASK_REGION = "ASK_REGION"
    SEARCH_CANDIDATES = "SEARCH_CANDIDATES"


@dataclass(frozen=True)
class JourneyIntent:
    region_code: str
    themes: tuple[str, ...]
    moods: tuple[str, ...]
    explicit_constraints: tuple[str, ...] = ()


@dataclass(frozen=True)
class IntakeDecision:
    type: IntakeDecisionType
    code: str
    normalized_query: str
    policy_version: str = POLICY_VERSION
    intent: JourneyIntent | None = None
    should_call_provider: bool = False


def evaluate_intake(raw_query: str) -> IntakeDecision:
    normalized = normalize_query(raw_query)
    if contains_html(normalized) or contains_prompt_injection(normalized):
        return blocked(IntakeDecisionType.SAFETY_BLOCKED, normalized)
    if contains_private_identifier(normalized):
        return blocked(IntakeDecisionType.PRIVACY_REDACT_REQUIRED, normalized)

    themes = detect_themes(normalized)
    if not themes or is_obviously_unsupported(normalized):
        return blocked(IntakeDecisionType.JOURNEY_SCOPE_UNSUPPORTED, normalized)

    region_code = detect_region(normalized)
    if region_code is None:
        return IntakeDecision(
            type=IntakeDecisionType.ASK_REGION,
            code="REGION_REQUIRED",
            normalized_query=normalized,
        )

    return IntakeDecision(
        type=IntakeDecisionType.SEARCH_CANDIDATES,
        code="SEARCH_CANDIDATES",
        normalized_query=normalized,
        intent=JourneyIntent(
            region_code=region_code,
            themes=themes,
            moods=detect_moods(normalized),
        ),
        should_call_provider=True,
    )


def normalize_query(raw_query: str) -> str:
    return unicodedata.normalize("NFC", raw_query).strip()


def contains_html(query: str) -> bool:
    return HTML_PATTERN.search(query) is not None


def contains_prompt_injection(query: str) -> bool:
    return any(pattern.search(query) is not None for pattern in PROMPT_INJECTION_PATTERNS)


def contains_private_identifier(query: str) -> bool:
    return PHONE_PATTERN.search(query) is not None or EMAIL_PATTERN.search(query) is not None


def is_obviously_unsupported(query: str) -> bool:
    return any(keyword in query for keyword in UNSUPPORTED_KEYWORDS)


def detect_region(query: str) -> str | None:
    for keyword, region_code in REGION_KEYWORDS.items():
        if keyword in query:
            return region_code
    return None


def detect_themes(query: str) -> tuple[str, ...]:
    found: list[str] = []
    for theme, keywords in THEME_KEYWORDS:
        if any(keyword.lower() in query.lower() for keyword in keywords):
            found.append(theme)
    return tuple(found)


def detect_moods(query: str) -> tuple[str, ...]:
    found: list[str] = []
    for mood, keywords in MOOD_KEYWORDS:
        if any(keyword in query for keyword in keywords):
            found.append(mood)
    return tuple(found) or ("NONE",)


def blocked(decision_type: IntakeDecisionType, normalized_query: str) -> IntakeDecision:
    return IntakeDecision(
        type=decision_type,
        code=decision_type.value,
        normalized_query=normalized_query,
    )

from typing import Final

from onmaru_ai.enrichment.schema import (
    EnrichmentCoverageStatus,
    EnrichmentPayload,
    ResourceRef,
)

ALLOWED_SOURCE_PREFIXES: Final[tuple[str, ...]] = (
    "catalog:",
    "hanok:",
    "odii:",
    "review:",
    "region:",
    "corpus:",
    "evidence-",
)


def is_valid_evidence_ref(ref: str) -> bool:
    if not ref or not isinstance(ref, str):
        return False
    return any(ref.startswith(prefix) for prefix in ALLOWED_SOURCE_PREFIXES)


def validate_evidence_refs(refs: list[str]) -> bool:
    if not refs:
        return False
    return all(is_valid_evidence_ref(r) for r in refs)


def validate_enrichment_payload(payload: EnrichmentPayload) -> EnrichmentPayload:
    if payload.coverageStatus in (
        EnrichmentCoverageStatus.INSUFFICIENT_EVIDENCE,
        EnrichmentCoverageStatus.UNAVAILABLE,
    ):
        return payload

    # Validate tags
    for tag in payload.tags:
        if "#" in tag or len(tag) < 2 or len(tag) > 20:
            return create_insufficient_evidence_fallback(
                payload.resourceRef, "태그 형식이 유효하지 않습니다."
            )

    # Validate whyRecommended
    for item in payload.whyRecommended:
        if not validate_evidence_refs(item.evidenceRefs):
            return create_insufficient_evidence_fallback(
                payload.resourceRef,
                "추천 이유에 유효한 출처가 누락되었거나 허용되지 않은 출처가 포함되어 있습니다.",
            )

    # Validate visitTips
    for tip in payload.visitTips:
        if not validate_evidence_refs(tip.evidenceRefs):
            return create_insufficient_evidence_fallback(
                payload.resourceRef,
                "방문 팁에 유효한 출처가 누락되었거나 허용되지 않은 출처가 포함되어 있습니다.",
            )

    # Validate hanokHighlights
    for highlight in payload.hanokHighlights:
        if not validate_evidence_refs(highlight.evidenceRefs):
            return create_insufficient_evidence_fallback(
                payload.resourceRef,
                "한옥 특징에 유효한 출처가 누락되었거나 허용되지 않은 출처가 포함되어 있습니다.",
            )

    # Validate reviewSummary
    if payload.reviewSummary is not None and not validate_evidence_refs(
        payload.reviewSummary.evidenceRefs
    ):
        return create_insufficient_evidence_fallback(
            payload.resourceRef,
            "후기 요약에 유효한 출처가 누락되었거나 허용되지 않은 출처가 포함되어 있습니다.",
        )

    return payload


def create_insufficient_evidence_fallback(
    resource_ref: ResourceRef, reason: str
) -> EnrichmentPayload:
    return EnrichmentPayload(
        schemaVersion="1.2",
        resourceRef=resource_ref,
        coverageStatus=EnrichmentCoverageStatus.INSUFFICIENT_EVIDENCE,
        summary=None,
        whyRecommended=[],
        bestFor=[],
        visitTips=[],
        hanokHighlights=[],
        reviewSummary=None,
        tags=[],
        unknowns=[reason],
        presentationHints=None,
    )

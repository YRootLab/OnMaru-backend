from onmaru_ai.enrichment.schema import (
    EnrichmentCoverageStatus,
    EnrichmentPayload,
    HanokHighlightItem,
    ResourceRef,
    ReviewSummaryBlock,
    VisitTipItem,
    WhyRecommendedItem,
)
from onmaru_ai.enrichment.validator import (
    validate_enrichment_payload,
)


def test_valid_enrichment_payload_passes() -> None:
    payload = EnrichmentPayload(
        schemaVersion="1.2",
        resourceRef=ResourceRef(type="PLACE", id="p-jeonju-hanok-village"),
        coverageStatus=EnrichmentCoverageStatus.SUPPORTED,
        summary="전주 한옥마을 요약 설명입니다.",
        whyRecommended=[
            WhyRecommendedItem(
                text="조용한 한옥 산책과 사진 촬영에 좋습니다.",
                evidenceRefs=["catalog:p-jeonju-hanok-village", "review:cluster:quiet"],
            )
        ],
        bestFor=[],
        visitTips=[
            VisitTipItem(
                label="방문 팁",
                text="이른 아침에 방문하세요.",
                evidenceRefs=["review:cluster:time"],
            )
        ],
        hanokHighlights=[
            HanokHighlightItem(
                label="기와",
                description="전통 기와 지붕선",
                evidenceRefs=["catalog:p-jeonju-hanok-village"],
            )
        ],
        reviewSummary=ReviewSummaryBlock(
            coverage="PARTIAL",
            reviewCount=10,
            positive=["조용함"],
            cautions=[],
            evidenceRefs=["review:cluster:quiet"],
        ),
        tags=["한옥산책", "전주여행"],
        unknowns=[],
        presentationHints=None,
    )

    validated = validate_enrichment_payload(payload)
    assert validated.coverageStatus == EnrichmentCoverageStatus.SUPPORTED
    assert validated.summary == "전주 한옥마을 요약 설명입니다."
    assert len(validated.whyRecommended) == 1


def test_invalid_evidence_ref_triggers_fallback() -> None:
    payload = EnrichmentPayload(
        schemaVersion="1.2",
        resourceRef=ResourceRef(type="PLACE", id="p-jeonju-hanok-village"),
        coverageStatus=EnrichmentCoverageStatus.SUPPORTED,
        summary="전주 한옥마을 요약 설명입니다.",
        whyRecommended=[
            WhyRecommendedItem(
                text="검증되지 않은 출처 기반 추천",
                evidenceRefs=["untrusted_blog:1234"],
            )
        ],
        bestFor=[],
        visitTips=[],
        hanokHighlights=[],
        reviewSummary=None,
        tags=["한옥산책"],
        unknowns=[],
        presentationHints=None,
    )

    validated = validate_enrichment_payload(payload)
    assert validated.coverageStatus == EnrichmentCoverageStatus.INSUFFICIENT_EVIDENCE
    assert validated.summary is None
    assert len(validated.unknowns) > 0


def test_invalid_tag_format_triggers_fallback() -> None:
    payload = EnrichmentPayload(
        schemaVersion="1.2",
        resourceRef=ResourceRef(type="PLACE", id="p-jeonju-hanok-village"),
        coverageStatus=EnrichmentCoverageStatus.SUPPORTED,
        summary="전주 한옥마을 요약 설명입니다.",
        whyRecommended=[
            WhyRecommendedItem(
                text="추천 이유",
                evidenceRefs=["catalog:p-jeonju-hanok-village"],
            )
        ],
        tags=["#잘못된태그"],  # '#' not allowed
        unknowns=[],
    )

    validated = validate_enrichment_payload(payload)
    assert validated.coverageStatus == EnrichmentCoverageStatus.INSUFFICIENT_EVIDENCE

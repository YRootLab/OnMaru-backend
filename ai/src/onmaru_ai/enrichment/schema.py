from enum import StrEnum

from pydantic import BaseModel, ConfigDict, Field


class EnrichmentCoverageStatus(StrEnum):
    SUPPORTED = "SUPPORTED"
    PARTIAL = "PARTIAL"
    INSUFFICIENT_EVIDENCE = "INSUFFICIENT_EVIDENCE"
    UNAVAILABLE = "UNAVAILABLE"


class WhyRecommendedItem(BaseModel):
    model_config = ConfigDict(extra="forbid")
    text: str = Field(min_length=1, max_length=500)
    evidenceRefs: list[str] = Field(min_length=1)


class BestForItem(BaseModel):
    model_config = ConfigDict(extra="forbid")
    label: str = Field(min_length=1, max_length=50)
    reason: str = Field(min_length=1, max_length=300)


class VisitTipItem(BaseModel):
    model_config = ConfigDict(extra="forbid")
    label: str = Field(min_length=1, max_length=50)
    text: str = Field(min_length=1, max_length=500)
    evidenceRefs: list[str] = Field(min_length=1)


class HanokHighlightItem(BaseModel):
    model_config = ConfigDict(extra="forbid")
    label: str = Field(min_length=1, max_length=50)
    description: str = Field(min_length=1, max_length=500)
    evidenceRefs: list[str] = Field(min_length=1)


class ReviewSummaryBlock(BaseModel):
    model_config = ConfigDict(extra="forbid")
    coverage: str = Field(min_length=1, max_length=50)
    reviewCount: int = Field(ge=0)
    positive: list[str] = Field(default_factory=list)
    cautions: list[str] = Field(default_factory=list)
    evidenceRefs: list[str] = Field(min_length=1)


class PresentationHints(BaseModel):
    model_config = ConfigDict(extra="forbid")
    intentType: str | None = Field(default=None, max_length=50)
    interestFocus: str | None = Field(default=None, max_length=50)
    answerDepth: str | None = Field(default=None, max_length=50)
    journeyContext: str | None = Field(default=None, max_length=50)
    decisionStage: str | None = Field(default=None, max_length=50)
    primaryMessage: str | None = Field(default=None, max_length=500)
    secondaryMessage: str | None = Field(default=None, max_length=500)
    recommendedSections: list[str] = Field(default_factory=list)


class ResourceRef(BaseModel):
    model_config = ConfigDict(extra="forbid")
    type: str = Field(min_length=1, max_length=50)
    id: str = Field(min_length=1, max_length=100)


class EnrichmentPayload(BaseModel):
    model_config = ConfigDict(extra="forbid")
    schemaVersion: str = Field(default="1.2")
    resourceRef: ResourceRef
    coverageStatus: EnrichmentCoverageStatus
    summary: str | None = Field(default=None, max_length=1000)
    whyRecommended: list[WhyRecommendedItem] = Field(default_factory=list)
    bestFor: list[BestForItem] = Field(default_factory=list)
    visitTips: list[VisitTipItem] = Field(default_factory=list)
    hanokHighlights: list[HanokHighlightItem] = Field(default_factory=list)
    reviewSummary: ReviewSummaryBlock | None = None
    tags: list[str] = Field(default_factory=list)
    unknowns: list[str] = Field(default_factory=list)
    presentationHints: PresentationHints | None = None

from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field

MediaType = Literal["K_DRAMA", "CINEMA", "KPOP"]


class ScreenHanokCandidate(BaseModel):
    model_config = ConfigDict(extra="forbid")

    place_id: str = Field(alias="placeId", min_length=1, max_length=128)
    name: str = Field(min_length=1, max_length=256)
    region_name: str = Field(alias="regionName", min_length=1, max_length=64)
    category: str = Field(min_length=1, max_length=64)


class ScreenHanokResearchRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    schema_version: Literal["internal.screen-hanok.v1"] = Field(alias="schemaVersion")
    request_id: str = Field(alias="requestId", min_length=1, max_length=128)
    candidates: list[ScreenHanokCandidate] = Field(min_length=1, max_length=50)


class ScreenHanokMatch(BaseModel):
    model_config = ConfigDict(extra="forbid")

    place_id: str = Field(alias="placeId")
    media_type: MediaType = Field(alias="mediaType")
    work_title: str = Field(alias="workTitle", min_length=1, max_length=200)
    subtitle: str = Field(default="", max_length=400)
    tags: list[str] = Field(default_factory=list, max_length=12)
    source_url: str = Field(alias="sourceUrl", min_length=1, max_length=2048)
    source_title: str = Field(alias="sourceTitle", default="", max_length=300)


class ScreenHanokResearchResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    schema_version: Literal["internal.screen-hanok.v1"] = Field(
        default="internal.screen-hanok.v1", alias="schemaVersion"
    )
    request_id: str = Field(alias="requestId")
    matches: list[ScreenHanokMatch]

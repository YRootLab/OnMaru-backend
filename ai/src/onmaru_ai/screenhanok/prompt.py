from __future__ import annotations

from onmaru_ai.providers.gemini.models import GeminiPrompt
from onmaru_ai.screenhanok.models import ScreenHanokCandidate

PROMPT_VERSION = "screen-hanok-research-v1"
ADAPTER_VERSION = "gemini-adapter-v1"
TAXONOMY_VERSION = "screen-hanok-media-type-v1"
SAFETY_POLICY_VERSION = "screen-hanok-safety-v1"

POLICY_BLOCK = (
    "You research whether a real Korean traditional place (hanok, traditional village, palace, "
    "market) has genuinely appeared in a real, released Korean drama, film, or K-pop music video. "
    "Use Google Search grounding to verify every claim. Report a match for a candidate only if "
    "you can also give a real, working source URL (a news article, official production notice, "
    "or a reputable tourism/media site) that supports the specific place-to-work claim. "
    "If you cannot find a verifiable source for a candidate, omit it from the response entirely -- "
    "do not guess, do not invent a title, and do not invent a URL. Never claim a place appeared in "
    "a work you are not able to cite. Respond with matches for supplied candidates only; never "
    "introduce a placeId that was not supplied."
)

FEW_SHOT_BLOCK = (
    '<example-input>{"candidates":[{"placeId":"p-001","name":"OO 고택",'
    '"regionName":"경북","category":"HANOK"}]}</example-input>\n'
    '<example-output>{"matches":[{"placeId":"p-001","mediaType":"K_DRAMA",'
    '"workTitle":"OO","subtitle":"실제 촬영이 보도된 사극 드라마 로케이션입니다.",'
    '"tags":["#사극로케이션"],"sourceUrl":"https://example.com/news/001",'
    '"sourceTitle":"OO 촬영지 보도"}]}</example-output>\n'
    '<example-input>{"candidates":[{"placeId":"p-002","name":"XX 한옥",'
    '"regionName":"전남","category":"HANOK_STAY"}]}</example-input>\n'
    '<example-output>{"matches":[]}</example-output>'
)

RESPONSE_SCHEMA = {
    "type": "OBJECT",
    "properties": {
        "matches": {
            "type": "ARRAY",
            "items": {
                "type": "OBJECT",
                "properties": {
                    "placeId": {"type": "STRING"},
                    "mediaType": {"type": "STRING", "enum": ["K_DRAMA", "CINEMA", "KPOP"]},
                    "workTitle": {"type": "STRING"},
                    "subtitle": {"type": "STRING"},
                    "tags": {"type": "ARRAY", "items": {"type": "STRING"}},
                    "sourceUrl": {"type": "STRING"},
                    "sourceTitle": {"type": "STRING"},
                },
                "required": ["placeId", "mediaType", "workTitle", "sourceUrl"],
            },
        }
    },
    "required": ["matches"],
}


def build_prompt(candidates: list[ScreenHanokCandidate], *, request_id: str) -> GeminiPrompt:
    data_block = {
        "candidates": [
            {
                "placeId": candidate.place_id,
                "name": candidate.name,
                "regionName": candidate.region_name,
                "category": candidate.category,
            }
            for candidate in candidates
        ]
    }
    return GeminiPrompt(
        policy_block=POLICY_BLOCK,
        few_shot_block=FEW_SHOT_BLOCK,
        data_block=data_block,
        prompt_version=PROMPT_VERSION,
        adapter_version=ADAPTER_VERSION,
        candidate_revision=request_id,
        taxonomy_version=TAXONOMY_VERSION,
        safety_policy_version=SAFETY_POLICY_VERSION,
    )

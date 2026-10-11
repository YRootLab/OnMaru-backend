"""W6 worker fixture and W7 versioned JSON Schema must stay compatible."""

import json
from pathlib import Path

from jsonschema import Draft202012Validator, FormatChecker


ROOT = Path(__file__).resolve().parents[2]
SCHEMA = json.loads((ROOT / "docs/contracts/schemas/kcontents-extraction.schema.json").read_text())
FIXTURE = json.loads((ROOT / "apps/spring-api/src/test/resources/fixtures/kcontents/w7-submission.json").read_text())
VALIDATOR = Draft202012Validator(SCHEMA, format_checker=FormatChecker())


def test_worker_fixture_matches_versioned_schema():
    Draft202012Validator.check_schema(SCHEMA)
    submission = FIXTURE["submission"]
    assert submission["schemaVersion"] == "kcontents-extraction-v1"
    assert submission["promptVersion"] == "kcontents-evidence-v1"
    result = json.loads(submission["resultJson"])
    VALIDATOR.validate(result)
    assert set(submission["evidenceIds"]) == {
        claim["evidenceId"]
        for candidate in result["candidates"]
        for claim in candidate["evidence"]
    }


def test_no_match_and_extra_model_confidence_boundary():
    VALIDATOR.validate({"resultStatus": "NO_MATCH", "candidates": [], "nextSearchAt": "2026-11-01T00:00:00Z"})
    assert not VALIDATOR.is_valid({"resultStatus": "NO_MATCH", "candidates": [], "nextSearchAt": "tomorrow"})
    assert not VALIDATOR.is_valid({"resultStatus": "MATCH", "candidates": [], "nextSearchAt": "2026-11-01T00:00:00Z"})
    result = json.loads(FIXTURE["submission"]["resultJson"])
    result["confidence"] = 1
    assert not VALIDATOR.is_valid(result)

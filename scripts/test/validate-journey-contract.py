#!/usr/bin/env python3
"""Validate Journey OpenAPI paths and executable scenario fixtures."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import yaml
from jsonschema import Draft202012Validator, FormatChecker
from openapi_spec_validator import validate


ROOT = Path(__file__).resolve().parents[2]
OPENAPI_PATH = ROOT / "docs/contracts/openapi/journey.openapi.yaml"
FIXTURE_DIR = ROOT / "docs/contracts/fixtures/journey"
REQUIRED_PATHS = {
    "/explorations",
    "/explorations/{explorationId}",
    "/explorations/{explorationId}/turns",
    "/explorations/{explorationId}/actions",
    "/explorations/{explorationId}/runs/{runId}",
    "/explorations/{explorationId}/runs/{runId}/events",
    "/explorations/{explorationId}/runs/{runId}/cancel",
    "/saved-journeys",
    "/saved-journeys/{savedJourneyId}",
    "/saved-journeys/{savedJourneyId}/resume",
}
REQUIRED_SCHEMAS = {
    "ActionCommandRequest",
    "ExplorationSnapshot",
    "SavedJourneyBoard",
    "SavedJourney",
    "SavedJourneyPage",
    "ResumeSavedJourneyResponse",
    "Error",
}
REQUIRED_FIXTURES = {
    "exploration-snapshot-normal",
    "exploration-snapshot-other-actor",
    "action-pin-normal",
    "action-idempotency-replay",
    "action-version-conflict",
    "action-idempotency-conflict",
    "action-pinned-ref-conflict",
    "action-proposal-expired",
    "action-csrf-invalid",
    "action-idempotency-invalid",
    "action-other-actor",
    "saved-journey-create-normal",
    "saved-journey-create-replay",
    "saved-journey-active-run",
    "saved-journey-page-normal",
    "saved-journey-detail-expired-source",
    "saved-journey-detail-other-actor",
    "saved-journey-delete-normal",
    "saved-journey-delete-other-actor",
    "saved-journey-delete-replay",
    "saved-journey-resume-unavailable",
    "saved-journey-resume-other-actor",
}


def fail(message: str) -> None:
    raise AssertionError(message)


def rewrite_refs(value: Any) -> Any:
    if isinstance(value, dict):
        return {
            key: (
                nested.replace("#/components/schemas/", "#/$defs/")
                if key == "$ref"
                and isinstance(nested, str)
                and nested.startswith("#/components/schemas/")
                else rewrite_refs(nested)
            )
            for key, nested in value.items()
        }
    if isinstance(value, list):
        return [rewrite_refs(item) for item in value]
    return value


def response_schema(operation: dict[str, Any], status: int) -> str | None:
    responses = operation.get("responses", {})
    if str(status) not in responses:
        fail(f"response status {status} is not declared by the operation")
    response = responses[str(status)]
    ref = response.get("$ref")
    if ref:
        return "Error" if ref == "#/components/responses/Error" else None
    schema = response.get("content", {}).get("application/json", {}).get("schema", {})
    schema_ref = schema.get("$ref")
    return schema_ref.rsplit("/", 1)[-1] if isinstance(schema_ref, str) else None


def validate_value(value: Any, schema: dict[str, Any], definitions: dict[str, Any], source: str) -> None:
    json_schema = {
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        **rewrite_refs(schema),
        "$defs": definitions,
    }
    validator = Draft202012Validator(json_schema, format_checker=FormatChecker())
    errors = sorted(validator.iter_errors(value), key=lambda error: list(error.path))
    if errors:
        fail(f"{source}: {errors[0].message}")


def validate_request(
    fixture: dict[str, Any],
    contract_path: str,
    path_item: dict[str, Any],
    operation: dict[str, Any],
    definitions: dict[str, Any],
    parameter_definitions: dict[str, Any],
    response: dict[str, Any],
    source: str,
) -> None:
    request = fixture["request"]
    actual_segments = request["path"][len("/api/v1") :].strip("/").split("/")
    template_segments = contract_path.strip("/").split("/")
    path_values = {
        template[1:-1]: actual
        for template, actual in zip(template_segments, actual_segments)
        if template.startswith("{") and template.endswith("}")
    }
    locations = {
        "path": path_values,
        "header": request.get("headers", {}),
        "query": request.get("query", {}),
    }
    for raw_parameter in [*path_item.get("parameters", []), *operation.get("parameters", [])]:
        parameter = raw_parameter
        ref = raw_parameter.get("$ref")
        if isinstance(ref, str) and ref.startswith("#/components/parameters/"):
            parameter = parameter_definitions[ref.rsplit("/", 1)[-1]]
        location = parameter.get("in")
        name = parameter.get("name")
        values = locations.get(location, {})
        if parameter.get("required") and name not in values:
            error_code = response.get("body", {}).get("code")
            expected_missing_csrf = (
                location == "header"
                and name == "X-CSRF-TOKEN"
                and response.get("status") == 403
                and error_code == "CSRF_INVALID"
            )
            expected_missing_idempotency = (
                location == "header"
                and name == "Idempotency-Key"
                and response.get("status") == 400
                and error_code == "VALIDATION_ERROR"
            )
            if not expected_missing_csrf and not expected_missing_idempotency:
                fail(f"{source}: missing required {location} parameter {name}")
        if name in values:
            validate_value(
                values[name],
                parameter.get("schema", {}),
                definitions,
                f"{source} {location} parameter {name}",
            )

    body_schema = (
        operation.get("requestBody", {})
        .get("content", {})
        .get("application/json", {})
        .get("schema")
    )
    if body_schema is not None:
        if "body" not in request:
            fail(f"{source}: request body is required by the fixture contract")
        validate_value(request["body"], body_schema, definitions, f"{source} request body")

    body = request.get("body", {})
    headers = request.get("headers", {})
    command_id = body.get("commandId") if isinstance(body, dict) else None
    missing_key_fixture = (
        "Idempotency-Key" not in headers
        and response.get("status") == 400
        and response.get("body", {}).get("code") == "VALIDATION_ERROR"
    )
    if command_id is not None and not missing_key_fixture and headers.get("Idempotency-Key") != command_id:
        fail(f"{source}: commandId must equal Idempotency-Key")


def match_path(request_path: str, paths: dict[str, Any]) -> str:
    prefix = "/api/v1"
    if not request_path.startswith(prefix):
        fail(f"fixture path must start with {prefix}: {request_path}")
    segments = request_path[len(prefix) :].strip("/").split("/")
    for candidate in paths:
        candidate_segments = candidate.strip("/").split("/")
        if len(segments) == len(candidate_segments) and all(
            part == actual or part.startswith("{")
            for part, actual in zip(candidate_segments, segments)
        ):
            return candidate
    fail(f"fixture path is not declared in OpenAPI: {request_path}")


def main() -> None:
    with OPENAPI_PATH.open(encoding="utf-8") as handle:
        openapi = yaml.safe_load(handle)
    validate(openapi)
    paths = openapi.get("paths", {})
    schemas = openapi.get("components", {}).get("schemas", {})
    missing_paths = REQUIRED_PATHS.difference(paths)
    missing_schemas = REQUIRED_SCHEMAS.difference(schemas)
    if missing_paths:
        fail(f"missing Journey paths: {sorted(missing_paths)}")
    if missing_schemas:
        fail(f"missing Journey schemas: {sorted(missing_schemas)}")
    if not FIXTURE_DIR.exists():
        fail(f"missing fixture directory: {FIXTURE_DIR}")

    fixtures = []
    for fixture_path in sorted(FIXTURE_DIR.glob("*.json")):
        with fixture_path.open(encoding="utf-8") as handle:
            fixture = json.load(handle)
        fixtures.append((fixture_path, fixture))
    missing_fixtures = REQUIRED_FIXTURES.difference(
        fixture.get("name") for _, fixture in fixtures
    )
    if missing_fixtures:
        fail(f"missing Journey fixtures: {sorted(missing_fixtures)}")

    definitions = {name: rewrite_refs(schema) for name, schema in schemas.items()}
    parameter_definitions = openapi.get("components", {}).get("parameters", {})
    for fixture_path, fixture in fixtures:
        request = fixture["request"]
        response = fixture["response"]
        contract_path = match_path(request["path"], paths)
        operation = paths[contract_path].get(request["method"].lower())
        if not isinstance(operation, dict):
            fail(f"{fixture_path.name}: undeclared method {request['method']}")
        validate_request(
            fixture,
            contract_path,
            paths[contract_path],
            operation,
            definitions,
            parameter_definitions,
            response,
            fixture_path.name,
        )
        expected = response_schema(operation, response["status"])
        if expected != response.get("schema"):
            fail(f"{fixture_path.name}: expected response schema {expected}, got {response.get('schema')}")
        if "body" not in response:
            if expected is not None:
                fail(f"{fixture_path.name}: response body is required for schema {expected}")
            continue
        if expected is None:
            fail(f"{fixture_path.name}: response body is not declared by the operation")
        validate_value(
            response["body"],
            {"$ref": f"#/components/schemas/{expected}"},
            definitions,
            fixture_path.name,
        )

    fixtures_by_name = {fixture["name"]: fixture for _, fixture in fixtures}
    pin = fixtures_by_name["action-pin-normal"]
    if pin["response"]["body"]["stateVersion"] != pin["request"]["body"]["baseVersion"] + 1:
        fail("action-pin-normal must demonstrate a stateVersion increment")
    replay = fixtures_by_name["action-idempotency-replay"]
    if replay["request"] != pin["request"] or replay["response"] != pin["response"]:
        fail("action-idempotency-replay must return the original response for the same request")
    conflict = fixtures_by_name["action-idempotency-conflict"]
    if (
        conflict["request"]["headers"]["Idempotency-Key"]
        != pin["request"]["headers"]["Idempotency-Key"]
        or conflict["request"]["method"] != pin["request"]["method"]
        or conflict["request"]["path"] != pin["request"]["path"]
        or conflict["request"]["body"] == pin["request"]["body"]
    ):
        fail("action-idempotency-conflict must reuse the key with a different payload")
    for name, code in (
        ("action-version-conflict", "VERSION_CONFLICT"),
        ("action-idempotency-conflict", "IDEMPOTENCY_CONFLICT"),
        ("action-pinned-ref-conflict", "PINNED_REF"),
        ("action-proposal-expired", "PROPOSAL_EXPIRED"),
        ("saved-journey-active-run", "ACTIVE_RUN"),
    ):
        fixture = fixtures_by_name[name]
        if fixture["response"]["status"] != 409 or fixture["response"]["body"]["code"] != code:
            fail(f"{name} must demonstrate 409 {code}")
    unavailable = fixtures_by_name["saved-journey-resume-unavailable"]["response"]["body"]["unavailableRefs"]
    if not unavailable:
        fail("saved-journey-resume-unavailable must include unavailableRefs")
    resumed = fixtures_by_name["saved-journey-resume-unavailable"]["response"]["body"]["exploration"]
    if resumed["board"] is not None or resumed["stateVersion"] != 0:
        fail("an all-unavailable resume must return an empty version-zero exploration")

    forbidden_snapshot_fields = {
        "explorationId",
        "latestRun",
        "pendingProposal",
        "recentHistory",
    }
    for name in ("saved-journey-create-normal", "saved-journey-detail-expired-source"):
        saved = fixtures_by_name[name]["response"]["body"]
        leaked = forbidden_snapshot_fields.intersection(saved["snapshot"])
        if leaked:
            fail(f"{name} leaks ephemeral fields into the saved snapshot: {sorted(leaked)}")
        board = saved["snapshot"]["board"]
        if "querySummary" in board or not board.get("summaryRefs"):
            fail(f"{name} must store canonical summaryRefs instead of a free-text querySummary")
    expired_source = fixtures_by_name["saved-journey-detail-expired-source"]["response"]["body"]
    if expired_source["sourceExplorationId"] is not None:
        fail("saved-journey-detail-expired-source must allow an expired source exploration")
    csrf = fixtures_by_name["action-csrf-invalid"]
    if (
        "X-CSRF-TOKEN" in csrf["request"].get("headers", {})
        or csrf["response"]["status"] != 403
        or csrf["response"]["body"]["code"] != "CSRF_INVALID"
    ):
        fail("action-csrf-invalid must demonstrate a missing CSRF token as 403 CSRF_INVALID")
    invalid_key = fixtures_by_name["action-idempotency-invalid"]
    if (
        "Idempotency-Key" in invalid_key["request"].get("headers", {})
        or invalid_key["response"]["status"] != 400
        or invalid_key["response"]["body"]["code"] != "VALIDATION_ERROR"
    ):
        fail("action-idempotency-invalid must reject a missing Idempotency-Key")
    for name in (
        "exploration-snapshot-other-actor",
        "action-other-actor",
        "saved-journey-detail-other-actor",
        "saved-journey-delete-other-actor",
        "saved-journey-resume-other-actor",
    ):
        fixture = fixtures_by_name[name]
        given = fixture.get("given", {})
        other_actor = fixture["response"]
        if (
            not given.get("ownerActorId")
            or not given.get("requestActorId")
            or given["ownerActorId"] == given["requestActorId"]
            or other_actor["status"] != 404
            or other_actor["body"]["code"] != "NOT_FOUND"
        ):
            fail(f"{name} must conceal a different owner's resource as 404 NOT_FOUND")
    delete = fixtures_by_name["saved-journey-delete-normal"]
    delete_replay = fixtures_by_name["saved-journey-delete-replay"]
    if delete_replay["request"] != delete["request"] or delete_replay["response"] != delete["response"]:
        fail("saved-journey-delete-replay must preserve the idempotent delete response")
    create = fixtures_by_name["saved-journey-create-normal"]
    create_replay = fixtures_by_name["saved-journey-create-replay"]
    if (
        create_replay["request"] != create["request"]
        or create_replay["response"]["status"] != 200
        or create_replay["response"]["body"] != create["response"]["body"]
    ):
        fail("saved-journey-create-replay must return the original saved journey with 200")

    unsafe_methods = {"post", "put", "patch", "delete"}
    for path, path_item in paths.items():
        for method, operation in path_item.items():
            if method not in unsafe_methods or not isinstance(operation, dict):
                continue
            refs = {
                parameter.get("$ref")
                for parameter in operation.get("parameters", [])
                if isinstance(parameter, dict)
            }
            if "#/components/parameters/IdempotencyKey" not in refs:
                fail(f"{method.upper()} {path} must require Idempotency-Key")
            if "#/components/parameters/CsrfToken" not in refs:
                fail(f"{method.upper()} {path} must require X-CSRF-TOKEN")
            if "403" not in operation.get("responses", {}):
                fail(f"{method.upper()} {path} must declare a 403 response")
            if "400" not in operation.get("responses", {}):
                fail(f"{method.upper()} {path} must declare a 400 validation response")
            if operation.get("requestBody") and "413" not in operation.get("responses", {}):
                fail(f"{method.upper()} {path} must declare a 413 payload response")

    for path_item in paths.values():
        for operation in path_item.values():
            if not isinstance(operation, dict):
                continue
            for status, response in operation.get("responses", {}).items():
                if not str(status).startswith("2"):
                    continue
                headers = response.get("headers", {})
                cache_control = headers.get("Cache-Control", {})
                if cache_control.get("$ref") != "#/components/headers/NoStore":
                    fail(f"{operation.get('operationId')} {status} must declare Cache-Control: no-store")

    error_response = openapi["components"]["responses"]["Error"]
    if error_response.get("headers", {}).get("Cache-Control", {}).get("$ref") != "#/components/headers/NoStore":
        fail("all shared error responses must declare Cache-Control: no-store")
    no_store = openapi["components"]["headers"]["NoStore"].get("schema", {})
    if no_store.get("type") != "string" or no_store.get("const") != "no-store":
        fail("NoStore header must resolve to the literal no-store value")
    for path, path_item in paths.items():
        for method, operation in path_item.items():
            if not isinstance(operation, dict):
                continue
            for status, response in operation.get("responses", {}).items():
                if str(status).startswith("2"):
                    continue
                if response.get("$ref") != "#/components/responses/Error":
                    fail(f"{method.upper()} {path} {status} must use the no-store Error response")

    list_responses = paths["/saved-journeys"]["get"]["responses"]
    if "410" not in list_responses:
        fail("saved journey pagination must declare cursor expiry as 410")
    error_codes = schemas["Error"]["properties"]["code"]["enum"]
    if "SAVE_LIMIT" not in error_codes:
        fail("Error.code must include SAVE_LIMIT")

    print(f"validated Journey contract and {len(fixtures)} fixtures")


if __name__ == "__main__":
    main()

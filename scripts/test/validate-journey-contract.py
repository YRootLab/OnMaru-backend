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
    "SavedJourney",
    "SavedJourneyPage",
    "ResumeSavedJourneyResponse",
    "Error",
}
REQUIRED_FIXTURES = {
    "exploration-snapshot-normal",
    "action-pin-normal",
    "action-version-conflict",
    "action-idempotency-conflict",
    "action-pinned-ref-conflict",
    "action-proposal-expired",
    "saved-journey-create-normal",
    "saved-journey-active-run",
    "saved-journey-page-normal",
    "saved-journey-detail-expired-source",
    "saved-journey-delete-normal",
    "saved-journey-resume-unavailable",
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
    response = operation.get("responses", {}).get(str(status), {})
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
    if command_id is not None and headers.get("Idempotency-Key") != command_id:
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
            fixture_path.name,
        )
        expected = response_schema(operation, response["status"])
        if expected != response.get("schema"):
            fail(f"{fixture_path.name}: expected response schema {expected}, got {response.get('schema')}")
        if "body" not in response:
            continue
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
    expired_source = fixtures_by_name["saved-journey-detail-expired-source"]["response"]["body"]
    if expired_source["sourceExplorationId"] is not None:
        fail("saved-journey-detail-expired-source must allow an expired source exploration")

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

    list_responses = paths["/saved-journeys"]["get"]["responses"]
    if "410" not in list_responses:
        fail("saved journey pagination must declare cursor expiry as 410")
    error_codes = schemas["Error"]["properties"]["code"]["enum"]
    if "SAVE_LIMIT" not in error_codes:
        fail("Error.code must include SAVE_LIMIT")

    print(f"validated Journey contract and {len(fixtures)} fixtures")


if __name__ == "__main__":
    main()

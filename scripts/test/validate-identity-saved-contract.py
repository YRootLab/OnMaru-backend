#!/usr/bin/env python3
"""Validate the identity and saved-resource OpenAPI contract fixtures."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import yaml
from jsonschema import Draft202012Validator, FormatChecker
from openapi_spec_validator import validate


ROOT = Path(__file__).resolve().parents[2]
OPENAPI_PATH = ROOT / "docs/contracts/openapi/identity-saved.openapi.yaml"
FIXTURE_DIR = ROOT / "docs/contracts/fixtures/identity-saved"

REQUIRED_PATHS = {
    "/auth/csrf",
    "/auth/kakao/login",
    "/auth/kakao/callback",
    "/auth/logout",
    "/members/me",
    "/saved-resources/places/{placeId}",
    "/saved-resources/odii-stories/{storyId}",
    "/saved-resources",
    "/me/timeline",
}
REQUIRED_SCHEMAS = {
    "CsrfTokenResponse",
    "MemberMe",
    "MemberDeletingStatus",
    "SavedResourceState",
    "SavedResourcePage",
    "MemberTimeline",
    "Error",
}
REQUIRED_FIXTURES = {
    "csrf-normal",
    "kakao-login-redirect",
    "kakao-callback-success",
    "logout-normal",
    "member-me-normal",
    "member-me-auth-required",
    "member-delete-accepted",
    "save-place-normal",
    "save-place-auth-required",
    "save-place-csrf-invalid",
    "save-place-not-found",
    "save-place-limit-conflict",
    "delete-place-idempotent",
    "save-odii-story-normal",
    "delete-odii-story-idempotent",
    "saved-resources-place-page",
    "saved-resources-odii-page",
    "saved-resources-type-required",
    "timeline-normal",
    "timeline-auth-required",
}
FORBIDDEN_PUBLIC_KEYS = {"contentId", "contentid", "pageNo", "page_no", "key", "serviceKey"}


def fail(message: str) -> None:
    raise AssertionError(message)


def walk(value: Any, path: str = "$") -> list[tuple[str, Any]]:
    items = [(path, value)]
    if isinstance(value, dict):
        for key, nested in value.items():
            items.extend(walk(nested, f"{path}.{key}"))
    elif isinstance(value, list):
        for index, nested in enumerate(value):
            items.extend(walk(nested, f"{path}[{index}]"))
    return items


def assert_no_forbidden_public_keys(value: Any, source: str) -> None:
    for path, nested in walk(value):
        if isinstance(nested, dict):
            leaked = FORBIDDEN_PUBLIC_KEYS.intersection(nested.keys())
            if leaked:
                fail(f"{source} exposes provider-only keys at {path}: {sorted(leaked)}")


def load_openapi() -> dict[str, Any]:
    if not OPENAPI_PATH.exists():
        fail(f"missing OpenAPI contract: {OPENAPI_PATH}")
    with OPENAPI_PATH.open(encoding="utf-8") as handle:
        document = yaml.safe_load(handle)
    if not isinstance(document, dict):
        fail("OpenAPI document must be an object")
    return document


def load_fixtures() -> list[dict[str, Any]]:
    if not FIXTURE_DIR.exists():
        fail(f"missing fixture directory: {FIXTURE_DIR}")
    fixtures: list[dict[str, Any]] = []
    for path in sorted(FIXTURE_DIR.glob("*.json")):
        with path.open(encoding="utf-8") as handle:
            payload = json.load(handle)
        if not isinstance(payload, dict):
            fail(f"{path} must contain a JSON object")
        payload["_sourcePath"] = str(path.relative_to(ROOT))
        fixtures.append(payload)
    if not fixtures:
        fail(f"no identity-saved fixtures found under {FIXTURE_DIR}")
    return fixtures


def fixture_openapi_path(path: str, openapi_paths: dict[str, Any]) -> str:
    prefix = "/api/v1"
    if not path.startswith(prefix):
        fail(f"fixture request path must start with {prefix}: {path}")
    relative_path = path[len(prefix) :] or "/"
    fixture_segments = relative_path.strip("/").split("/")

    for candidate in openapi_paths:
        candidate_segments = candidate.strip("/").split("/")
        if len(candidate_segments) != len(fixture_segments):
            continue
        if all(
            candidate_segment.startswith("{") and candidate_segment.endswith("}")
            or candidate_segment == fixture_segment
            for candidate_segment, fixture_segment in zip(candidate_segments, fixture_segments)
        ):
            return candidate

    fail(f"fixture request path is not declared in OpenAPI: {path}")


def fixture_path_values(path: str, openapi_path: str) -> dict[str, str]:
    prefix = "/api/v1"
    relative_path = path[len(prefix) :] or "/"
    fixture_segments = relative_path.strip("/").split("/")
    candidate_segments = openapi_path.strip("/").split("/")
    values: dict[str, str] = {}
    for candidate_segment, fixture_segment in zip(candidate_segments, fixture_segments):
        if candidate_segment.startswith("{") and candidate_segment.endswith("}"):
            values[candidate_segment[1:-1]] = fixture_segment
    return values


def dereference_parameter(openapi: dict[str, Any], parameter: dict[str, Any], source: str) -> dict[str, Any]:
    parameter_ref = parameter.get("$ref")
    if not isinstance(parameter_ref, str):
        return parameter
    if not parameter_ref.startswith("#/components/parameters/"):
        fail(f"{source} parameter ref must point to components.parameters: {parameter_ref}")
    parameter_name = parameter_ref.rsplit("/", 1)[-1]
    resolved = openapi.get("components", {}).get("parameters", {}).get(parameter_name)
    if not isinstance(resolved, dict):
        fail(f"{source} parameter ref points to missing parameter: {parameter_ref}")
    return resolved


def validate_request_parameters(
    fixture: dict[str, Any],
    openapi: dict[str, Any],
    operation: dict[str, Any],
    openapi_path: str,
    source: str,
) -> None:
    request = fixture["request"]
    response = fixture["response"]
    path = request["path"]
    status = response["status"]
    provided_values = {
        "query": request.get("query", {}),
        "header": request.get("headers", {}),
        "path": fixture_path_values(path, openapi_path),
    }
    for location, values in provided_values.items():
        if not isinstance(values, dict):
            fail(f"{source} request {location} values must be an object")

    for raw_parameter in operation.get("parameters", []):
        if not isinstance(raw_parameter, dict):
            fail(f"{source} operation parameter must be an object")
        parameter = dereference_parameter(openapi, raw_parameter, source)
        name = parameter.get("name")
        location = parameter.get("in")
        if not isinstance(name, str) or location not in provided_values:
            fail(f"{source} operation parameter has invalid name or location")
        value_present = name in provided_values[location]
        if parameter.get("required") is True and not value_present and status < 400:
            fail(f"{source} missing required request {location} parameter {name!r}")
        if not value_present:
            continue
        schema = parameter.get("schema")
        if not isinstance(schema, dict):
            fail(f"{source} request parameter {name!r} has no schema")
        parameter_schema = {
            "$schema": "https://json-schema.org/draft/2020-12/schema",
            **rewrite_openapi_refs(schema),
            "$defs": {
                schema_name: rewrite_openapi_refs(component_schema)
                for schema_name, component_schema in openapi.get("components", {}).get("schemas", {}).items()
            },
        }
        validator = Draft202012Validator(parameter_schema, format_checker=FormatChecker())
        errors = sorted(validator.iter_errors(provided_values[location][name]), key=lambda error: list(error.path))
        if errors:
            fail(
                f"{source} request {location} parameter {name!r} does not match OpenAPI schema: "
                f"{errors[0].message}"
            )


def dereference_response(openapi: dict[str, Any], response_spec: dict[str, Any], source: str) -> dict[str, Any]:
    response_ref = response_spec.get("$ref")
    if not isinstance(response_ref, str):
        return response_spec
    if not response_ref.startswith("#/components/responses/"):
        fail(f"{source} response ref must point to components.responses: {response_ref}")
    response_name = response_ref.rsplit("/", 1)[-1]
    resolved = openapi.get("components", {}).get("responses", {}).get(response_name)
    if not isinstance(resolved, dict):
        fail(f"{source} response ref points to missing response: {response_ref}")
    return resolved


def operation_response_schema_name(
    fixture: dict[str, Any],
    openapi: dict[str, Any],
    openapi_paths: dict[str, Any],
    source: str,
) -> str | None:
    request = fixture.get("request")
    response = fixture.get("response")
    if not isinstance(request, dict) or not isinstance(response, dict):
        return None

    method = request.get("method")
    path = request.get("path")
    status = response.get("status")
    if not isinstance(method, str) or not isinstance(path, str) or not isinstance(status, int):
        return None

    openapi_path = fixture_openapi_path(path, openapi_paths)
    operation = openapi_paths[openapi_path].get(method.lower())
    if not isinstance(operation, dict):
        fail(f"{source} method {method} is not declared for {openapi_path}")
    validate_request_parameters(fixture, openapi, operation, openapi_path, source)

    response_spec = operation.get("responses", {}).get(str(status))
    if not isinstance(response_spec, dict):
        fail(f"{source} status {status} is not declared for {method} {openapi_path}")
    response_spec = dereference_response(openapi, response_spec, source)

    content = response_spec.get("content", {})
    schema = content.get("application/json", {}).get("schema", {})
    ref = schema.get("$ref") if isinstance(schema, dict) else None
    if not isinstance(ref, str):
        if status in {204, 302, 303}:
            return None
        fail(f"{source} response {status} for {method} {openapi_path} has no JSON schema ref")
    if not ref.startswith("#/components/schemas/"):
        fail(f"{source} response schema ref must point to components.schemas: {ref}")
    return ref.rsplit("/", 1)[-1]


def rewrite_openapi_refs(value: Any) -> Any:
    if isinstance(value, dict):
        rewritten: dict[str, Any] = {}
        for key, nested in value.items():
            if key == "$ref" and isinstance(nested, str) and nested.startswith("#/components/schemas/"):
                rewritten[key] = nested.replace("#/components/schemas/", "#/$defs/")
            else:
                rewritten[key] = rewrite_openapi_refs(nested)
        return rewritten
    if isinstance(value, list):
        return [rewrite_openapi_refs(item) for item in value]
    return value


def fixture_response_schema(schema_name: str, schemas: dict[str, Any]) -> dict[str, Any]:
    return {
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        "$ref": f"#/$defs/{schema_name}",
        "$defs": {name: rewrite_openapi_refs(schema) for name, schema in schemas.items()},
    }


def validate_fixture_body_json_schema(
    body: Any,
    schema_name: str,
    schemas: dict[str, Any],
    source: str,
) -> None:
    schema = fixture_response_schema(schema_name, schemas)
    Draft202012Validator.check_schema(schema)
    validator = Draft202012Validator(schema, format_checker=FormatChecker())
    errors = sorted(validator.iter_errors(body), key=lambda error: list(error.path))
    if errors:
        error = errors[0]
        location = "$" + "".join(f"[{part!r}]" if isinstance(part, int) else f".{part}" for part in error.path)
        fail(f"{source} does not match JSON Schema {schema_name} at {location}: {error.message}")


def validate_openapi_contract(openapi: dict[str, Any]) -> None:
    validate(openapi)
    if openapi.get("openapi") != "3.1.0":
        fail("Identity Saved OpenAPI must use OpenAPI 3.1.0")
    if openapi.get("servers") != [{"url": "/api/v1"}]:
        fail("Identity Saved OpenAPI must use /api/v1 server")

    paths = openapi.get("paths")
    if not isinstance(paths, dict):
        fail("OpenAPI paths must be an object")
    missing_paths = REQUIRED_PATHS.difference(paths.keys())
    if missing_paths:
        fail(f"missing required paths: {sorted(missing_paths)}")

    schemas = openapi.get("components", {}).get("schemas", {})
    if not isinstance(schemas, dict):
        fail("OpenAPI components.schemas must be an object")
    missing_schemas = REQUIRED_SCHEMAS.difference(schemas.keys())
    if missing_schemas:
        fail(f"missing required schemas: {sorted(missing_schemas)}")

    assert_no_forbidden_public_keys(openapi, str(OPENAPI_PATH.relative_to(ROOT)))


def validate_fixtures(openapi: dict[str, Any], fixtures: list[dict[str, Any]]) -> None:
    names = {fixture.get("name") for fixture in fixtures}
    missing = REQUIRED_FIXTURES.difference(names)
    if missing:
        fail(f"missing required fixture names: {sorted(missing)}")

    schemas = openapi["components"]["schemas"]
    known_schemas = set(schemas.keys())
    for fixture in fixtures:
        source = fixture["_sourcePath"]
        assert_no_forbidden_public_keys(fixture, source)
        for required in ("name", "description", "request", "response"):
            if required not in fixture:
                fail(f"{source} missing {required}")

        response = fixture["response"]
        if not isinstance(response, dict):
            fail(f"{source} response must be an object")
        schema_name = response.get("schema")
        if schema_name is not None and schema_name not in known_schemas:
            fail(f"{source} references unknown schema {schema_name!r}")
        expected_schema_name = operation_response_schema_name(fixture, openapi, openapi["paths"], source)
        if expected_schema_name is not None and schema_name != expected_schema_name:
            fail(
                f"{source} schema {schema_name!r} does not match OpenAPI response schema "
                f"{expected_schema_name!r}"
            )
        if expected_schema_name is None and schema_name is not None:
            fail(f"{source} response should not declare JSON schema for redirect or empty status")
        if "body" in response:
            if not isinstance(schema_name, str):
                fail(f"{source} response body requires schema")
            validate_fixture_body_json_schema(response["body"], schema_name, schemas, source)


def main() -> None:
    openapi = load_openapi()
    validate_openapi_contract(openapi)
    fixtures = load_fixtures()
    validate_fixtures(openapi, fixtures)
    print(f"validated identity-saved contract: {OPENAPI_PATH.relative_to(ROOT)} and {len(fixtures)} fixtures")


if __name__ == "__main__":
    main()

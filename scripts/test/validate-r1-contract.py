#!/usr/bin/env python3
"""Validate the R1 OpenAPI contract and machine-readable fixtures."""

from __future__ import annotations

import json
import warnings
from pathlib import Path
from typing import Any

import yaml
from jsonschema import Draft202012Validator, FormatChecker

warnings.filterwarnings(
    "ignore",
    message="urllib3 v2 only supports OpenSSL 1.1.1+",
    category=Warning,
)

from openapi_spec_validator import validate


ROOT = Path(__file__).resolve().parents[2]
OPENAPI_PATH = ROOT / "docs/contracts/openapi/r1.openapi.yaml"
FIXTURE_DIR = ROOT / "docs/contracts/fixtures/r1"

FORBIDDEN_PUBLIC_KEYS = {"contentId", "contentid", "pageNo", "page_no", "key", "serviceKey"}
REQUIRED_PATHS = {
    "/hanoks",
    "/hanoks/monthly",
    "/hanoks/{placeId}",
    "/places/{placeId}",
    "/saved-resources/places/{placeId}",
    "/saved-resources",
    "/me/timeline",
}
REQUIRED_SCHEMAS = {
    "HanokListResponse",
    "HanokDetail",
    "MonthlyHanokEditionResponse",
    "CanonicalPlaceDetail",
    "SavedPlaceState",
    "SavedResourcePage",
    "MemberTimeline",
    "Error",
}
REQUIRED_FIXTURES = {
    "hanok-list-normal",
    "hanok-list-empty",
    "hanok-list-cursor",
    "hanok-monthly-normal",
    "hanok-detail-normal",
    "place-detail-normal",
    "saved-place-auth-required",
    "saved-place-normal",
    "saved-resources-cursor",
    "timeline-normal",
    "timeline-unavailable",
    "place-not-found",
    "service-unavailable",
}


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


def validate_openapi_standard(openapi: dict[str, Any]) -> None:
    validate(openapi)


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
        fail(f"no R1 fixtures found under {FIXTURE_DIR}")
    return fixtures


def response_schema_names(openapi: dict[str, Any]) -> set[str]:
    names: set[str] = set()
    for path_item in openapi.get("paths", {}).values():
        if not isinstance(path_item, dict):
            continue
        for operation in path_item.values():
            if not isinstance(operation, dict):
                continue
            for response in operation.get("responses", {}).values():
                content = response.get("content", {}) if isinstance(response, dict) else {}
                schema = content.get("application/json", {}).get("schema", {})
                ref = schema.get("$ref") if isinstance(schema, dict) else None
                if isinstance(ref, str) and ref.startswith("#/components/schemas/"):
                    names.add(ref.rsplit("/", 1)[-1])
    return names


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

    responses = operation.get("responses", {})
    response_spec = responses.get(str(status))
    if not isinstance(response_spec, dict):
        fail(f"{source} status {status} is not declared for {method} {openapi_path}")
    response_ref = response_spec.get("$ref")
    if isinstance(response_ref, str):
        if not response_ref.startswith("#/components/responses/"):
            fail(f"{source} response ref must point to components.responses: {response_ref}")
        response_name = response_ref.rsplit("/", 1)[-1]
        response_spec = openapi.get("components", {}).get("responses", {}).get(response_name)
        if not isinstance(response_spec, dict):
            fail(f"{source} response ref points to missing response: {response_ref}")

    content = response_spec.get("content", {})
    schema = content.get("application/json", {}).get("schema", {})
    ref = schema.get("$ref") if isinstance(schema, dict) else None
    if not isinstance(ref, str):
        if status == 204:
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


def validate_openapi(openapi: dict[str, Any]) -> set[str]:
    if openapi.get("openapi") != "3.1.0":
        fail("R1 OpenAPI must use OpenAPI 3.1.0")
    if openapi.get("servers") != [{"url": "/api/v1"}]:
        fail("R1 OpenAPI must use /api/v1 server")

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
    return set(schemas.keys()).union(response_schema_names(openapi))


def validate_fixtures(
    fixtures: list[dict[str, Any]],
    known_schemas: set[str],
    component_schemas: dict[str, Any],
    openapi: dict[str, Any],
    openapi_paths: dict[str, Any],
) -> None:
    names = {fixture.get("name") for fixture in fixtures}
    missing = REQUIRED_FIXTURES.difference(names)
    if missing:
        fail(f"missing required fixture names: {sorted(missing)}")

    observed_place_ids: dict[str, str] = {}
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
        if schema_name not in known_schemas:
            fail(f"{source} references unknown schema {schema_name!r}")
        expected_schema_name = operation_response_schema_name(fixture, openapi, openapi_paths, source)
        if expected_schema_name is not None and schema_name != expected_schema_name:
            fail(
                f"{source} schema {schema_name!r} does not match OpenAPI response schema "
                f"{expected_schema_name!r}"
            )
        if "body" in response:
            if not isinstance(component_schemas.get(schema_name), dict):
                fail(f"{source} references non-object schema {schema_name!r}")
            validate_fixture_body_json_schema(response["body"], schema_name, component_schemas, source)
            body = response["body"]
            if schema_name == "HanokDetail" and isinstance(body, dict):
                ids = {
                    "placeId": body.get("placeId"),
                    "mapCard.placeId": body.get("mapCard", {}).get("placeId") if isinstance(body.get("mapCard"), dict) else None,
                    "odiiLinkedCard.placeId": body.get("odiiLinkedCard", {}).get("placeId")
                    if isinstance(body.get("odiiLinkedCard"), dict)
                    else body.get("placeId"),
                }
                if len(set(ids.values())) != 1:
                    fail(f"{source} response body must use one canonical placeId, got {ids}")
                observed_place_ids[fixture["name"]] = str(body["placeId"])
            if schema_name == "CanonicalPlaceDetail" and isinstance(body, dict):
                observed_place_ids[fixture["name"]] = str(body["placeId"])

        shared = fixture.get("sharedPlaceAssertion")
        if isinstance(shared, dict):
            place_id = shared.get("placeId")
            for surface in ("hanokCardPlaceId", "mapCardPlaceId", "odiiLinkedCardPlaceId"):
                if shared.get(surface) != place_id:
                    fail(f"{source} {surface} must equal shared placeId {place_id!r}")
            if isinstance(place_id, str):
                observed_place_ids[fixture["name"]] = place_id

    if "hanok-detail-normal" in observed_place_ids and "place-detail-normal" in observed_place_ids:
        if observed_place_ids["hanok-detail-normal"] != observed_place_ids["place-detail-normal"]:
            fail("hanok and place detail fixtures must use the same canonical placeId")


def main() -> None:
    openapi = load_openapi()
    validate_openapi_standard(openapi)
    known_schemas = validate_openapi(openapi)
    fixtures = load_fixtures()
    component_schemas = openapi["components"]["schemas"]
    validate_fixtures(fixtures, known_schemas, component_schemas, openapi, openapi["paths"])
    print(f"validated R1 contract: {OPENAPI_PATH.relative_to(ROOT)} and {len(fixtures)} fixtures")


if __name__ == "__main__":
    main()

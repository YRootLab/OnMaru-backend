#!/usr/bin/env python3
"""Validate repository contract documents and machine-readable fixtures."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

import yaml
from jsonschema import Draft202012Validator, FormatChecker
from openapi_spec_validator import validate


def fail(message: str) -> None:
    raise AssertionError(message)


def load_document(path: Path) -> Any:
    with path.open(encoding="utf-8") as handle:
        if path.suffix in {".yaml", ".yml"}:
            return yaml.safe_load(handle)
        return json.load(handle)


def iter_contract_files(root: Path, relative_dir: str, suffixes: tuple[str, ...]) -> list[Path]:
    directory = root / relative_dir
    if not directory.exists():
        return []
    return sorted(path for path in directory.rglob("*") if path.suffix in suffixes)


def schema_location(error_path: Any) -> str:
    location = "$"
    for part in error_path:
        if isinstance(part, int):
            location += f"[{part}]"
        else:
            location += f".{part}"
    return location


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


def schema_document(schema_name: str, component_schemas: dict[str, Any]) -> dict[str, Any]:
    return {
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        "$ref": f"#/$defs/{schema_name}",
        "$defs": {
            name: rewrite_openapi_refs(schema)
            for name, schema in component_schemas.items()
            if isinstance(schema, dict)
        },
    }


def openapi_server_prefixes(document: dict[str, Any]) -> list[str]:
    servers = document.get("servers")
    if not isinstance(servers, list):
        return [""]
    prefixes = [server.get("url", "") for server in servers if isinstance(server, dict)]
    return [prefix for prefix in prefixes if isinstance(prefix, str)] or [""]


def strip_server_prefix(fixture_path: str, document: dict[str, Any]) -> str:
    for prefix in sorted(openapi_server_prefixes(document), key=len, reverse=True):
        if prefix and fixture_path.startswith(prefix):
            return fixture_path[len(prefix) :] or "/"
    return fixture_path


def path_matches(openapi_path: str, fixture_path: str, document: dict[str, Any]) -> bool:
    relative_fixture_path = strip_server_prefix(fixture_path, document)
    fixture_segments = relative_fixture_path.strip("/").split("/")
    candidate_segments = openapi_path.strip("/").split("/")
    if len(candidate_segments) != len(fixture_segments):
        return False
    return all(
        candidate.startswith("{") and candidate.endswith("}") or candidate == fixture
        for candidate, fixture in zip(candidate_segments, fixture_segments)
    )


def path_specificity(openapi_path: str) -> tuple[int, int]:
    segments = openapi_path.strip("/").split("/")
    static_segments = sum(
        1 for segment in segments if not (segment.startswith("{") and segment.endswith("}"))
    )
    return static_segments, len(segments)


def fixture_contract_group(root: Path, fixture_path: Path) -> str | None:
    relative = fixture_path.relative_to(root)
    parts = relative.parts
    if len(parts) >= 4 and parts[:3] == ("docs", "contracts", "fixtures"):
        return parts[3]
    return None


def contract_matches_fixture_group(contract: dict[str, Any], group: str | None) -> bool:
    if group is None:
        return False
    return contract["path"].stem.startswith(group)


def collect_openapi_contracts(root: Path) -> list[dict[str, Any]]:
    contracts: list[dict[str, Any]] = []
    for path in iter_contract_files(root, "docs/contracts/openapi", (".json", ".yaml", ".yml")):
        document = load_document(path)
        if not isinstance(document, dict):
            fail(f"{path.relative_to(root)} must contain an OpenAPI object")
        validate(document)
        components = document.get("components", {})
        component_schemas = components.get("schemas", {}) if isinstance(components, dict) else {}
        if not isinstance(component_schemas, dict):
            continue
        contracts.append(
            {
                "path": path,
                "document": document,
                "schemas": {
                    name: schema_document(name, component_schemas)
                    for name, schema in component_schemas.items()
                    if isinstance(schema, dict)
                },
            }
        )
    return contracts


def validate_required_openapi_contracts(root: Path) -> None:
    manifest = root / "docs/contracts/required-openapi-files.txt"
    if not manifest.exists():
        return
    directory = root / "docs/contracts/openapi"
    for line in manifest.read_text(encoding="utf-8").splitlines():
        name = line.strip()
        if not name or name.startswith("#"):
            continue
        path = directory / name
        if not path.is_file():
            fail(f"required OpenAPI contract is missing: docs/contracts/openapi/{name}")
        if name == "hanok-stamps.openapi.yaml":
            validate_stamp_openapi(path, root)


def validate_stamp_openapi(path: Path, root: Path) -> None:
    document = load_document(path)
    if not isinstance(document, dict):
        fail(f"{path.relative_to(root)} must contain an OpenAPI object")
    required_operations = {
        "/stamps": {"get": {"200"}},
        "/me/stamp-book": {"get": {"200", "401", "503"}},
        "/places/{placeId}/check-ins": {
            "post": {"200", "201", "400", "401", "403", "404", "409", "422", "429", "503"}
        },
    }
    paths = document.get("paths", {})
    for route, operations in required_operations.items():
        path_item = paths.get(route) if isinstance(paths, dict) else None
        if not isinstance(path_item, dict):
            fail(f"{path.relative_to(root)} is missing required path {route}")
        for method, statuses in operations.items():
            operation = path_item.get(method)
            if not isinstance(operation, dict):
                fail(f"{path.relative_to(root)} is missing operation {method.upper()} {route}")
            responses = operation.get("responses", {})
            missing = statuses - set(responses) if isinstance(responses, dict) else statuses
            if missing:
                fail(f"{path.relative_to(root)} {method.upper()} {route} is missing responses {sorted(missing)}")
    schemas = document.get("components", {}).get("schemas", {})
    for name in ("StampCatalogResponse", "StampBookResponse", "CheckInRequest", "CheckInResponse", "ApiError"):
        if not isinstance(schemas, dict) or name not in schemas:
            fail(f"{path.relative_to(root)} is missing schema {name}")


def validate_standalone_json_schemas(root: Path, schemas: dict[str, Any]) -> None:
    for path in iter_contract_files(root, "docs/contracts/schemas", (".json",)):
        document = load_document(path)
        if not isinstance(document, dict):
            fail(f"{path.relative_to(root)} must contain a JSON Schema object")
        Draft202012Validator.check_schema(document)
        schemas[path.stem] = document
        title = document.get("title")
        if isinstance(title, str):
            schemas[title] = document


def resolve_response_ref(openapi: dict[str, Any], response_spec: dict[str, Any]) -> dict[str, Any]:
    response_ref = response_spec.get("$ref")
    if not isinstance(response_ref, str):
        return response_spec
    if not response_ref.startswith("#/components/responses/"):
        fail(f"response ref must point to components.responses: {response_ref}")
    response_name = response_ref.rsplit("/", 1)[-1]
    resolved = openapi.get("components", {}).get("responses", {}).get(response_name)
    if not isinstance(resolved, dict):
        fail(f"response ref points to missing response: {response_ref}")
    return resolved


def response_schema_name(
    root: Path,
    fixture_path: Path,
    fixture: dict[str, Any],
    contracts: list[dict[str, Any]],
) -> tuple[str | None, dict[str, Any] | None]:
    request = fixture.get("request")
    response = fixture.get("response")
    if not isinstance(request, dict) or not isinstance(response, dict):
        return None, None

    method = request.get("method")
    path = request.get("path")
    status = response.get("status")
    if not isinstance(method, str) or not isinstance(path, str) or not isinstance(status, int):
        return None, None

    matches: list[tuple[dict[str, Any], str, tuple[int, int]]] = []
    for contract in contracts:
        openapi = contract["document"]
        paths = openapi.get("paths", {})
        if not isinstance(paths, dict):
            continue
        for openapi_path in paths:
            if path_matches(openapi_path, path, openapi):
                matches.append((contract, openapi_path, path_specificity(openapi_path)))

    source = fixture_path.relative_to(root)
    if not matches:
        fail(f"{source} request path is not declared in OpenAPI: {path}")
    best_specificity = max(match[2] for match in matches)
    matches = [match for match in matches if match[2] == best_specificity]
    fixture_group = fixture_contract_group(root, fixture_path)
    grouped_matches = [
        match for match in matches if contract_matches_fixture_group(match[0], fixture_group)
    ]
    if grouped_matches:
        matches = grouped_matches
    if len(matches) > 1:
        matched_paths = [f"{match[0]['path'].relative_to(root)}:{match[1]}" for match in matches]
        fail(f"{source} request path is ambiguous across OpenAPI contracts: {matched_paths}")

    contract, openapi_path, _ = matches[0]
    openapi = contract["document"]
    operation = openapi["paths"][openapi_path].get(method.lower())
    if not isinstance(operation, dict):
        fail(f"{source} method {method} is not declared for {openapi_path}")
    response_spec = operation.get("responses", {}).get(str(status))
    if not isinstance(response_spec, dict):
        fail(f"{source} status {status} is not declared for {method} {openapi_path}")
    response_spec = resolve_response_ref(openapi, response_spec)
    schema = response_spec.get("content", {}).get("application/json", {}).get("schema", {})
    ref = schema.get("$ref") if isinstance(schema, dict) else None
    if not isinstance(ref, str):
        if status == 204:
            return None, contract
        fail(f"{source} response {status} for {method} {openapi_path} has no JSON schema ref")
    if not ref.startswith("#/components/schemas/"):
        fail(f"{source} response schema ref must point to components.schemas: {ref}")
    return ref.rsplit("/", 1)[-1], contract


def validate_fixture_response(
    root: Path,
    fixture_path: Path,
    fixture: dict[str, Any],
    contracts: list[dict[str, Any]],
    standalone_schemas: dict[str, Any],
) -> None:
    response = fixture.get("response")
    if not isinstance(response, dict):
        return
    schema_name = response.get("schema")
    if not isinstance(schema_name, str):
        return

    expected_schema_name, contract = response_schema_name(root, fixture_path, fixture, contracts)
    if expected_schema_name is not None and schema_name != expected_schema_name:
        fail(
            f"{fixture_path.relative_to(root)} schema {schema_name!r} does not match "
            f"OpenAPI response schema {expected_schema_name!r}"
        )

    contract_schemas = contract["schemas"] if contract is not None else {}
    schema = contract_schemas.get(schema_name) or standalone_schemas.get(schema_name)
    if schema is None:
        fail(f"{fixture_path.relative_to(root)} references unknown schema {schema_name!r}")
    if "body" not in response:
        return

    Draft202012Validator.check_schema(schema)
    validator = Draft202012Validator(schema, format_checker=FormatChecker())
    errors = sorted(validator.iter_errors(response["body"]), key=lambda error: list(error.path))
    if errors:
        error = errors[0]
        fail(
            f"{fixture_path.relative_to(root)} does not match JSON Schema {schema_name} "
            f"at {schema_location(error.path)}: {error.message}"
        )


def validate_fixtures(
    root: Path,
    contracts: list[dict[str, Any]],
    standalone_schemas: dict[str, Any],
) -> None:
    for path in iter_contract_files(root, "docs/contracts/fixtures", (".json",)):
        document = load_document(path)
        if not isinstance(document, dict):
            fail(f"{path.relative_to(root)} must contain a JSON object")
        validate_fixture_response(root, path, document, contracts, standalone_schemas)


def validate_contracts(root: Path) -> None:
    validate_required_openapi_contracts(root)
    contracts = collect_openapi_contracts(root)
    standalone_schemas: dict[str, Any] = {}
    validate_standalone_json_schemas(root, standalone_schemas)
    validate_fixtures(root, contracts, standalone_schemas)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    validate_contracts(args.root.resolve())
    print("validated contract documents and fixtures")


if __name__ == "__main__":
    main()

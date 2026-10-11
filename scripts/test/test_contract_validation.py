from __future__ import annotations

import importlib.util
import json
import os
import shutil
import subprocess
from pathlib import Path

import pytest
import yaml


ROOT = Path(__file__).resolve().parents[2]
VALIDATOR_PATH = ROOT / "scripts/validate_contracts.py"


def load_validator():
    spec = importlib.util.spec_from_file_location("validate_contracts", VALIDATOR_PATH)
    if spec is None or spec.loader is None:
        raise AssertionError("could not load contract validator module")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def write_json(path: Path, payload: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2), encoding="utf-8")


def write_yaml(path: Path, payload: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(yaml.safe_dump(payload, sort_keys=False), encoding="utf-8")


def minimal_openapi() -> dict[str, object]:
    return {
        "openapi": "3.1.0",
        "info": {"title": "Fixture API", "version": "1.0.0"},
        "servers": [{"url": "/api/v1"}],
        "paths": {
            "/greetings": {
                "get": {
                    "responses": {
                        "200": {
                            "description": "ok",
                            "content": {
                                "application/json": {
                                    "schema": {"$ref": "#/components/schemas/Greeting"}
                                }
                            },
                        }
                    }
                }
            }
        },
        "components": {
            "schemas": {
                "Greeting": {
                    "type": "object",
                    "additionalProperties": False,
                    "required": ["message"],
                    "properties": {"message": {"type": "string"}},
                }
            }
        },
    }


def alternate_openapi_with_error_schema() -> dict[str, object]:
    return {
        "openapi": "3.1.0",
        "info": {"title": "Alternate API", "version": "1.0.0"},
        "servers": [{"url": "/api/v1"}],
        "paths": {
            "/alternate-errors": {
                "get": {
                    "responses": {
                        "200": {
                            "description": "ok",
                            "content": {
                                "application/json": {
                                    "schema": {"$ref": "#/components/schemas/Error"}
                                }
                            },
                        }
                    }
                }
            }
        },
        "components": {
            "schemas": {
                "Error": {
                    "type": "object",
                    "additionalProperties": False,
                    "required": ["errorCode"],
                    "properties": {"errorCode": {"type": "string"}},
                }
            }
        },
    }


def create_contract_tree(tmp_path: Path) -> Path:
    root = tmp_path / "repo"
    write_yaml(root / "docs/contracts/openapi/greeting.openapi.yaml", minimal_openapi())
    write_json(
        root / "docs/contracts/fixtures/greeting-normal.json",
        {
            "name": "greeting-normal",
            "request": {"method": "GET", "path": "/api/v1/greetings"},
            "response": {
                "status": 200,
                "schema": "Greeting",
                "body": {"message": "hello"},
            },
        },
    )
    write_json(
        root / "docs/database/azimutt/onmaru-schema.azimutt.sql",
        {"artifact": "current"},
    )
    return root


def test_contract_validator_accepts_valid_contract_tree(tmp_path: Path) -> None:
    validator = load_validator()
    root = create_contract_tree(tmp_path)

    validator.validate_contracts(root)


def test_contract_validator_requires_manifested_stamp_openapi(tmp_path: Path) -> None:
    validator = load_validator()
    root = create_contract_tree(tmp_path)
    manifest = root / "docs/contracts/required-openapi-files.txt"
    manifest.write_text("hanok-stamps.openapi.yaml\n", encoding="utf-8")

    with pytest.raises(AssertionError, match="required OpenAPI contract is missing"):
        validator.validate_contracts(root)


def test_repository_stamp_openapi_declares_required_ranking_contract() -> None:
    validator = load_validator()

    validator.validate_stamp_openapi(
        ROOT / "docs/contracts/openapi/hanok-stamps.openapi.yaml", ROOT
    )


def test_contract_validator_rejects_fixture_body_that_does_not_match_schema(tmp_path: Path) -> None:
    validator = load_validator()
    root = create_contract_tree(tmp_path)
    write_json(
        root / "docs/contracts/fixtures/greeting-normal.json",
        {
            "name": "greeting-normal",
            "request": {"method": "GET", "path": "/api/v1/greetings"},
            "response": {
                "status": 200,
                "schema": "Greeting",
                "body": {"unexpected": "field"},
            },
        },
    )

    with pytest.raises(AssertionError, match="does not match JSON Schema Greeting"):
        validator.validate_contracts(root)


def test_contract_validator_uses_openapi_file_that_declares_fixture_path(tmp_path: Path) -> None:
    validator = load_validator()
    root = create_contract_tree(tmp_path)
    greeting = minimal_openapi()
    greeting["components"]["schemas"]["Error"] = {
        "type": "object",
        "additionalProperties": False,
        "required": ["message"],
        "properties": {"message": {"type": "string"}},
    }
    greeting["paths"]["/errors"] = {
        "get": {
            "responses": {
                "200": {
                    "description": "ok",
                    "content": {
                        "application/json": {"schema": {"$ref": "#/components/schemas/Error"}}
                    },
                }
            }
        }
    }
    write_yaml(root / "docs/contracts/openapi/greeting.openapi.yaml", greeting)
    write_yaml(root / "docs/contracts/openapi/z-alternate.openapi.yaml", alternate_openapi_with_error_schema())
    write_json(
        root / "docs/contracts/fixtures/error-normal.json",
        {
            "name": "error-normal",
            "request": {"method": "GET", "path": "/api/v1/errors"},
            "response": {
                "status": 200,
                "schema": "Error",
                "body": {"message": "matched by request path"},
            },
        },
    )

    validator.validate_contracts(root)


def test_contract_validator_rejects_fixture_schema_that_differs_from_openapi_response(
    tmp_path: Path,
) -> None:
    validator = load_validator()
    root = create_contract_tree(tmp_path)
    openapi = minimal_openapi()
    openapi["components"]["schemas"]["Farewell"] = {
        "type": "object",
        "additionalProperties": False,
        "required": ["goodbye"],
        "properties": {"goodbye": {"type": "string"}},
    }
    write_yaml(root / "docs/contracts/openapi/greeting.openapi.yaml", openapi)
    write_json(
        root / "docs/contracts/fixtures/greeting-normal.json",
        {
            "name": "greeting-normal",
            "request": {"method": "GET", "path": "/api/v1/greetings"},
            "response": {
                "status": 200,
                "schema": "Farewell",
                "body": {"goodbye": "bye"},
            },
        },
    )

    with pytest.raises(AssertionError, match="schema 'Farewell' does not match OpenAPI response schema 'Greeting'"):
        validator.validate_contracts(root)


def test_contract_validator_prefers_static_path_over_parameter_path(tmp_path: Path) -> None:
    validator = load_validator()
    root = create_contract_tree(tmp_path)
    openapi = minimal_openapi()
    openapi["paths"]["/greetings/monthly"] = {
        "get": {
            "responses": {
                "200": {
                    "description": "ok",
                    "content": {
                        "application/json": {"schema": {"$ref": "#/components/schemas/Greeting"}}
                    },
                }
            }
        }
    }
    openapi["paths"]["/greetings/{greetingId}"] = {
        "get": {
            "parameters": [
                {
                    "name": "greetingId",
                    "in": "path",
                    "required": True,
                    "schema": {"type": "string"},
                }
            ],
            "responses": {
                "200": {
                    "description": "ok",
                    "content": {
                        "application/json": {"schema": {"$ref": "#/components/schemas/Greeting"}}
                    },
                }
            }
        }
    }
    write_yaml(root / "docs/contracts/openapi/greeting.openapi.yaml", openapi)
    write_json(
        root / "docs/contracts/fixtures/greeting-normal.json",
        {
            "name": "greeting-normal",
            "request": {"method": "GET", "path": "/api/v1/greetings/monthly"},
            "response": {
                "status": 200,
                "schema": "Greeting",
                "body": {"message": "static path wins"},
            },
        },
    )

    validator.validate_contracts(root)


def test_contract_validator_uses_fixture_group_to_choose_duplicate_openapi_path(
    tmp_path: Path,
) -> None:
    validator = load_validator()
    root = create_contract_tree(tmp_path)
    legacy = minimal_openapi()
    legacy["paths"] = {
        "/regions": {
            "get": {
                "responses": {
                    "200": {
                        "description": "legacy",
                        "content": {
                            "application/json": {"schema": {"$ref": "#/components/schemas/Greeting"}}
                        },
                    }
                }
            }
        }
    }
    r2 = minimal_openapi()
    r2["paths"] = legacy["paths"]
    r2["components"]["schemas"]["Greeting"] = {
        "type": "object",
        "additionalProperties": False,
        "required": ["message", "schemaVersion"],
        "properties": {
            "message": {"type": "string"},
            "schemaVersion": {"const": "1.2"},
        },
    }
    write_yaml(root / "docs/contracts/openapi/legacy.openapi.yaml", legacy)
    write_yaml(root / "docs/contracts/openapi/r2-map.openapi.yaml", r2)
    write_json(
        root / "docs/contracts/fixtures/r2/region-count-normal.json",
        {
            "name": "region-count-normal",
            "request": {"method": "GET", "path": "/api/v1/regions"},
            "response": {
                "status": 200,
                "schema": "Greeting",
                "body": {"schemaVersion": "1.2", "message": "r2 contract"},
            },
        },
    )

    validator.validate_contracts(root)


def test_verify_contracts_rejects_stale_generated_artifact(tmp_path: Path) -> None:
    root = create_contract_tree(tmp_path)
    generated_dir = tmp_path / "generated"
    generated_dir.mkdir()
    for name in (
        "onmaru-schema.azimutt.sql",
        "onmaru-schema.azimutt-strict.sql",
        "onmaru-schema.postgres.sql",
    ):
        (root / "docs/database/azimutt" / name).parent.mkdir(parents=True, exist_ok=True)
        (root / "docs/database/azimutt" / name).write_text("expected\n", encoding="utf-8")
        (generated_dir / name).write_text("generated\n", encoding="utf-8")

    result = subprocess.run(
        [
            "bash",
            str(ROOT / "scripts/verify-contracts"),
            "--root",
            str(root),
            "--artifacts-only",
            "--generated-output-dir",
            str(generated_dir),
        ],
        cwd=ROOT,
        text=True,
        capture_output=True,
        check=False,
    )

    assert result.returncode != 0
    assert "onmaru-schema.azimutt.sql" in (result.stdout + result.stderr)


def test_verify_contracts_supports_negative_fixture_mode(tmp_path: Path) -> None:
    root = create_contract_tree(tmp_path)
    write_json(
        root / "docs/contracts/fixtures/greeting-broken.json",
        {
            "name": "greeting-broken",
            "request": {"method": "GET", "path": "/api/v1/greetings"},
            "response": {
                "status": 200,
                "schema": "Greeting",
                "body": {},
            },
        },
    )

    result = subprocess.run(
        ["bash", str(ROOT / "scripts/verify-contracts"), "--root", str(root), "--contracts-only"],
        cwd=ROOT,
        text=True,
        capture_output=True,
        check=False,
    )

    assert result.returncode != 0
    assert "greeting-broken.json does not match JSON Schema Greeting" in (
        result.stdout + result.stderr
    )


def test_dbml2sql_toolchain_locks_compatible_transitive_dependencies() -> None:
    toolchain = ROOT / "scripts/dbml-toolchain"
    assert (toolchain / "package.json").is_file(), "DBML toolchain needs a repository-owned manifest"
    assert (toolchain / "package-lock.json").is_file(), "DBML transitive dependencies need a committed lock"
    manifest = json.loads((toolchain / "package.json").read_text(encoding="utf-8"))
    lock = json.loads((toolchain / "package-lock.json").read_text(encoding="utf-8"))

    assert manifest["private"] is True
    assert manifest["dependencies"]["@dbml/cli"] == "10.1.1"
    assert manifest["dependencies"]["@types/node"] == "22.20.2"
    assert lock["packages"][""]["dependencies"] == manifest["dependencies"]
    for name in ("core", "parse", "connector"):
        assert manifest["overrides"][f"@dbml/{name}"] == "10.1.1"
    for path, package in lock["packages"].items():
        if not path:
            continue
        assert package["version"]
        assert package["resolved"].startswith("https://registry.npmjs.org/")
        assert package["integrity"].startswith("sha512-")
        if "/@dbml/" in path:
            assert package["version"] == "10.1.1", f"incompatible DBML package: {path}"
    for name in ("cli", "core", "parse", "connector"):
        assert lock["packages"][f"node_modules/@dbml/{name}"]["version"] == "10.1.1"


def test_dbml2sql_wrapper_installs_locked_toolchain_and_forwards_arguments(tmp_path: Path) -> None:
    scripts_dir = tmp_path / "repo with spaces/scripts"
    scripts_dir.mkdir(parents=True)
    wrapper = scripts_dir / "dbml2sql"
    shutil.copy2(ROOT / "scripts/dbml2sql", wrapper)
    toolchain = scripts_dir / "dbml-toolchain"
    toolchain.mkdir()
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir()
    capture_path = tmp_path / "npm-arguments.txt"
    cli_capture_path = tmp_path / "cli-arguments.txt"
    fake_npm = bin_dir / "npm"
    fake_npm.write_text(
        '#!/usr/bin/env bash\n'
        'printf "%s\\n" "$@" >"$NPM_CAPTURE_PATH"\n'
        'printf "install diagnostic\\n"\n'
        'exit "${NPM_EXIT_CODE:-0}"\n',
        encoding="utf-8",
    )
    fake_npm.chmod(0o755)
    local_cli = toolchain / "node_modules/.bin/dbml2sql"
    local_cli.parent.mkdir(parents=True)
    local_cli.write_text(
        '#!/usr/bin/env bash\nprintf "%s\\n" "$@" >"$CLI_CAPTURE_PATH"\nprintf "compiled sql\\n"\n',
        encoding="utf-8",
    )
    local_cli.chmod(0o755)

    env = os.environ.copy()
    env["PATH"] = f"{bin_dir}:{env['PATH']}"
    env["NPM_CAPTURE_PATH"] = str(capture_path)
    env["CLI_CAPTURE_PATH"] = str(cli_capture_path)
    result = subprocess.run(
        [str(wrapper), "schema with spaces.dbml", "--postgres"],
        cwd=tmp_path,
        env=env,
        text=True,
        capture_output=True,
        check=False,
    )

    assert result.returncode == 0
    assert result.stdout == "compiled sql\n"
    assert "install diagnostic" in result.stderr
    assert capture_path.read_text(encoding="utf-8").splitlines() == [
        "ci",
        "--prefix",
        str(toolchain),
        "--ignore-scripts",
        "--no-audit",
        "--no-fund",
    ]
    assert cli_capture_path.read_text(encoding="utf-8").splitlines() == [
        "schema with spaces.dbml",
        "--postgres",
    ]

    cli_capture_path.unlink()
    env["NPM_EXIT_CODE"] = "42"
    result = subprocess.run(
        [str(wrapper), "schema.dbml", "--postgres"],
        cwd=tmp_path,
        env=env,
        text=True,
        capture_output=True,
        check=False,
    )
    assert result.returncode == 42
    assert not cli_capture_path.exists(), "a failed locked install must not execute a stale CLI"

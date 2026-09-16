from __future__ import annotations

import argparse
import json
from collections.abc import Sequence
from pathlib import Path

from .models import EvalReport
from .runner import compare_reports, evaluate_document


def main(arguments: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Run deterministic OnMaru AI evaluations")
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--compare", type=Path)
    parser.add_argument("--schema-output", type=Path)
    options = parser.parse_args(arguments)

    document = json.loads(options.input.read_text(encoding="utf-8"))
    report = evaluate_document(document)
    if options.compare is not None:
        baseline = EvalReport.model_validate_json(options.compare.read_text(encoding="utf-8"))
        report = report.model_copy(
            update={"comparison": compare_reports(report, baseline)}
        )
    payload = report.model_dump(mode="json", by_alias=True)

    options.output.parent.mkdir(parents=True, exist_ok=True)
    options.output.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    if options.schema_output is not None:
        options.schema_output.parent.mkdir(parents=True, exist_ok=True)
        options.schema_output.write_text(
            json.dumps(
                EvalReport.model_json_schema(by_alias=True),
                ensure_ascii=False,
                indent=2,
                sort_keys=True,
            )
            + "\n",
            encoding="utf-8",
        )

    for gate in report.gates:
        state = "PASS" if gate.passed else "FAIL"
        print(f"{state} {gate.name}")
    print(f"report={options.output}")
    return 0 if report.overall_passed else 1


if __name__ == "__main__":
    raise SystemExit(main())

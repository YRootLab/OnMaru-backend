#!/usr/bin/env python3
"""Enqueue an operator-reviewed 100/1000-place cohort on staging only."""

import argparse
import json
import os
import re
import urllib.parse
import urllib.request
import uuid
from pathlib import Path


def validate(rows, stage):
    if stage not in (100, 1000) or len(rows) != stage:
        raise ValueError("exactly 100 or 1000 input rows are required")
    ids = set()
    for row in rows:
        place = str(uuid.UUID(row["placeId"]))
        if place in ids or not row.get("sourceFingerprint") or not isinstance(row.get("inputJson"), str):
            raise ValueError("duplicate place or missing source fingerprint/input JSON")
        json.loads(row["inputJson"])
        ids.add(place)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, help="reviewed JSONL with placeId/sourceFingerprint/inputJson")
    parser.add_argument("--stage", required=True, type=int, choices=(100, 1000))
    parser.add_argument("--reason", required=True)
    parser.add_argument("--base-url", default="https://staging-api.onmaru.site")
    parser.add_argument("--execute", action="store_true", help="perform POST; default validates only")
    parser.add_argument("--output", help="private JSONL receipt path, required with --execute")
    args = parser.parse_args()
    if not re.fullmatch(rf"PILOT_691_{args.stage}_[A-Za-z0-9_]+", args.reason):
        parser.error("reason must isolate the chosen pilot stage")
    url = urllib.parse.urlsplit(args.base_url)
    if url.scheme != "https" or url.hostname != "staging-api.onmaru.site" or url.username or url.password or url.path or url.query or url.fragment:
        parser.error("only the exact staging API origin is allowed")
    rows = [json.loads(line) for line in Path(args.input).read_text(encoding="utf-8").splitlines() if line.strip()]
    validate(rows, args.stage)
    if not args.execute:
        print(json.dumps({"validatedPlaces": len(rows), "reason": args.reason, "posted": False}))
        return 0
    if not args.output:
        parser.error("--output is required with --execute")
    token = os.getenv("ONMARU_STAGING_ADMIN_JWT")
    if not token:
        parser.error("ONMARU_STAGING_ADMIN_JWT is required")
    endpoint = args.base_url + "/api/v1/internal/kcontents/research/admin/jobs"
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, request, fp, code, msg, headers, newurl):
            return None
    opener = urllib.request.build_opener(NoRedirect)
    with Path(args.output).open("x", encoding="utf-8") as receipt:
        for row in rows:
            data = {"placeId": row["placeId"], "reason": args.reason,
                    "sourceFingerprint": row["sourceFingerprint"], "inputJson": row["inputJson"]}
            request = urllib.request.Request(endpoint, data=json.dumps(data).encode(), method="POST",
                                             headers={"Authorization": "Bearer " + token,
                                                      "Content-Type": "application/json"})
            with opener.open(request, timeout=15) as response:
                result = json.loads(response.read(4096))
                receipt.write(json.dumps({"placeId": row["placeId"], "jobId": result["jobId"]}) + "\n")
                receipt.flush()
    print(json.dumps({"posted": len(rows), "receipt": args.output}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

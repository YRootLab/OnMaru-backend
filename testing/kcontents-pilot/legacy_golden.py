#!/usr/bin/env python3
"""Capture and strictly compare read-only legacy staging responses."""

import argparse
import hashlib
import json
import os
import re
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path


def canonical(body):
    return json.dumps(body, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()


def allowed_base(raw):
    parsed = urllib.parse.urlsplit(raw)
    if parsed.scheme == "https" and parsed.hostname == "staging-api.onmaru.site" and not parsed.username:
        return raw.rstrip("/")
    if parsed.scheme == "http" and parsed.hostname in ("127.0.0.1", "localhost") and not parsed.username:
        return raw.rstrip("/")
    raise ValueError("capture is restricted to staging or localhost")


def expand(path):
    def replace(match):
        value = os.getenv(match.group(1))
        if not value or not re.fullmatch(r"[A-Za-z0-9_-]+", value):
            raise ValueError("missing or unsafe path variable: " + match.group(1))
        return value
    result = re.sub(r"\$\{([A-Z0-9_]+)\}", replace, path)
    if not result.startswith("/api/") or ".." in result or urllib.parse.urlsplit(result).netloc:
        raise ValueError("only relative API paths are allowed")
    return result


def capture(base, specification, opener=urllib.request.urlopen):
    base = allowed_base(base)
    result = []
    for item in specification["requests"]:
        path = expand(item["path"])
        headers = {"Accept": "application/json"}
        if item.get("session"):
            cookie = os.getenv("ONMARU_STAGING_SESSION_COOKIE")
            if not cookie:
                raise ValueError("ONMARU_STAGING_SESSION_COOKIE is required")
            headers["Cookie"] = "__Host-onmaru-session=" + cookie
        request = urllib.request.Request(base + path, headers=headers, method="GET")
        try:
            response = opener(request, timeout=15)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            raw = response.read(2_000_001)
            if len(raw) > 2_000_000:
                raise ValueError("legacy response exceeds 2 MB")
            body = json.loads(raw)
            if response.status != item["status"]:
                raise ValueError(f"{item['id']} returned {response.status}, expected {item['status']}")
            row = {"id": item["id"], "path": path, "status": response.status,
                   "sha256": hashlib.sha256(canonical(body)).hexdigest()}
            if not item.get("session"):
                row["body"] = body
            result.append(row)
    return {"kind": "LEGACY_STAGING_GOLDEN", "requests": result}


def compare(before, after):
    if before.get("kind") != "LEGACY_STAGING_GOLDEN" or after.get("kind") != before["kind"]:
        raise ValueError("wrong capture kind")
    old = {row["id"]: row for row in before["requests"]}
    new = {row["id"]: row for row in after["requests"]}
    if old.keys() != new.keys():
        raise ValueError("legacy request sets differ")
    return [key for key in sorted(old) if any(old[key][field] != new[key][field]
                                             for field in ("path", "status", "sha256"))]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    record = sub.add_parser("capture")
    record.add_argument("--base-url", required=True)
    record.add_argument("--manifest", required=True)
    record.add_argument("--output", required=True)
    check = sub.add_parser("compare")
    check.add_argument("--before", required=True)
    check.add_argument("--after", required=True)
    args = parser.parse_args()
    if args.command == "capture":
        spec = json.loads(Path(args.manifest).read_text(encoding="utf-8"))
        output = capture(args.base_url, spec)
        Path(args.output).write_text(json.dumps(output, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        return 0
    differences = compare(json.loads(Path(args.before).read_text(encoding="utf-8")),
                          json.loads(Path(args.after).read_text(encoding="utf-8")))
    print(json.dumps({"equal": not differences, "differences": differences}, ensure_ascii=False))
    return 0 if not differences else 2


if __name__ == "__main__":
    raise SystemExit(main())

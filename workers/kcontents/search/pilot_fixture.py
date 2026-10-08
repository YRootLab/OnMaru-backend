"""Run exactly 100 fixture jobs through a localhost mock of the W5 HTTP API."""

from __future__ import annotations

import json
import tempfile
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from threading import Thread

from .worker import Cache, FixtureProvider, JobApi, Worker


def main() -> None:
    jobs = [{"jobId": str(uuid.UUID(int=index + 1)), "placeId": str(uuid.UUID(int=index + 101)),
             "reason": "FIXTURE_PILOT", "sourceFingerprint": f"fixture-{index}",
             "inputJson": json.dumps({"title": "경복궁", "region": "서울 종로구"}),
             "leaseToken": "local-test-token", "attempt": 1}
            for index in range(100)]
    submitted: list[dict] = []
    failures: list[dict] = []

    class Handler(BaseHTTPRequestHandler):
        def do_POST(self) -> None:
            payload = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            if self.path.endswith("/lease"):
                if jobs:
                    body, code = jobs.pop(0), 200
                else:
                    body, code = None, 204
            elif self.path.endswith("/evidence"):
                body, code = {"evidenceIds": [str(uuid.uuid5(uuid.NAMESPACE_URL, item["url"]))
                                               for item in payload]}, 200
            elif self.path.endswith("/submit"):
                submitted.append(payload)
                body, code = {"status": "SUCCEEDED"}, 200
            elif self.path.endswith("/fail"):
                failures.append(payload)
                body, code = {"status": "RETRY"}, 200
            else:
                body, code = {}, 404
            encoded = json.dumps(body).encode() if body is not None else b""
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(encoded)))
            self.end_headers()
            self.wfile.write(encoded)

        def log_message(self, *args) -> None:
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        with tempfile.TemporaryDirectory() as directory:
            fixture = Path(__file__).parent / "fixtures" / "pilot-example.json"
            provider = FixtureProvider(fixture)
            worker = Worker(JobApi(f"http://127.0.0.1:{server.server_port}", "fixture-token", "fixture-pilot"),
                            provider, Cache(Path(directory) / "pilot.sqlite3"), {"example.org"},
                            resolver=lambda host: ["8.8.8.8"], max_total_calls=300)
            start = time.monotonic()
            for _ in range(100):
                assert worker.once()
            elapsed = round((time.monotonic() - start) * 1000)
            assert not jobs and len(submitted) == 100 and not failures
            assert all(item["resultStatus"] == "UNCERTAIN" for item in submitted)
            result = {"mode": "fixture-localhost", **worker.metrics,
                      "submissions": len(submitted), "wallElapsedMs": elapsed,
                      "hitRate": worker.metrics["jobsWithHits"] / worker.metrics["jobs"]}
            print(json.dumps(result, sort_keys=True))
    finally:
        server.shutdown()
        server.server_close()


if __name__ == "__main__":
    main()

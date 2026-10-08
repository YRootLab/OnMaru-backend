import json
import tempfile
import unittest
import uuid
from unittest.mock import Mock, patch
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from threading import Thread

from workers.kcontents.search.worker import Cache, FixtureProvider, HttpJsonProvider, JobApi, PinnedHttpsConnection, SearchFailure, Worker, canonical_url, queries


class FakeApi:
    def __init__(self, leases):
        self.leases = list(leases)
        self.calls = []

    def post(self, suffix, payload=None, lease=None, key=None):
        self.calls.append((suffix, payload, lease, key))
        if suffix == "/jobs/lease":
            return self.leases.pop(0) if self.leases else None
        if suffix.endswith("/evidence"):
            return {"evidenceIds": [str(uuid.UUID(int=n + 1)) for n in range(len(payload))]}
        return {"status": "SUCCEEDED"}


def lease(job="job-1", fingerprint="fp-1", title="경복궁", region="서울 종로구"):
    return {"jobId": job, "placeId": "place-1", "reason": "INITIAL", "sourceFingerprint": fingerprint,
            "inputJson": json.dumps({"title": title, "region": region}), "leaseToken": "secret", "attempt": 1}


class WorkerTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name)

    def provider(self, data):
        fixture = self.path / "fixture.json"
        fixture.write_text(json.dumps(data), encoding="utf-8")
        return FixtureProvider(fixture)

    def test_url_dedup_tracking_and_ssrf(self):
        resolve = lambda host: ["8.8.8.8"]
        self.assertEqual(canonical_url("https://news.example/a/?utm_source=x&z=2#part", {"news.example"}, resolve),
                         "https://news.example/a?z=2")
        for url in ("http://127.0.0.1/a", "http://169.254.169.254/latest", "https://localhost/a",
                    "https://user:pass@news.example/a", "file:///etc/passwd", "https://news.example:8080/a"):
            with self.subTest(url=url), self.assertRaises(ValueError):
                canonical_url(url, {"news.example"}, resolve)
        with self.assertRaises(ValueError):
            canonical_url("https://news.example/a", {"news.example"}, lambda host: ["10.0.0.1"])

    def test_region_homonym_required_in_every_query(self):
        self.assertTrue(all("서울 종로구" in query for query in queries({"title": "경복궁", "region": "서울 종로구", "workTitle": "궁"})))
        with self.assertRaises(SearchFailure):
            queries({"title": "경복궁"})

    def test_restart_uses_ttl_cache_dedups_and_never_claims_match(self):
        query = queries({"title": "경복궁", "region": "서울 종로구"})[0]
        hit = {"url": "https://news.example/story?utm_source=one", "title": "촬영 기사",
               "publisher": "언론", "excerpt": "경복궁에서 촬영했다", "tier": "PRESS", "fullArticle": "DO_NOT_STORE"}
        duplicate = {**hit, "url": "https://news.example/story?utm_source=two"}
        provider = self.provider({query: [hit, duplicate]})
        cache_path = self.path / "cache.sqlite3"
        api = FakeApi([lease()])
        worker = Worker(api, provider, Cache(cache_path), {"news.example"}, now=lambda: 1000,
                        resolver=lambda host: ["8.8.8.8"])
        self.assertTrue(worker.once())
        evidence = next(call[1] for call in api.calls if call[0].endswith("/evidence"))
        self.assertEqual(len(evidence), 1)
        self.assertEqual(evidence[0]["url"], "https://news.example/story")
        self.assertNotIn("DO_NOT_STORE", cache_path.read_bytes().decode("utf-8", errors="ignore"))
        submission = next(call[1] for call in api.calls if call[0].endswith("/submit"))
        self.assertEqual(submission["resultStatus"], "UNCERTAIN")
        self.assertEqual(json.loads(submission["resultJson"])["relationCandidates"], [])

        restarted = Worker(FakeApi([lease("job-2", "fp-2")]), provider, Cache(cache_path), {"news.example"},
                           now=lambda: 1100, resolver=lambda host: ["8.8.8.8"])
        self.assertTrue(restarted.once())
        self.assertEqual(provider.calls, 2)  # both query variants were fetched only in the first process
        self.assertEqual(restarted.metrics["cacheHits"], 2)

    def test_no_match_has_reinvestigation_time_and_skips_repeat_search(self):
        provider = self.provider({})
        cache = Cache(self.path / "cache.sqlite3")
        first = FakeApi([lease()])
        Worker(first, provider, cache, {"news.example"}, no_match_ttl=3600, now=lambda: 1000).once()
        submission = next(call[1] for call in first.calls if call[0].endswith("/submit"))
        self.assertEqual(submission["resultStatus"], "NO_MATCH")
        self.assertIn("nextSearchAt", json.loads(submission["resultJson"]))
        second = FakeApi([lease("job-2")])
        Worker(second, provider, Cache(self.path / "cache.sqlite3"), {"news.example"}, now=lambda: 1100).once()
        self.assertEqual(provider.calls, 2)
        self.assertFalse(any(call[0].endswith("/evidence") for call in second.calls))

    def test_429_or_timeout_fails_job_without_submission(self):
        query = queries({"title": "경복궁", "region": "서울 종로구"})[0]
        for code in ("RATE_LIMITED", "TIMEOUT"):
            with self.subTest(code=code):
                api = FakeApi([lease(job=code)])
                worker = Worker(api, self.provider({query: {"error": code}}), Cache(self.path / f"{code}.db"),
                                {"news.example"}, now=lambda: 1000)
                worker.once()
                self.assertTrue(any(call[0].endswith("/fail") and call[1]["code"] == code for call in api.calls))
                self.assertFalse(any(call[0].endswith("/submit") for call in api.calls))
                self.assertEqual(worker.metrics["retries"], 1)

    def test_http_fixture_round_trip_uses_worker_headers_and_no_redirect(self):
        events = []
        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                events.append((self.path, body, dict(self.headers)))
                if self.path.endswith("/lease"):
                    response = lease()
                elif self.path.endswith("/evidence"):
                    response = {"evidenceIds": [str(uuid.UUID(int=1))]}
                else:
                    response = {"status": "SUCCEEDED"}
                payload = json.dumps(response).encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)

            def log_message(self, *args):
                pass

        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = Thread(target=server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        query = queries({"title": "경복궁", "region": "서울 종로구"})[0]
        provider = self.provider({query: [{"url": "https://news.example/a", "title": "기사", "excerpt": "촬영 근거"}]})
        api = JobApi(f"http://127.0.0.1:{server.server_port}", "worker-secret", "worker-1")
        worker = Worker(api, provider, Cache(self.path / "http.db"), {"news.example"},
                        resolver=lambda host: ["8.8.8.8"])
        self.assertTrue(worker.once())
        self.assertEqual(len(events), 3)
        self.assertTrue(all(headers["Authorization"] == "Bearer worker-secret" for _, _, headers in events))
        self.assertEqual(events[2][1]["resultStatus"], "UNCERTAIN")
        self.assertEqual(events[2][2]["X-Lease-Token"], "secret")
        self.assertIn("Idempotency-Key", events[2][2])

    def test_provider_requires_https_allowlist_and_pins_validated_ip_without_proxy(self):
        for endpoint, hosts in (("http://provider.example/search", {"provider.example"}),
                                ("https://evil.example/search", {"provider.example"}),
                                ("https://user@provider.example/search", {"provider.example"})):
            with self.subTest(endpoint=endpoint), self.assertRaises(ValueError):
                HttpJsonProvider(endpoint, "key", hosts)
        provider = HttpJsonProvider("https://provider.example/search", "key", {"provider.example"},
                                    resolver=lambda host: ["8.8.8.8"])
        class Response:
            status = 200
            def read(self, maximum):
                return b'{"results": []}'
        class Connection:
            last = None
            def __init__(self, host, ip, timeout):
                self.host, self.ip = host, ip
                Connection.last = self
            def request(self, method, path, headers):
                self.requested = (method, path, headers)
            def getresponse(self):
                return Response()
            def close(self):
                pass
        with patch("workers.kcontents.search.worker.PinnedHttpsConnection", Connection):
            self.assertEqual(provider.search("경복궁"), [])
            self.assertEqual((Connection.last.host, Connection.last.ip), ("provider.example", "8.8.8.8"))
        blocked = HttpJsonProvider("https://provider.example/search", "key", {"provider.example"},
                                   resolver=lambda host: ["10.0.0.1"])
        with self.assertRaises(SearchFailure):
            blocked.search("경복궁")

    def test_failed_call_consumes_persistent_daily_budget_after_restart(self):
        query = queries({"title": "경복궁", "region": "서울 종로구"})[0]
        cache_path = self.path / "budget.db"
        provider = self.provider({query: {"error": "RATE_LIMITED"}})
        first = Worker(FakeApi([lease()]), provider, Cache(cache_path), {"news.example"},
                       max_total_calls=1, max_cost=0.1, cost_per_search=0.1, now=lambda: 1000)
        first.once()
        self.assertEqual(first.metrics["searchCalls"], 1)
        self.assertEqual(first.metrics["estimatedCost"], 0.1)
        second = Worker(FakeApi([lease("job-2", "fp-2")]), provider, Cache(cache_path), {"news.example"},
                        max_total_calls=1, max_cost=0.1, cost_per_search=0.1, now=lambda: 1001)
        second.once()
        self.assertEqual(second.metrics["searchCalls"], 0)
        self.assertEqual(provider.calls, 1)
        self.assertEqual(second.metrics["rateLimited"], 1)

    def test_pinned_tls_connects_to_checked_ip_and_uses_approved_sni(self):
        context = Mock()
        raw = Mock()
        with patch("workers.kcontents.search.worker.ssl.create_default_context", return_value=context), \
             patch("workers.kcontents.search.worker.socket.create_connection", return_value=raw) as socket_call:
            connection = PinnedHttpsConnection("provider.example", "8.8.8.8", 5)
            connection.connect()
            socket_call.assert_called_once_with(("8.8.8.8", 443), 5)
            context.wrap_socket.assert_called_once_with(raw, server_hostname="provider.example")


if __name__ == "__main__":
    unittest.main()

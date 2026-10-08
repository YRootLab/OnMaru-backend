"""Bounded, evidence-only search against the W5 lease API.

The fixture provider is the only provider enabled by default. A live provider
requires an explicit endpoint/host allowlist after its terms and quota review.
"""

from __future__ import annotations

import argparse
import hashlib
import http.client
import ipaddress
import json
import os
import socket
import ssl
import sqlite3
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Callable

SCHEMA_VERSION = "kcontents-search-v1"
SOURCE_TIERS = {"OFFICIAL", "BROADCAST", "CULTURAL", "PRESS", "DISCOVERY_ONLY"}
STATE_DIR = Path.home() / ".local" / "state" / "onmaru"
TRACKING = {"fbclid", "gclid", "igshid", "mc_cid", "mc_eid"}
RETRYABLE = {429, 500, 502, 503, 504}


class SearchFailure(Exception):
    def __init__(self, code: str):
        super().__init__(code)
        self.code = code


def canonical_url(raw: str, allowed_hosts: set[str] | None = None,
                  resolver: Callable[[str], list[str]] | None = None) -> str:
    """Normalize and reject private, loopback, credentialed or unapproved URLs."""
    parsed = urllib.parse.urlsplit(raw.strip())
    if parsed.scheme not in {"http", "https"} or not parsed.hostname or parsed.username or parsed.password:
        raise ValueError("UNSAFE_URL")
    host = parsed.hostname.encode("idna").decode("ascii").lower().rstrip(".")
    if allowed_hosts is not None and host not in allowed_hosts:
        raise ValueError("UNAPPROVED_HOST")
    if parsed.port not in {None, 80, 443}:
        raise ValueError("UNSAFE_PORT")
    if host == "localhost" or host.endswith(".localhost"):
        raise ValueError("UNSAFE_HOST")
    try:
        addresses = [str(ipaddress.ip_address(host))]
    except ValueError:
        if resolver is None:
            addresses = list({item[4][0] for item in socket.getaddrinfo(host, None, type=socket.SOCK_STREAM)})
        else:
            addresses = resolver(host)
    if not addresses or any(not ipaddress.ip_address(address).is_global for address in addresses):
        raise ValueError("UNSAFE_ADDRESS")
    query = [(key, value) for key, value in urllib.parse.parse_qsl(parsed.query, keep_blank_values=True)
             if key.lower() not in TRACKING and not key.lower().startswith("utm_")]
    query.sort()
    path = urllib.parse.quote(urllib.parse.unquote(parsed.path or "/"), safe="/%:@")
    return urllib.parse.urlunsplit((parsed.scheme, host, path.rstrip("/") or "/",
                                   urllib.parse.urlencode(query), ""))


def queries(input_json: dict[str, Any]) -> list[str]:
    """Region always accompanies place names to distinguish homonyms."""
    title = str(input_json.get("title") or input_json.get("placeTitle") or "").strip()
    region = str(input_json.get("region") or input_json.get("regionName") or "").strip()
    if not title or not region:
        raise SearchFailure("INVALID_JSON")
    work = str(input_json.get("workTitle") or "").strip()
    terms = [f'"{title}" "{region}" 촬영지', f'"{title}" "{region}" 드라마 영화 뮤직비디오 촬영']
    if work:
        terms.append(f'"{work}" "{title}" "{region}" 촬영')
    return terms


@dataclass(frozen=True)
class Hit:
    url: str
    title: str
    publisher: str
    excerpt: str
    tier: str

    def api_value(self) -> dict[str, str]:
        return {"url": self.url, "title": self.title[:300],
                "publisher": self.publisher[:200], "excerpt": self.excerpt[:1000]}


class Cache:
    def __init__(self, path: Path):
        path.parent.mkdir(parents=True, exist_ok=True)
        self.connection = sqlite3.connect(path)
        self.connection.execute("CREATE TABLE IF NOT EXISTS search_cache (key TEXT PRIMARY KEY, expires_at REAL NOT NULL, payload TEXT NOT NULL)")
        self.connection.execute("CREATE TABLE IF NOT EXISTS no_match (fingerprint TEXT PRIMARY KEY, next_at REAL NOT NULL)")
        self.connection.execute("CREATE TABLE IF NOT EXISTS call_budget (day TEXT NOT NULL, provider TEXT NOT NULL, calls INTEGER NOT NULL, cost REAL NOT NULL, PRIMARY KEY(day,provider))")
        self.connection.commit()

    def reserve_call(self, day: str, provider: str, max_calls: int, max_cost: float,
                     cost_per_call: float) -> bool:
        """Reserve before network I/O, so 429/timeouts and process crashes consume budget."""
        self.connection.execute("BEGIN IMMEDIATE")
        try:
            row = self.connection.execute("SELECT calls,cost FROM call_budget WHERE day=? AND provider=?",
                                          (day, provider)).fetchone()
            calls, cost = row if row else (0, 0.0)
            if calls + 1 > max_calls or cost + cost_per_call > max_cost + 1e-9:
                self.connection.rollback()
                return False
            self.connection.execute("INSERT INTO call_budget VALUES (?,?,?,?) ON CONFLICT(day,provider) DO UPDATE SET calls=excluded.calls,cost=excluded.cost",
                                    (day, provider, calls + 1, cost + cost_per_call))
            self.connection.commit()
            return True
        except Exception:
            self.connection.rollback()
            raise

    def get(self, key: str, now: float) -> list[dict[str, Any]] | None:
        row = self.connection.execute("SELECT expires_at,payload FROM search_cache WHERE key=?", (key,)).fetchone()
        return json.loads(row[1]) if row and row[0] > now else None

    def put(self, key: str, payload: list[dict[str, Any]], expires_at: float) -> None:
        self.connection.execute("INSERT INTO search_cache VALUES (?,?,?) ON CONFLICT(key) DO UPDATE SET expires_at=excluded.expires_at,payload=excluded.payload",
                                (key, expires_at, json.dumps(payload, ensure_ascii=False)))
        self.connection.commit()

    def no_match_due(self, fingerprint: str, now: float) -> bool:
        row = self.connection.execute("SELECT next_at FROM no_match WHERE fingerprint=?", (fingerprint,)).fetchone()
        return row is None or row[0] <= now

    def mark_no_match(self, fingerprint: str, next_at: float) -> None:
        self.connection.execute("INSERT INTO no_match VALUES (?,?) ON CONFLICT(fingerprint) DO UPDATE SET next_at=excluded.next_at",
                                (fingerprint, next_at))
        self.connection.commit()


class FixtureProvider:
    def __init__(self, path: Path):
        self.data = json.loads(path.read_text(encoding="utf-8"))
        self.calls = 0

    def search(self, query: str) -> list[dict[str, Any]]:
        self.calls += 1
        value = self.data.get(query, [])
        if isinstance(value, dict) and "error" in value:
            raise SearchFailure(value["error"])
        return value


class HttpJsonProvider:
    """Optional approved JSON search endpoint; no arbitrary redirects or page fetches."""
    def __init__(self, endpoint: str, key: str, hosts: set[str], timeout: float = 5.0,
                 resolver: Callable[[str], list[str]] | None = None):
        parsed = urllib.parse.urlsplit(endpoint)
        if (parsed.scheme != "https" or not parsed.hostname or parsed.hostname not in hosts
                or parsed.port not in {None, 443} or parsed.username or parsed.password or parsed.fragment):
            raise ValueError("Provider endpoint requires HTTPS and approved host")
        self.host, self.path = parsed.hostname, urllib.parse.urlunsplit(("", "", parsed.path or "/", parsed.query, ""))
        self.key = key
        self.timeout = timeout
        self.resolver = resolver
        self.provider_id = self.host
        self.calls = 0

    def search(self, query: str) -> list[dict[str, Any]]:
        self.calls += 1
        # Resolve and validate immediately before each request, then pin the socket to
        # that IP while retaining the approved hostname for Host and TLS SNI/cert checks.
        try:
            addresses = self.resolver(self.host) if self.resolver else list({
                item[4][0] for item in socket.getaddrinfo(self.host, 443, type=socket.SOCK_STREAM)})
            if not addresses or any(not ipaddress.ip_address(ip).is_global for ip in addresses):
                raise SearchFailure("OTHER")
            connection = PinnedHttpsConnection(self.host, addresses[0], timeout=self.timeout)
            path = self.path + ("&" if "?" in self.path else "?") + urllib.parse.urlencode({"q": query})
            connection.request("GET", path, headers={"Authorization": f"Bearer {self.key}", "Accept": "application/json"})
            response = connection.getresponse()
            if response.status == 429:
                raise SearchFailure("RATE_LIMITED")
            if response.status in {408, 500, 502, 503, 504}:
                raise SearchFailure("TIMEOUT")
            if response.status != 200:
                raise SearchFailure("OTHER")
            body = response.read(1_000_001)
            if len(body) > 1_000_000:
                raise SearchFailure("INVALID_JSON")
            payload = json.loads(body)
        except SearchFailure:
            raise
        except (TimeoutError, OSError, ssl.SSLError):
            raise SearchFailure("TIMEOUT") from None
        except (ValueError, json.JSONDecodeError):
            raise SearchFailure("INVALID_JSON") from None
        finally:
            if "connection" in locals():
                connection.close()
        if not isinstance(payload, dict) or not isinstance(payload.get("results"), list):
            raise SearchFailure("INVALID_JSON")
        return payload["results"]


class PinnedHttpsConnection(http.client.HTTPSConnection):
    """Direct socket to validated IP. No proxy or redirect resolution path exists."""
    def __init__(self, host: str, ip: str, timeout: float):
        super().__init__(host, port=443, timeout=timeout, context=ssl.create_default_context())
        self.ip = ip

    def connect(self) -> None:
        raw = socket.create_connection((self.ip, 443), self.timeout)
        try:
            self.sock = self._context.wrap_socket(raw, server_hostname=self.host)
        except Exception:
            raw.close()
            raise


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request: Any, fp: Any, code: int, msg: str, headers: Any, newurl: str) -> None:
        raise SearchFailure("OTHER")


class JobApi:
    def __init__(self, base: str, token: str, worker_id: str, timeout: float = 8.0):
        self.base = base.rstrip("/")
        if urllib.parse.urlsplit(self.base).scheme not in {"https", "http"}:
            raise ValueError("Invalid server URL")
        if urllib.parse.urlsplit(self.base).scheme == "http" and urllib.parse.urlsplit(self.base).hostname not in {"localhost", "127.0.0.1"}:
            raise ValueError("Remote server requires HTTPS")
        self.token, self.worker_id, self.timeout = token, worker_id, timeout

    def post(self, suffix: str, payload: Any = None, lease: str | None = None,
             key: str | None = None) -> Any:
        headers = {"Authorization": f"Bearer {self.token}", "X-Worker-Id": self.worker_id,
                   "Content-Type": "application/json"}
        if lease:
            headers["X-Lease-Token"] = lease
        if key:
            headers["Idempotency-Key"] = key
        request = urllib.request.Request(self.base + "/api/v1/internal/kcontents/research" + suffix,
                                         data=json.dumps(payload if payload is not None else {}).encode(),
                                         headers=headers, method="POST")
        try:
            with urllib.request.build_opener(NoRedirect()).open(request, timeout=self.timeout) as response:
                return None if response.status == 204 else json.load(response)
        except urllib.error.HTTPError as error:
            if error.code in RETRYABLE:
                raise SearchFailure("RATE_LIMITED" if error.code == 429 else "TIMEOUT") from None
            raise SearchFailure("OTHER") from None
        except (TimeoutError, OSError):
            raise SearchFailure("TIMEOUT") from None


class Worker:
    def __init__(self, api: JobApi, provider: FixtureProvider | HttpJsonProvider, cache: Cache,
                 hosts: set[str], cache_ttl: int = 86400, no_match_ttl: int = 30 * 86400,
                 max_searches: int = 3, max_cost: float = 0.0, cost_per_search: float = 0.0,
                 max_total_calls: int = 300,
                 now: Callable[[], float] = time.time,
                 resolver: Callable[[str], list[str]] | None = None,
                 source_tiers: dict[str, str] | None = None):
        self.api, self.provider, self.cache, self.hosts = api, provider, cache, hosts
        self.cache_ttl, self.no_match_ttl = cache_ttl, no_match_ttl
        if not 1 <= max_searches <= 3 or cache_ttl < 60 or no_match_ttl < 60:
            raise ValueError("Invalid search or TTL budget")
        if max_cost < 0 or cost_per_search < 0:
            raise ValueError("Invalid cost budget")
        if max_total_calls < 1:
            raise ValueError("Invalid total call budget")
        self.max_searches, self.max_cost, self.cost_per_search = max_searches, max_cost, cost_per_search
        self.max_total_calls = max_total_calls
        self.now = now
        self.resolver = resolver
        self.source_tiers = source_tiers or {}
        if any(host not in hosts or tier not in SOURCE_TIERS for host, tier in self.source_tiers.items()):
            raise ValueError("Invalid source tier policy")
        self.metrics = {"jobs": 0, "jobsWithHits": 0, "searchCalls": 0, "cacheHits": 0, "resultHits": 0,
                        "failures": 0, "retries": 0, "rateLimited": 0, "timeouts": 0,
                        "elapsedMs": 0, "estimatedCost": 0.0}

    def once(self) -> bool:
        lease = self.api.post("/jobs/lease")
        if lease is None:
            return False
        self.metrics["jobs"] += 1
        started = time.monotonic()
        job_id, token = lease["jobId"], lease["leaseToken"]
        try:
            self._process(lease)
        except SearchFailure as error:
            self.metrics["failures"] += 1
            if error.code in {"RATE_LIMITED", "TIMEOUT"}:
                self.metrics["retries"] += 1
                self.metrics["rateLimited" if error.code == "RATE_LIMITED" else "timeouts"] += 1
            self.api.post(f"/jobs/{job_id}/fail", {"code": error.code}, token,
                          hashlib.sha256(f"{job_id}:{lease['attempt']}:{error.code}".encode()).hexdigest())
        finally:
            self.metrics["elapsedMs"] += round((time.monotonic() - started) * 1000)
        return True

    def _process(self, lease: dict[str, Any]) -> None:
        raw = lease["inputJson"]
        try:
            inputs = json.loads(raw) if isinstance(raw, str) else raw
        except (TypeError, ValueError):
            raise SearchFailure("INVALID_JSON") from None
        if not isinstance(inputs, dict):
            raise SearchFailure("INVALID_JSON")
        fingerprint = ":".join((lease["placeId"], lease["reason"], lease["sourceFingerprint"]))
        if not self.cache.no_match_due(fingerprint, self.now()):
            self._submit(lease, [], "NO_MATCH", self.cache.connection.execute(
                "SELECT next_at FROM no_match WHERE fingerprint=?", (fingerprint,)).fetchone()[0])
            return
        hits: dict[str, Hit] = {}
        for query in queries(inputs)[:self.max_searches]:
            key = hashlib.sha256((SCHEMA_VERSION + query).encode()).hexdigest()
            rows = self.cache.get(key, self.now())
            if rows is None:
                day = datetime.fromtimestamp(self.now(), timezone.utc).date().isoformat()
                provider_id = getattr(self.provider, "provider_id", "fixture")
                cost_cap = self.max_cost if self.cost_per_search else float("inf")
                if not self.cache.reserve_call(day, provider_id, self.max_total_calls,
                                               cost_cap, self.cost_per_search):
                    raise SearchFailure("RATE_LIMITED")
                self.metrics["searchCalls"] += 1
                self.metrics["estimatedCost"] += self.cost_per_search
                rows = self.provider.search(query)
                if not isinstance(rows, list):
                    raise SearchFailure("INVALID_JSON")
                rows = [{field: str(row.get(field) or "")[:limit]
                         for field, limit in (("url", 2048), ("title", 300),
                                              ("publisher", 200), ("excerpt", 1000))}
                        for row in rows[:100] if isinstance(row, dict)]
                self.cache.put(key, rows, self.now() + self.cache_ttl)
            else:
                self.metrics["cacheHits"] += 1
            for row in rows:
                if not isinstance(row, dict):
                    continue
                try:
                    url = canonical_url(str(row.get("url", "")), self.hosts, self.resolver)
                except (ValueError, OSError):
                    continue
                title = str(row.get("title") or "").strip()
                excerpt = str(row.get("excerpt") or "").strip()
                if not title or not excerpt:
                    continue
                publisher = str(row.get("publisher") or "").strip()
                tier = self.source_tiers.get(urllib.parse.urlsplit(url).hostname or "", "DISCOVERY_ONLY")
                hits.setdefault(url, Hit(url, title, publisher, excerpt, tier))
                if len(hits) >= 20:
                    break
            if len(hits) >= 20:
                break
        self.metrics["resultHits"] += len(hits)
        if hits:
            self.metrics["jobsWithHits"] += 1
        next_at = self.now() + self.no_match_ttl if not hits else None
        if next_at:
            self.cache.mark_no_match(fingerprint, next_at)
        self._submit(lease, list(hits.values()), "UNCERTAIN" if hits else "NO_MATCH", next_at)

    def _submit(self, lease: dict[str, Any], hits: list[Hit], status: str, next_at: float | None) -> None:
        job_id, token = lease["jobId"], lease["leaseToken"]
        ids: list[str] = []
        if hits:
            response = self.api.post(f"/jobs/{job_id}/evidence", [hit.api_value() for hit in hits], token)
            ids = response["evidenceIds"]
        result = {"status": status, "placeId": lease["placeId"], "sourceFingerprint": lease["sourceFingerprint"],
                  "evidenceIds": ids, "queryCount": min(self.max_searches, len(queries(json.loads(lease["inputJson"]) if isinstance(lease["inputJson"], str) else lease["inputJson"]))),
                  "nextSearchAt": datetime.fromtimestamp(next_at, timezone.utc).isoformat() if next_at else None,
                  "sources": [{"evidenceId": evidence_id, "tier": hit.tier}
                              for evidence_id, hit in zip(ids, hits)],
                  "relationCandidates": [], "note": "Search leads only; server verification required"}
        payload = {"resultStatus": status, "schemaVersion": SCHEMA_VERSION, "promptVersion": "none",
                   "modelVersion": "none", "evidenceIds": ids,
                   "resultJson": json.dumps(result, ensure_ascii=False, sort_keys=True)}
        key = hashlib.sha256((job_id + ":" + str(lease["attempt"]) + ":submit").encode()).hexdigest()
        self.api.post(f"/jobs/{job_id}/submit", payload, token, key)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Bounded W5 evidence search worker")
    parser.add_argument("--fixture", type=Path, help="fixture JSON; default provider mode")
    parser.add_argument("--max-jobs", type=int, default=1)
    parser.add_argument("--cache", type=Path, default=STATE_DIR / "kcontents-search.sqlite3")
    parser.add_argument("--metrics", type=Path, default=STATE_DIR / "kcontents-search-metrics.jsonl")
    args = parser.parse_args(argv)
    if args.max_jobs < 1 or args.max_jobs > 100:
        parser.error("max-jobs must be 1..100")
    mode = os.getenv("ONMARU_SEARCH_PROVIDER", "fixture")
    hosts = {host.strip().lower() for host in os.getenv("ONMARU_SEARCH_EVIDENCE_HOSTS", "").split(",") if host.strip()}
    if mode == "fixture":
        if args.fixture is None:
            parser.error("fixture mode requires --fixture")
        provider: FixtureProvider | HttpJsonProvider = FixtureProvider(args.fixture)
    elif mode == "http-json" and os.getenv("ONMARU_SEARCH_LIVE_ENABLED") == "true":
        endpoint = os.environ["ONMARU_SEARCH_PROVIDER_ENDPOINT"]
        key = os.environ["ONMARU_SEARCH_PROVIDER_KEY"]
        approved_provider_hosts = {host.strip().lower() for host in os.getenv("ONMARU_SEARCH_PROVIDER_HOSTS", "").split(",") if host.strip()}
        if not approved_provider_hosts:
            parser.error("live provider requires ONMARU_SEARCH_PROVIDER_HOSTS")
        provider = HttpJsonProvider(endpoint, key, approved_provider_hosts)
    else:
        parser.error("live search requires explicit ONMARU_SEARCH_LIVE_ENABLED=true and approved provider")
    if not hosts:
        parser.error("ONMARU_SEARCH_EVIDENCE_HOSTS is required")
    source_tiers = json.loads(os.getenv("ONMARU_SEARCH_SOURCE_TIERS", "{}"))
    if not isinstance(source_tiers, dict):
        parser.error("ONMARU_SEARCH_SOURCE_TIERS must be a JSON object")
    cost_per_search = float(os.getenv("ONMARU_SEARCH_COST_PER_CALL", "0"))
    max_cost = float(os.getenv("ONMARU_SEARCH_MAX_COST", "0"))
    if mode == "http-json" and (cost_per_search <= 0 or max_cost <= 0):
        parser.error("live provider requires positive cost per call and maximum cost")
    api = JobApi(os.environ["ONMARU_RESEARCH_API_BASE"], os.environ["ONMARU_RESEARCH_WORKER_TOKEN"],
                 os.environ["ONMARU_RESEARCH_WORKER_ID"])
    worker = Worker(api, provider, Cache(args.cache), hosts,
                    max_searches=int(os.getenv("ONMARU_SEARCH_MAX_CALLS_PER_JOB", "3")),
                    max_cost=max_cost, cost_per_search=cost_per_search,
                    max_total_calls=int(os.getenv("ONMARU_SEARCH_MAX_CALLS_PER_DAY", "300")),
                    source_tiers=source_tiers)
    for _ in range(args.max_jobs):
        if not worker.once():
            break
    args.metrics.parent.mkdir(parents=True, exist_ok=True)
    with args.metrics.open("a", encoding="utf-8") as stream:
        stream.write(json.dumps({"at": datetime.now(timezone.utc).isoformat(), **worker.metrics}) + "\n")
    print(json.dumps(worker.metrics))
    return 0


if __name__ == "__main__":
    sys.exit(main())

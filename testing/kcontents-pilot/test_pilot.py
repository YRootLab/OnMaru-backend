import importlib.util
import unittest
import urllib.error
from pathlib import Path


ROOT = Path(__file__).parent


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, ROOT / filename)
    loaded = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(loaded)
    return loaded


report = module("pilot_report", "report.py")
golden = module("legacy_golden", "legacy_golden.py")
enqueue = module("pilot_enqueue", "enqueue.py")


class PilotTests(unittest.TestCase):
    def sample(self):
        manifest = {"stage": 100, "expectedJobs": 100, "pilotReason": "PILOT_691_100_TEST",
                    "providerMode": "http-json", "environment": "staging", "minimumPrecision": 0.95}
        database = {"pilotReason": manifest["pilotReason"], "totalJobs": 100, "succeededJobs": 100,
                    "failedJobs": 0, "quarantinedJobs": 0, "jobsWithEvidence": 60,
                    "validationRuns": 100, "reviewRequiredRuns": 40, "publicRelations": 1,
                    "publicRelationIds": ["r-1"], "publicPlaces": 1, "distinctRegions": 1,
                    "distinctWorks": 1, "durationSeconds": 600}
        metrics = [{key: 0 for key in report.METRICS}]
        metrics[0].update(jobs=100, searchCalls=180, jobsWithHits=60, estimatedCost=1.8)
        labels = [{"relationId": "r-1", "verdict": "VALID", "reviewer": "human",
                   "reviewedAt": "2026-10-09T00:00:00Z"}]
        return manifest, database, metrics, labels

    def test_live_complete_and_manually_judged_can_reach_operator_review(self):
        output = report.report(*self.sample())
        self.assertEqual(output["gate"], "READY_FOR_OPERATOR_REVIEW")
        self.assertEqual(output["throughputPlacesPerMinute"], 10)
        self.assertEqual(output["evidenceDiscoveryRate"], 0.6)
        self.assertEqual(output["reviewRate"], 0.4)

    def test_fixture_or_missing_labels_cannot_claim_live_pilot(self):
        manifest, database, metrics, labels = self.sample()
        manifest["providerMode"] = "fixture"
        self.assertEqual(report.report(manifest, database, metrics, labels)["gate"], "BLOCKED")
        manifest["providerMode"] = "http-json"
        self.assertIn("MANUAL_PRECISION_INCOMPLETE", report.report(manifest, database, metrics, [])["blockers"])

    def test_wrong_cohort_and_duplicate_judgment_are_rejected(self):
        manifest, database, metrics, labels = self.sample()
        database["pilotReason"] = "OTHER"
        with self.assertRaises(ValueError): report.report(manifest, database, metrics, labels)
        database["pilotReason"] = manifest["pilotReason"]
        with self.assertRaises(ValueError): report.report(manifest, database, metrics, labels * 2)

    def test_golden_strictly_detects_legacy_change_and_rejects_production(self):
        before = {"kind": "LEGACY_STAGING_GOLDEN", "requests": [
            {"id": "screen-hanok", "path": "/api/v1/hanoks/screen-hanok", "status": 200, "sha256": "a"}]}
        after = {"kind": before["kind"], "requests": [dict(before["requests"][0], sha256="b")]}
        self.assertEqual(golden.compare(before, after), ["screen-hanok"])
        with self.assertRaises(ValueError): golden.allowed_base("https://api.onmaru.site")
        for unsafe in ("https://staging-api.onmaru.site:443", "https://staging-api.onmaru.site/api",
                       "https://staging-api.onmaru.site?next=elsewhere",
                       "https://staging-api.onmaru.site/#fragment"):
            with self.assertRaises(ValueError): golden.allowed_base(unsafe)

    def test_golden_ignores_only_volatile_time_and_cursor_expiry(self):
        import base64
        import json
        def cursor(expiry, place):
            payload = {"scope": "odii-stories:v1", "expiresAt": expiry,
                       "claims": {"revisionId": "r1", "storyId": place, "limit": 20}}
            encoded = base64.urlsafe_b64encode(json.dumps(payload).encode()).decode().rstrip("=")
            return encoded + ".signature"

        def row(item_id, body):
            return {"id": item_id, "path": "/api/v1/" + item_id, "status": 200,
                    "sha256": "old-capture-hash", "body": body}

        before = {"kind": "LEGACY_STAGING_GOLDEN", "requests": [
            row("home", {"countsAsOf": "t1", "items": [{"placeId": "p-1"}]}),
            row("odii", {"items": [{"storyId": "s-1"}], "nextCursor": cursor("t1", "s-1")})]}
        after = {"kind": before["kind"], "requests": [
            row("home", {"countsAsOf": "t2", "items": [{"placeId": "p-1"}]}),
            row("odii", {"items": [{"storyId": "s-1"}], "nextCursor": cursor("t2", "s-1")})]}
        self.assertEqual(golden.compare(before, after), [])
        after["requests"][1]["body"]["nextCursor"] = cursor("t2", "s-2")
        self.assertEqual(golden.compare(before, after), ["odii"])
        after["requests"][0]["body"]["items"][0]["placeId"] = "p-2"
        self.assertEqual(golden.compare(before, after), ["home", "odii"])

    def test_authenticated_golden_refuses_redirect(self):
        def redirect(request, timeout):
            self.assertEqual(request.get_header("Cookie"), "__Host-onmaru-session=secret")
            raise urllib.error.HTTPError(request.full_url, 302, "moved",
                                         {"Location": "https://example.org/steal"}, None)
        import os
        previous = os.environ.get("ONMARU_STAGING_SESSION_COOKIE")
        os.environ["ONMARU_STAGING_SESSION_COOKIE"] = "secret"
        try:
            with self.assertRaisesRegex(ValueError, "redirect"):
                golden.capture("https://staging-api.onmaru.site", {"requests": [
                    {"id": "saved", "path": "/api/v1/saved-resources", "status": 200, "session": True}]}, redirect)
        finally:
            if previous is None:
                os.environ.pop("ONMARU_STAGING_SESSION_COOKIE", None)
            else:
                os.environ["ONMARU_STAGING_SESSION_COOKIE"] = previous

    def test_enqueue_requires_exact_distinct_validated_place_inputs(self):
        rows = [{"placeId": f"00000000-0000-4000-8000-{index:012d}",
                 "sourceFingerprint": f"source-{index}", "inputJson": "{}"}
                for index in range(100)]
        enqueue.validate(rows, 100)
        with self.assertRaises(ValueError): enqueue.validate(rows[:99], 100)
        with self.assertRaises(ValueError): enqueue.validate(rows[:-1] + [rows[0]], 100)
        with self.assertRaises(ValueError): enqueue.validate([dict(row, inputJson="not-json") for row in rows], 100)


if __name__ == "__main__":
    unittest.main()

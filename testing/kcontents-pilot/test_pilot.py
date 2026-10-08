import importlib.util
import unittest
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

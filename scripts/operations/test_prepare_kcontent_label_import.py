import hashlib
import importlib.util
import json
from pathlib import Path
import unittest

PATH = Path(__file__).with_name('prepare-kcontent-label-import.py')
spec = importlib.util.spec_from_file_location('prepare_kcontent_label_import', PATH)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class PrepareKcontentLabelImportTests(unittest.TestCase):
    def fixture(self):
        row = {'contentId': '123', 'sourceHash': 'a' * 64, 'decision': 'REVIEW',
               'policyVersion': 'v1', 'kcontentsResearchStatus': 'OFFICIAL_CANDIDATE_REVIEW',
               'reason': 'TEST', 'role': None}
        row['evidenceAudit'] = {'contentId': '123', 'auditStatus': 'EXACT_NAME_NEARBY_REVIEW',
                                'auditVersion': 'audit-v1', 'nearbyOfficialPlaces': []}
        row['placeSummaryPoints'] = ['장소 · 전통문화 후보 검토 중', '위치: 서울', '촬영 근거 검토 중']
        digest = hashlib.sha256(('123:' + 'a' * 64 + ':REVIEW').encode()).hexdigest()
        audit_digest = hashlib.sha256((json.dumps(row['evidenceAudit'], ensure_ascii=False,
                                                  separators=(',', ':')) + '\n').encode()).hexdigest()
        summary_digest = hashlib.sha256((json.dumps(
            {'contentId': row['contentId'], 'placeSummaryPoints': row['placeSummaryPoints']},
            ensure_ascii=False, separators=(',', ':')) + '\n').encode()).hexdigest()
        manifest = {'uniqueContentIds': 1, 'labelSetSha256': digest,
                    'decisions': {'REVIEW': 1}, 'policyVersion': 'v1',
                    'capturedAt': '2026-10-12T00:00:00Z', 'officialSourceRows': 1,
                    'evidenceAuditVersion': 'audit-v1', 'evidenceAuditSha256': audit_digest,
                    'evidenceAuditStatuses': {'EXACT_NAME_NEARBY_REVIEW': 1},
                    'placeSummaryPolicyVersion': 'place-summary-v1.0.0',
                    'placeSummarySetSha256': summary_digest,
                    'verifiedRelationsProvided': True,
                    'verifiedOutsideCandidateContentIds': []}
        return [row], manifest

    def test_default_is_rollback(self):
        rows, manifest = self.fixture()
        sql, _ = module.generate(rows, manifest)
        self.assertTrue(sql.endswith('ROLLBACK;\n'))
        self.assertIn('CREATE TEMP TABLE import_labels', sql)
        self.assertNotIn('INSERT INTO onmaru.k_content_label_active', sql)

    def test_publish_requires_all_verified_places_in_capture(self):
        rows, manifest = self.fixture()
        manifest['verifiedOutsideCandidateContentIds'] = ['999']
        with self.assertRaisesRegex(ValueError, 'outside selected capture'):
            module.generate(rows, manifest, publish=True)

    def test_tampered_decision_fails_before_sql_generation(self):
        rows, manifest = self.fixture()
        rows[0]['decision'] = 'INCLUDE'
        with self.assertRaisesRegex(ValueError, 'digest differs'):
            module.generate(rows, manifest)

    def test_tampered_audit_fails_before_sql_generation(self):
        rows, manifest = self.fixture()
        rows[0]['evidenceAudit']['auditStatus'] = 'VERIFIED_EXISTING'
        with self.assertRaisesRegex(ValueError, 'audit digest differs'):
            module.generate(rows, manifest)

    def test_tampered_summary_fails_before_sql_generation(self):
        rows, manifest = self.fixture()
        rows[0]['placeSummaryPoints'][2] = '촬영 확정'
        with self.assertRaisesRegex(ValueError, 'summary digest differs'):
            module.generate(rows, manifest)


if __name__ == '__main__':
    unittest.main()

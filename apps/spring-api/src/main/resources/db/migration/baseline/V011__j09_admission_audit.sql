-- onmaru-checksum: j09-v011-20260916
-- Issue: #117 Guest/member AI daily quota admission audit.

CREATE TABLE onmaru.operations_admission_audit (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    scope_key varchar NOT NULL,
    operation varchar NOT NULL,
    subject_type varchar NOT NULL,
    window_start timestamptz NOT NULL,
    decision varchar NOT NULL,
    reason varchar,
    limit_value integer NOT NULL,
    consumed_after integer NOT NULL,
    active_after integer NOT NULL,
    retry_after_ms bigint NOT NULL,
    occurred_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT operations_admission_audit_operation_ck CHECK (btrim(operation) <> ''),
    CONSTRAINT operations_admission_audit_subject_type_ck CHECK (
        subject_type IN ('GUEST', 'IP', 'MEMBER')
    ),
    CONSTRAINT operations_admission_audit_decision_ck CHECK (
        decision IN ('ALLOWED', 'REJECTED')
    ),
    CONSTRAINT operations_admission_audit_reason_ck CHECK (
        reason IS NULL OR reason IN ('ACTIVE_LIMIT', 'QUOTA_EXCEEDED')
    ),
    CONSTRAINT operations_admission_audit_limit_value_ck CHECK (limit_value > 0),
    CONSTRAINT operations_admission_audit_consumed_after_ck CHECK (consumed_after >= 0),
    CONSTRAINT operations_admission_audit_active_after_ck CHECK (active_after >= 0),
    CONSTRAINT operations_admission_audit_retry_after_ms_ck CHECK (retry_after_ms >= 0)
);

CREATE INDEX operations_admission_audit_scope_occurred_idx
    ON onmaru.operations_admission_audit (scope_key, occurred_at DESC);

CREATE INDEX operations_admission_audit_operation_decision_occurred_idx
    ON onmaru.operations_admission_audit (operation, decision, occurred_at DESC);

INSERT INTO onmaru_registry.migration_version_reservations (
    version,
    reserved_for,
    issue_number,
    description
) VALUES (
    '011',
    'J09',
    117,
    'Guest/member AI daily quota admission audit'
) ON CONFLICT (version) DO UPDATE
SET reserved_for = EXCLUDED.reserved_for,
    issue_number = EXCLUDED.issue_number,
    description = EXCLUDED.description;

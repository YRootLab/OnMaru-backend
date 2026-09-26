CREATE TABLE onmaru.web_idempotency_receipts (
    subject_id varchar NOT NULL,
    idempotency_key uuid NOT NULL,
    method varchar NOT NULL,
    path varchar NOT NULL,
    payload_fingerprint varchar NOT NULL,
    response_status integer NOT NULL,
    response_headers jsonb NOT NULL DEFAULT '{}'::jsonb,
    response_body jsonb,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (subject_id, idempotency_key, method, path),
    CONSTRAINT web_idempotency_receipts_status_ck CHECK (response_status BETWEEN 100 AND 599),
    CONSTRAINT web_idempotency_receipts_headers_object_ck CHECK (jsonb_typeof(response_headers) = 'object')
);

INSERT INTO onmaru_registry.migration_version_reservations (version,reserved_for,issue_number,description)
VALUES ('022','FE-346',346,'Durable HTTP idempotency receipts')
ON CONFLICT (version) DO UPDATE SET reserved_for=EXCLUDED.reserved_for,issue_number=EXCLUDED.issue_number,description=EXCLUDED.description;

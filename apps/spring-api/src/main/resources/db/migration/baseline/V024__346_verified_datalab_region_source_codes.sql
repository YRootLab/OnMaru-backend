-- DataLab requests may use only region codes verified against an official provider source.
CREATE TABLE onmaru.catalog_region_source_code_verifications (
    provider varchar NOT NULL,
    dataset varchar NOT NULL,
    source_code varchar NOT NULL,
    valid_from date NOT NULL,
    official_source_url text NOT NULL,
    verified_at timestamptz NOT NULL,
    verified_by varchar NOT NULL,
    PRIMARY KEY (provider, dataset, source_code, valid_from),
    CONSTRAINT catalog_region_source_code_verifications_mapping_fk
        FOREIGN KEY (provider, dataset, source_code, valid_from)
        REFERENCES onmaru.catalog_region_source_codes (provider, dataset, source_code, valid_from),
    CONSTRAINT catalog_region_source_code_verifications_official_url_ck
        CHECK (official_source_url ~ '^https://[^[:space:]]+$'),
    CONSTRAINT catalog_region_source_code_verifications_verified_by_ck
        CHECK (btrim(verified_by) <> '')
);

INSERT INTO onmaru_registry.migration_version_reservations (
    version, reserved_for, issue_number, description
) VALUES (
    '024', 'FE-346', 346,
    'Official-source verification boundary for DataLab region source codes'
) ON CONFLICT (version) DO UPDATE
SET reserved_for = EXCLUDED.reserved_for,
    issue_number = EXCLUDED.issue_number,
    description = EXCLUDED.description;

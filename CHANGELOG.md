# Changelog

This project uses semantic version tags from `main`. Release notes should be generated from Conventional Commits through Release Please once releasable backend changes exist.

## Unreleased

- Add identity/member/SavedResource OpenAPI fixtures and contract validation for Issue #132.
- Add R1 hanok/place/saved-resource OpenAPI fixtures and CI contract validation for OpenAPI, JSON Schema, DBML, generated SQL, Spring, and FastAPI checks.
- Capture redacted Odii API qualification fixtures and license/provenance manifest for Issue #61.
- Add TourAPI qualification manifest, redacted provider fixtures, and CI fixture validation for Issue #66.
- Add reproducible backend planning-input snapshots with provenance manifest, hash verification, and secret scanning in CI.
- Add PostgreSQL/PostGIS Testcontainers smoke coverage, clean test DB reset helper, and local compose readiness checks.
- Add reproducible Java 21/Spring Boot and Python 3.12/FastAPI development scaffolds with locked dependencies, health checks, and offline-cache verification.
- Standardize the Java namespace and Gradle group on `com.yrootlab.onmaru` across source, planning graphs, and published Issue paths.
- Document post-audit backend planning refinements, DBML ERD modules, and Azimutt PNG handoff workflow.
- Prepare backend implementation issue graph, P0 triage, and runtime ADR follow-up after PR #48.
- Initialize project operating harness, Git Flow policy, release automation baseline, and ADR directory.

# improvements.md

Untriaged follow-up ideas captured during work. GitHub Issues are the source of truth after triage; this file is only a temporary inbox for future sessions.

## Product & AI Follow-ups

- [ ] Re-evaluate public Gemini usage policy with measured quota and abuse evidence.
  - Context: Current MVP policy is 2 runs/day for guests and 5 runs/day for members in KST. Need real-world metric evaluation.
  - Acceptance: Latency, error rate, quota exhaustion frequency, and abuse signals are analyzed to determine whether to adjust limits or introduce tiered quotas.
  - Suggested labels: `needs-triage`, `type:ai`, `type:product`, `priority:p2`

- [ ] Prepare contest demo scenario and evidence package.
  - Context: Need structured demonstration flow for judging evaluations.
  - Acceptance: 90-second journey walkthrough, before/after value comparisons, and public data integration impact evidence are compiled.
  - Suggested labels: `needs-triage`, `type:product`, `priority:p1`

- [ ] Explore two-era experimental journey UI prototype.
  - Context: Experimental historical comparison / era-linked map view for cultural storytelling.
  - Acceptance: Prototype after core MVP stabilization and feedback collection.
  - Suggested labels: `needs-triage`, `type:ux`, `priority:p2`

## Architecture & Operational Improvements

- [ ] Revisit caching layer (Redis) and graph traversal engine as load scales.
  - Context: Current architecture runs on PostgreSQL and in-memory caches. Dedicated cache cluster or graph database may be evaluated if complex recommendation queries or timeline traversals require it.
  - Acceptance: Benchmark query latency and concurrency under load tests before deciding on service extraction.
  - Suggested labels: `needs-triage`, `type:architecture`, `priority:p2`

- [ ] Verify GitHub branch protection rules manually in repository settings.
  - Context: GitHub API in private repo context cannot programmatically verify branch protection enforcement.
  - Acceptance: Confirm required reviews, status checks (`verify`), and force push restrictions on `develop` and `main`.
  - Suggested labels: `needs-triage`, `type:ops`, `priority:p2`

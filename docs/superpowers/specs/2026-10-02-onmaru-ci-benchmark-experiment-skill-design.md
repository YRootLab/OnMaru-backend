# OnMaru CI Benchmark Experiment Skill Design

Status: approved design

Related: [OnMaruBE #568](https://github.com/YRootLab/OnMaru-backend/issues/568), [OnMaruBE #555](https://github.com/YRootLab/OnMaru-backend/issues/555), [OnMaruBE #556](https://github.com/YRootLab/OnMaru-backend/issues/556), [Toolkit #115](https://github.com/YRootLab/OnMaru-backend-ci-toolkit/issues/115), [Toolkit #122](https://github.com/YRootLab/OnMaru-backend-ci-toolkit/issues/122), [Agent Toolkit #58](https://github.com/SHcommit/Agent-toolkit/issues/58)

## Decision

The benchmark orchestration skill is an OnMaruBE-owned, repository-local skill named `onmaru-ci-benchmark-experiment`. Its only canonical source lives at `skills/onmaru-ci-benchmark-experiment/`. It is not published from Agent Toolkit and is not duplicated under `.agents/skills`.

This placement follows the actual contract. Toolkit #122 intentionally fixes the consumer repository, `develop` baseline, `feature/*` candidate, workflow path, committed test plan, artifact names, trusted signer, and OnMaruBE integration gates. A skill that operates that contract is product-specific even though the underlying comparison and evidence libraries remain reusable.

## Ownership boundary

| Owner | Responsibility |
| --- | --- |
| OnMaruBE Skill | Natural-language triggering, dry-run-first guidance, explicit execute boundary, operator-facing explanations, and invocation of the pinned Toolkit CLI |
| OnMaruBE workflow | Trusted dispatch controller, samples, application/test-plan identity, attestations, artifacts, credentials, and consumer diagnostics |
| CI Toolkit | `pipeline-toolkit experiment` validation, dispatch/wait/collection, bounded artifact handling, evidence authentication, comparison, and stable result schemas |
| Agent Toolkit | No implementation or distribution responsibility for this project-specific skill |

The skill must not reproduce benchmark calculations, GitHub API validation, artifact parsing, or security policy. Those remain executable Toolkit contracts.

## Repository layout

```text
skills/
└── onmaru-ci-benchmark-experiment/
    ├── SKILL.md
    ├── scripts/
    │   └── run_experiment.py
    └── evals/
        └── evals.json
```

`AGENTS.md` gains a repository-local skills section that tells agents to discover skills under `skills/`, read the complete matching `SKILL.md`, resolve relative resources from that directory, and treat it as the single source. No symlink, generated copy, plugin bundle, or catalog export is added.

The helper is a thin deterministic adapter. It locates the repository root, validates the requested operation (`dry-run`, `dispatch`, `wait`, or `compare`), constructs an argument vector without a shell, invokes the installed version-pinned Toolkit CLI, and preserves its exit code and bounded structured output. It does not weaken or duplicate Toolkit validation.

## Trigger and interaction contract

The skill triggers only for explicit OnMaru pipeline experiment work, such as comparing a CI/test pipeline change against `develop`, preparing a three-sample experiment, waiting for its exact run, or explaining its result.

It must not trigger for ordinary CI failures, general performance questions, release approval by itself, application benchmarks, production deployment, or requests unrelated to the OnMaruBE repository.

An unspecified experiment action resolves to `dry-run`. Read-only preparation may proceed automatically. `dispatch` requires an explicit request in the current conversation; a previous dry-run or general request to improve CI is not permission to dispatch. The skill never retries a dispatch whose response may have been lost.

The operator flow is:

1. Confirm the current repository is `YRootLab/OnMaru-backend` and the candidate is a clean, pushed `feature/*` branch.
2. Run the Toolkit dry-run and present the immutable baseline/candidate SHAs, scope, policy version, workflow, required sample count, and integration-gate state.
3. Dispatch only after an explicit execute request. Let the Toolkit revalidate all mutable state immediately before POST.
4. Wait for the exact run/attempt from the receipt; never substitute the newest run.
5. Summarize measurements, exclusions, failure rate, verdict, evidence links, and whether the result is online-verified or offline replay.

## Failure and safety behavior

Branch, authentication, clean-tree, remote identity, workflow, integration-gate, attestation, artifact, source/test-plan, timeout, and comparability failures remain fail-closed. The skill translates stable Toolkit error codes into concise Korean explanations and a safe next action, but never edits evidence or bypasses a gate.

An exit-zero `inconclusive` result is not success or regression. The skill reports why the comparison is inconclusive and does not automatically add samples, rerun failed work, or promote a release. Credentials, raw API responses, untrusted artifact contents, and consumer source are never copied into skill output or committed files.

## Verification

Verification has four layers:

1. Structure: frontmatter, required files, relative links, and repository-local discovery guidance.
2. Trigger evals: positive OnMaru experiment prompts and negative ordinary-CI, generic-performance, release-only, and non-OnMaru prompts.
3. Behavior evals: default dry-run, explicit dispatch, lost-response non-retry, stable error explanations, and `inconclusive` reporting.
4. Integration: OnMaruBE repository verification plus the single trusted dispatch required by #555/#556. Fake CLI fixtures cover helper argument construction and exit/output preservation without making network dispatches.

The skill implementation is not considered complete from prompt evaluation alone. #555/#556 must validate the real workflow and signed artifact contract before Toolkit #122 and root #115 can close.

## Ownership migration sequence

1. Implement and verify OnMaruBE #568 in `skills/onmaru-ci-benchmark-experiment/`.
2. Merge the OnMaruBE PR before changing upstream ownership links.
3. Replace Agent Toolkit #58 references in Toolkit #115 and #122 with OnMaruBE #568, preserving #555/#556 dependencies.
4. Comment on Agent Toolkit #58 that no implementation was moved because it contained only a proposal, link OnMaruBE #568 and the merged PR, then close it as transferred.
5. Keep Toolkit #122 open until the real #555/#556 integration dispatch succeeds; keep root #115 open until all remaining consumer gates pass.

This order avoids a period where the root issue points to a nonexistent skill or a closed issue without an active replacement.

## Rejected alternatives

- Agent Toolkit ownership was rejected because the contract is intentionally bound to OnMaruBE and would present a project-specific action as reusable.
- CI Toolkit ownership was rejected because the skill's trigger and operator context change with the consumer workflow, not with the reusable evidence engine alone.
- Dual copies under `skills/` and `.agents/skills` were rejected because they create drift and unclear precedence.
- Generalizing Toolkit #122 was rejected because its strict OnMaru identity, signer, and workflow constraints are security boundaries rather than accidental configuration.

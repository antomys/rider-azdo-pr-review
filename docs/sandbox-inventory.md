# Sandbox inventory

Every artifact created in `igorvolokhovych/test-pr-reviewer`, so it can be cleaned up (see the cleanup section in [sandbox-plan.md](sandbox-plan.md)). Identity ids are not recorded here.

## Created 2026-10-20 (research run, before the sandbox plan)

| Artifact | Details |
|---|---|
| `main` | Initial commit "Initial sample project" (the repository was empty) |
| `feature/review-sample` | 2 pushes |
| PR 1 | "Sample change for review testing"; no reviewers; 2 iterations; 6 threads (1 system, 5 text) |

## Created 2026-10-20 16:03 UTC (sandbox plan, Phase 5)

| Plan id | Artifact | Details |
|---|---|---|
|  | branch `test/f2-required` | from `main` |
|  | branch `test/f2-optional` | from `main` |
|  | branch `test/f2-team` | from `main` |
|  | branch `test/f4-update` | from `main` |
| S2-A | PR 2 | `test/f2-required` → `main`; reviewers: test identity (required) |
| S2-B | PR 3 | `test/f2-optional` → `main`; reviewers: test identity (optional, vote 5) |
| S2-C | PR 4 | `test/f2-team` → `main`; reviewers: team "test-pr-reviewer Team" only |
| S3-D | PR 5 | `test/f4-update` → `main`; reviewers: test identity (required) |
|  | branch `test/empty-pr` | from `main` |
| S4-E | PR 6 | `test/empty-pr` → `main`; no reviewers; identical to main |

## Phase 5 results (read-only checks, 2026-10-20)

Verified:
- `reviewerId={me}` returns PRs 2, 3 and 5; PR 1 is absent. `reviewerId={teamId}` returns PR 4 only, so a team-only assignment is not matched by my own id: F2 must query my id and each of my team ids (settles D25).
- PR 3: my vote is 5 and `isRequired` is false. Casting the vote added 1 system thread.
- The PR author can add themselves as a reviewer and vote.
- Azure DevOps accepted PR 6 with source equal to target (no fallback commits needed); it has no file changes.

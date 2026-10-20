# Sandbox plan: test pull requests for F1–F5

Status: **plan only; nothing in this document has been created yet.** The owner approves this list before Phase 5. After that, every created artifact is recorded in `docs/sandbox-inventory.md`.

Scope:
- **Where:** organization `igorvolokhovych`, project `test-pr-reviewer`, repository `test-pr-reviewer` only.
- **Content:** dummy C#/Markdown only.
- **What's protected:** no existing content is modified or deleted. New branches use the `test/` prefix.
- **Identity:** "test identity" = the PAT owner (the only user in the sandbox). Its id comes from `connectionData`; it isn't written here.

## Existing content (created 2026-10-20, kept as is)

| Artifact | Details | Used for |
|---|---|---|
| `main` | Base commit: `README.md`, `src/Calculator.cs`, `src/Legacy.cs`, `docs/notes.md` | Target branch for all test PRs |
| `feature/review-sample` | 2 pushes | Source of PR 1 |
| **PR 1** "Sample change for review testing" | Created by the test identity. **No reviewers.** 2 iterations (edit, add, delete, rename). 6 threads: 1 system (push), 5 text threads covering right-line, left-line, right-range, file-level and PR-level positions, with a reply, an `@mention`, and statuses active, fixed, wontFix, and closed then reopened | Threads with replies, resolved and active (F5 data); "not a reviewer" case for F2 (AC4) |

## New artifacts to create (for approval)

Each new PR gets its own branch from the current `main` tip and one commit that edits a dummy file.

| Id | Branch | PR title | Reviewers | Purpose |
|---|---|---|---|---|
| S2-A | `test/f2-required` | "F2: required reviewer" | test identity, **required**, no vote | F2 AC1 |
| S2-B | `test/f2-optional` | "F2: optional reviewer with vote" | test identity, **optional**, vote **5** (approved with suggestions), cast via `PUT …/reviewers/{me}` | F2 AC2, vote mapping |
| S2-C | `test/f2-team` | "F2: team reviewer only" | project team `test-pr-reviewer Team` only (id from `teams?$mine=true`) | F2 AC3; settles whether a team-only assignment matches the team's `reviewerId` |
| S3-D | `test/f4-update` | "F3–F5: update after review" | test identity, required | F3/F4/F5. Starts at iteration 1; later steps below |
| S4-E | `test/empty-pr` | "Empty PR" | none | Empty-state UI for the changes list and diff |

Assumptions to check while creating them (each **UNVERIFIED**):
- the author of a PR can add themselves as a reviewer and vote;
- Azure DevOps accepts a PR whose source equals its target. If it refuses (S4-E), the fallback is two commits on `test/empty-pr`: add `tmp.txt`, then delete it, giving commits but no file changes.

## Steps and expected results

**S1: F1 sign-in.** No artifacts. Manual, in the IDE:
1. No PAT saved → sign-in panel appears.
2. Garbage PAT → "rejected", nothing saved.
3. Valid PAT → list loads.
4. The owner revokes a test PAT in the web UI → next action shows the panel with the reason.
5. PAT without Code scope → 403 scope message.

The owner creates or revokes test PATs in the web UI. The agent never handles them.

**S2: F2.** After creating S2-A/B/C, read-only checks:
- `GET …/pullrequests?searchCriteria.reviewerId={me}&searchCriteria.status=active` → expect S2-A, S2-B and S3-D (3 PRs); PR 1 absent.
- `GET …/pullrequests?searchCriteria.reviewerId={teamId}` → expect S2-C. Record whether it does or doesn't appear; this decides D25.
- `GET …/pullRequests/{S2-B}/reviewers` → my `vote` = 5 and `isRequired` = false.

**S3: F3–F5.** These steps run **later, during plugin testing**, one at a time, each only after the owner says go:

| Step | Action (sandbox write) | Expected in plugin |
|---|---|---|
| 1 | none: in the plugin, open S3-D, then mark reviewed | Hidden from default list; stored `reviewedIteration` = 1 |
| 2 | Push 1 commit editing 1 file to `test/f4-update` | Iteration 2. PR back as "Updated since review"; banner "iteration 1 → 2", 1 commit, 1 file |
| 3 | Mark reviewed again, then add a comment-only thread | Stays reviewed (no new iteration) |
| 4 | Force-push: a commit whose parent is `main`'s tip, replacing the branch | New iteration with reason forcePush; mark cleared. Record what `$compareTo` returns. Force-push through the push API is **UNVERIFIED**; fallback: delete and recreate the branch ref at a new commit |
| 5 | Mark reviewed, then push 1 commit to `main` touching an unrelated file | Record whether an iteration is created (F4 AC4). This changes `main`, so it needs explicit approval and can be skipped |

**S4: empty PR.** Open S4-E → changes list shows "No changed files"; no diff opens.

## Read-only confirmation (Phase 5, after creation)

Reports print HTTP status and counts only:
- each PR: `GET …/pullRequests/{id}` 200, reviewer count and `isRequired`/`vote` as expected;
- `iterations` count = 1 for every new PR;
- `threads` count = 1 for the new PRs (system thread only).

## Cleanup (separate approval)

Azure DevOps can't delete pull requests. Cleanup means:
- **Abandon** each new PR: `PATCH …/pullRequests/{id}` `{"status":"abandoned"}`.
- **Delete** its `test/*` branch: `POST …/refs` with `newObjectId` = 40 zeros and `oldObjectId` = the current tip.

Abandoned PRs stay visible in the "Abandoned" filter. Existing content (PR 1, `feature/review-sample`, `main`) is kept, because the test fixtures come from it.

## How the writes run

The agent's own writes to Azure DevOps were blocked by the IDE safety check on 2026-10-20. The Phase 5 script is therefore written by the agent and run by the owner with `!`. It:
- reads `AZDO_PAT` from the environment;
- prints only HTTP statuses and created ids;
- stops at the first failure;
- appends each created artifact to `docs/sandbox-inventory.md`.

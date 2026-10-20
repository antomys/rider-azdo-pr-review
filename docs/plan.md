# Plan: Azure DevOps PR Review plugin for JetBrains Rider

Status: specification.
- **Implemented and merged:** the core Azure DevOps client and provider (milestone 1).
- **In open PRs:** settings, the PR list, and PR details with diff (milestone 2a/2b).
- **Specified only:** F1–F5 ([features/F1-F5.md](features/F1-F5.md)) and the sandbox plan ([sandbox-plan.md](sandbox-plan.md)).
Research date: 2026-10-20. Items marked **UNVERIFIED** were not confirmed against a primary source or a live system.

## 1. Goal

A standalone Rider plugin for reviewing Azure DevOps pull requests inside the IDE. The review UX follows JetBrains' bundled GitLab merge-request integration: list, details, diff, inline threads, replies, resolve, @mentions. It adds an AI review that posts its findings as PR threads.

### Why standalone (decided 2026-10-20)

Rider 2026.2.2 bundles JetBrains' own "Azure DevOps" plugin (`com.intellij.azureDevops`, version 262.10315.191). Inspecting the installed jar showed:

- **What it has:** PR list with filters, PR details, create PR, diff, mark files viewed, reviewers, approve and approve-with-suggestions votes.
- **What it lacks:** no comment, thread, mention or AI classes were found. This is a class-name scan, not a runtime test.
- **Why we can't extend it:**
  - it declares no extension points;
  - it is pinned to exactly one build (`since-build` = `until-build` = 262.10315.191);
  - its source is not in `JetBrains/intellij-community`, so it is closed source.

Extending it would mean hooking internal classes. That breaks on every IDE update and may conflict with the JetBrains EULA. The new plugin is therefore standalone and coexists with the bundled one (see risks.md, R7).

## 2. Reference: which GitLab plugin provides the UX

| Plugin | ID / version (2026-10-20) | License | Role |
|---|---|---|---|
| GitLab (JetBrains, bundled) | `org.jetbrains.plugins.gitlab`; source [intellij-community/plugins/gitlab](https://github.com/JetBrains/intellij-community/tree/master/plugins/gitlab) | Apache-2.0 (per-file headers) | **All the merge-request review UX**: list, timeline, diff and editor review, threads, resolve, mentions, approve, merge |
| GitLab Duo (GitLab Inc., formerly "GitLab Workflow") | `com.gitlab.plugin` 3.50.0; [Marketplace 22325](https://plugins.jetbrains.com/plugin/22325-gitlab-duo); [source](https://gitlab.com/gitlab-org/editor-extensions/gitlab-jetbrains-plugin) | MIT | AI only: completion, Duo Chat, agents. **No MR review UI.** |

The bundled GitLab and GitHub plugins share the open-source `platform/collaboration-tools` module (`com.intellij.collaboration.*`, Apache-2.0). It ships in Rider 2026.2.2 as `lib/intellij.platform.collaborationTools*.jar` (verified locally). We reuse its **patterns and public APIs** and copy no code (see risks.md, R6).

## 3. Feature mapping

| GitLab feature (bundled plugin) | Azure DevOps equivalent (REST 7.1) | Status | Implementation note |
|---|---|---|---|
| MR list + filters (state, author, reviewer) | `GET …/pullrequests?searchCriteria.status/creatorId/reviewerId` | available | `status` defaults to `active`. Descriptions are truncated to 400 chars in lists. |
| Quick filters (mine, needs my review) | `creatorId` / `reviewerId` = my id | available | My id comes from `GET {org}/_apis/connectionData` → `authenticatedUser.id` (undocumented, but verified in the sandbox). `profile/me` returned 401 for a token without a profile scope. |
| Open MR → details | `GET …/pullrequests/{id}` + `/statuses` | available | |
| Timeline (description, discussions, events) | threads list (system threads included) | partial | Verified: a push creates a system thread (first comment `commentType: system`, no `status`, `properties` keys `CodeReviewRefName`, `CodeReviewRefNewCommits`, `CodeReviewRefNewHeadCommit`, …). There is no dedicated events API. |
| Diff, side-by-side and unified | iterations + iteration changes + Items/Blobs content | partial | The API gives no line hunks, so the IDE diff engine computes them locally from both file versions. |
| Change tree, "viewed" state | iteration changes | partial | The "viewed" state is kept locally, like the bundled plugins do. No REST API for viewed state was found (**UNVERIFIED**). |
| Inline comment on a line / range | `POST …/threads` with `threadContext` + `pullRequestThreadContext` | available | Needs `changeTrackingId` and the `iterationContext`. Verified round-trip: right-side single line, right-side range (start/end), left-side line, file-level (`filePath` only), PR-level (no context). |
| Thread replies | `POST …/threads/{t}/comments` with `parentCommentId` | available | |
| Resolve / reopen | `PATCH …/threads/{t}` `status` | available | Resolve = `fixed` (also `wontFix`, `closed`, `byDesign`); reopen = `active`. Verified numbers: 1 active, 2 fixed, 3 wontFix, 4 closed, 5 byDesign, 6 pending. Responses always return the names. |
| Edit / delete comment | `PATCH` / `DELETE …/comments/{c}` | available | Delete is a soft delete (`isDeleted`). |
| Reactions (emoji) | comment likes | partial | Only a single "like". |
| @mention autocomplete | Graph `subjectquery` (preview) for typeahead, then `graph/storagekeys/{descriptor}` for the GUID | partial | Verified in the sandbox: IMS `searchFilter=General` matches only exact names, while Graph `subjectquery` matches prefixes. The storage key equals the identity `id`. Encode the mention as `@<GUID>`. Verified: the comment is stored and returned as raw `@<GUID>`, and the thread `identities` map is empty. The plugin must resolve GUIDs itself via IMS `identityIds=` (cached). |
| Draft notes / batched review | no equivalent; threads publish immediately | not available | Could be emulated with `pending` thread status (**UNVERIFIED** semantics). Out of MVP. |
| Suggestions (`suggestion` blocks) | no apply API | not available | Render as Markdown only. |
| Approve / unapprove | `PUT …/reviewers/{me}` `vote` 10/5/0/-5/-10 | available | Richer than GitLab |
| Reviewer management | reviewers POST/PUT/DELETE, `isRequired` | available | |
| Merge / close / reopen | `PATCH …/pullrequests/{id}` `status`, `completionOptions`, `autoCompleteSetBy` | available | Out of MVP |
| Create MR | `POST …/pullrequests` | available | Out of MVP (the bundled plugin already does it) |
| Checkout branch | local git (`sourceRefName`) | available | Out of MVP |
| AI review (Duo Code Review runs server-side in GitLab) | none built in; our AI adapter posts the results as threads | n/a | Core differentiator |
| **F1** Sign-in: account selector in the tool window, "Log in again" on 401, tokens in `PasswordSafe` | `connectionData` to validate a PAT; PAT in `PasswordSafe` | available | PAT expiry can't be read with a PAT, so it's detected on use (401/203). See F1 |
| **F2** Quick filter `AssignedToMe` / `NeedMyReview`. GitLab's list shows no approval state | `searchCriteria.reviewerId` per my id and per team id from `teams?$mine=true`, then de-duplicated; `reviewers[]` with vote in the list response | partial | No group expansion in `reviewerId` (docs). A team GUID is accepted (verified); whether team-only PRs match is unverified (sandbox S2-C) |
| **F3** none in GitLab or GitHub at MR level; only per-file "viewed" | none server-side per user. PR properties are PR-wide and visible to all; `isFlagged`/`hasDeclined` have other meanings | not available → local | Local app-level store keyed by org/repoId/prId/userId (D21) |
| **F4** GitLab: per-file viewed state keyed by the SHA of the last commit that changed the file, so it resets on change | iteration ids (+1 per push, force-push, rebase or retarget); `lastMergeSourceCommit` in the list as a cheap signal | available | Compare the stored iteration and source commit, never time. See F4 |
| **F5** none in either reference plugin | `iterations/{n}/changes?$compareTo={m}` (verified delta); iteration commits are cumulative (verified), so diff the two lists | available | Banner plus a "since last view" diff chain. See F5 |

## 4. MVP scope

1. **Accounts:** an org URL plus a PAT, stored in IntelliJ `PasswordSafe`. One organization, multiple projects and repos.
2. **List PRs:** a tool window ("Azure DevOps PRs") with Active/Completed/Abandoned and quick filters "Created by me" and "Assigned to me".
3. **Open a PR:** details pane (title, description, branches, reviewers and votes, statuses) and a changed-file tree for the latest iteration.
4. **Side-by-side diff:** the platform diff viewer on base vs. iteration source. Left side = `commonRefCommit`; right side = `sourceRefCommit`.
5. **Inline comments:** gutter "+" on either side creates a thread. Range selection maps to start/end positions.
6. **Reply to threads:** inline in the diff and in a Threads panel.
7. **Resolve and reopen:** toggle between `fixed` and `active`. A dropdown offers the other statuses.
8. **@mention users:** completion in the comment editor via Graph subject query, a prefix search verified in the sandbox. The comment shows `@Display Name` and posts `@<GUID>`.
9. **AI review:** an action "Review with AI…". It shows a consent dialog that lists exactly which files and diffs will be sent and to which tool. The adapter returns findings. The user previews and selects them, and each selected finding is posted as an `active` thread on its file and line.

10. **F1–F5:** sign-in on first run, the "Assigned to me" view, reviewed marking, update detection and the update view, as specified in [features/F1-F5.md](features/F1-F5.md).

### Out of scope (MVP)

- Merge/complete, abandon, auto-complete
- Create PR
- Votes UI. Votes may come in v1.1; the bundled plugin already has them.
- Draft or batched reviews, suggestion apply, likes
- Editor-mode review outside the diff
- Entra ID OAuth (PAT only first)
- Azure DevOps Server (on-prem)
- Notifications and background polling. Update detection (F4) runs when the list loads or refreshes
- Syncing reviewed marks across machines (D21)
- GitLab provider implementation. Only the interface comes in the MVP.

## 5. Architecture (patterns only)

Following the bundled GitLab plugin:

- **MVVM:** coroutine-scoped view models expose `StateFlow`; Swing component factories bind to them.
- **Platform UI:** tool window plus `diff.DiffExtension` for inlays in the diff viewer.
- **Mentions:** a `completion.contributor`.
- **Layers:** `api` (HTTP + DTOs) → `provider` (the interface below) → `viewmodel` → `ui`. The AI adapters plug in at the `provider` level.

### 5.1 Provider interface (shared by Azure DevOps and a future GitLab provider)

Implemented in `src/main/kotlin/.../review/ReviewProvider.kt` (merged in #2):

```
interface ReviewProvider {
  val id: String; val capabilities: ProviderCapabilities     // thread statuses, open/resolved status, multiLine, draftReviews
  suspend fun currentUser(): ReviewUser
  suspend fun listReviews(repo: RepoRef, filter: ReviewFilter, page: Page = Page()): List<ReviewSummary>
  suspend fun getReview(ref: ReviewRef): ReviewDetails
  suspend fun listRevisions(ref: ReviewRef): List<Revision>
  suspend fun listChanges(ref: ReviewRef, revision: Revision): List<FileChange>        // vs merge base
  suspend fun fileContent(repo: RepoRef, path: String, commit: String): ByteArray?
  suspend fun listThreads(ref: ReviewRef): List<ReviewThread>
  suspend fun createThread(ref: ReviewRef, revision: Revision, anchor: ThreadAnchor?, body: String): ReviewThread
  suspend fun reply(ref, threadId, parentCommentId, body): ReviewComment
  suspend fun editComment(ref, threadId, commentId, body): ReviewComment
  suspend fun deleteComment(ref, threadId, commentId)
  suspend fun setThreadStatus(ref, threadId, status: ThreadStatus): ReviewThread
  suspend fun searchUsers(query: String, limit: Int = 10): List<ReviewUser>
  suspend fun resolveUsers(ids: Collection<String>): Map<String, ReviewUser>
  fun encodeMention(user: ReviewUser): String                                           // AzDO "@<GUID>", GitLab "@username"
  fun webUrl(ref: ReviewRef): String                                                    // added in #3
}
ThreadAnchor(path, lines: LineRange(side LEFT|RIGHT, start, end)?)   // null lines = file-level; null anchor = review-level
```

Azure DevOps details stay inside the provider: `changeTrackingId` and the iteration pair are looked up from the revision and never reach the UI.

**Additions for F2–F5 (specified, not implemented):**

| Change | For | Azure DevOps mapping |
|---|---|---|
| `ReviewFilter.assignedToMe: Boolean` | F2 | Union of `reviewerId` = me and = each `$mine` team id; de-duplicated |
| `ReviewSummary.reviewers: List<Reviewer>`, `ReviewSummary.sourceCommit: String` | F2, F4 | `reviewers[]` and `lastMergeSourceCommit` from the list response, with no extra calls |
| `Reviewer.hasDeclined`, `Reviewer.viaTeam: ReviewUser?` | F2 | `hasDeclined`; team entries (`isContainer`), with `votedFor` for my roll-up |
| `Revision.reasons: Set<RevisionReason>`, `Revision.created: Instant` | F5 | iteration `reason` flags, `createdDate` |
| `listChanges(ref, revision, since: Revision? = null)` | F5 | `$compareTo={since.id}` |
| `listRevisionCommits(ref, revision): List<CommitInfo>` | F5 | `iterations/{id}/commits` with `top`/`skip`; cumulative |

The reviewed and last-viewed state (F3/F4) lives in a local `ReviewStateStore`, not in the provider, so every provider shares it.

### 5.2 AI reviewer adapter

```
interface AiReviewer {
  val id: String                                   // "claude-code", "copilot-cli", "fake"
  fun isAvailable(): Availability                  // CLI on PATH, version, auth state; no network call
  suspend fun review(request: AiReviewRequest, cancel: CancellationToken): AiReviewResult
}
AiReviewRequest(prTitle, prDescription, files: List<FileDiff>, instructions: String, maxFindings: Int)
AiReviewResult(findings: List<Finding>, rawLog: String?)
Finding(path, side, startLine, endLine, severity: INFO|WARN|ERROR, title, body: Markdown, confidence: Double?)
```

- **Execution:** each adapter runs the CLI as a child process with a working directory scoped to a temp checkout or diff bundle. It has no IDE or project write access.
- **Output contract:** JSON validated against a schema. Findings whose `path` or line is not in the diff are dropped before preview.
- **Claude Code adapter** (`claude` 2.1.304 installed; flags verified via `--help`): `claude -p --output-format json --json-schema <schema> --permission-mode <restrictive> --allowedTools <read-only set>`. The prompt is passed on stdin.
- **Copilot CLI adapter:** the CLI is **not installed on this machine**, so its non-interactive flags and output format are **UNVERIFIED**. Confirm before implementing.
- **Fake adapter:** returns canned findings from a fixture file and is used in all automated tests.

## 6. Azure DevOps API reference (api-version 7.1, GA)

`7.2` exists only as `-preview` (verified against the docs, and the sandbox accepts `7.2-preview.1`). Base: `https://dev.azure.com/{org}/{project}/_apis/git/repositories/{repoId}`.

| Purpose | Call |
|---|---|
| List PRs | `GET …/pullrequests?searchCriteria.*&$top&$skip` |
| Get PR | `GET …/pullrequests/{id}` |
| Iterations | `GET …/pullRequests/{id}/iterations` → `sourceRefCommit`, `targetRefCommit`, `commonRefCommit` |
| Changes | `GET …/iterations/{n}/changes?$compareTo=&$top(≤2000)&$skip` → `changeTrackingId`, `changeType`, `item.path`, `originalPath`, `objectId`. Verified: for `delete`, `item.path` is null and the path is only in `originalPath`; `rename` carries both. |
| File content | `GET …/items?path=&versionDescriptor.version={sha}&versionDescriptor.versionType=commit&includeContent=true` or `GET …/blobs/{objectId}` |
| Threads | `GET/POST …/pullRequests/{id}/threads[?$iteration&$baseIteration]`, `PATCH …/threads/{t}` |
| Comments | `GET/POST …/threads/{t}/comments`, `PATCH/DELETE …/comments/{c}` |
| Reviewers | `GET/POST …/reviewers`, `PUT/PATCH/DELETE …/reviewers/{id}` |
| Identity search (typeahead) | `POST https://vssps.dev.azure.com/{org}/_apis/graph/subjectquery?api-version=7.1-preview.1` `{"query":"jo","subjectKind":["User"]}` → `descriptor` → `GET …/_apis/graph/storagekeys/{descriptor}` → GUID |
| Resolve GUIDs to names | `GET https://vssps.dev.azure.com/{org}/_apis/identities?identityIds={guid,…}` (GA) |
| Current user | `GET https://dev.azure.com/{org}/_apis/connectionData` → `authenticatedUser.id` (undocumented, verified) |

- **Thread position:** `threadContext.filePath` (leading `/`), `rightFileStart/End {line (1-based), offset}` for the new side, and `left…` for the base side. `pullRequestThreadContext.changeTrackingId` and `iterationContext.{first,second}ComparingIteration` are also required.
- **Thread statuses:** `active`, `fixed`, `wontFix`, `closed`, `byDesign`, `pending`, `unknown`.
- **Sources:**
  - [pull-requests](https://learn.microsoft.com/en-us/rest/api/azure/devops/git/pull-requests?view=azure-devops-rest-7.1)
  - [iterations](https://learn.microsoft.com/en-us/rest/api/azure/devops/git/pull-request-iterations?view=azure-devops-rest-7.1)
  - [iteration changes](https://learn.microsoft.com/en-us/rest/api/azure/devops/git/pull-request-iteration-changes/get?view=azure-devops-rest-7.1)
  - [threads](https://learn.microsoft.com/en-us/rest/api/azure/devops/git/pull-request-threads?view=azure-devops-rest-7.1)
  - [comments](https://learn.microsoft.com/en-us/rest/api/azure/devops/git/pull-request-thread-comments?view=azure-devops-rest-7.1)
  - [reviewers](https://learn.microsoft.com/en-us/rest/api/azure/devops/git/pull-request-reviewers?view=azure-devops-rest-7.1)
  - [identities](https://learn.microsoft.com/en-us/rest/api/azure/devops/ims/identities/read-identities?view=azure-devops-rest-7.1)
  - [@mentions](https://learn.microsoft.com/en-us/azure/devops/organizations/notifications/at-mentions?view=azure-devops)
  - [rate limits](https://learn.microsoft.com/en-us/azure/devops/integrate/concepts/rate-limits?view=azure-devops)

## 7. Build target (verified 2026-10-20)

| Item | Value | Evidence |
|---|---|---|
| IDE | Rider 2026.2.2, build `RD-262.10315.191` | Local `product-info.json`; JetBrains releases feed |
| Platform dependency | `riderRD:2026.2.2` | JetBrains releases Maven repo returns HTTP 200 for the artifact |
| Compatibility | `sinceBuild=262`, `untilBuild=262.*` (default; see open-decisions.md) | |
| IntelliJ Platform Gradle Plugin | `org.jetbrains.intellij.platform` **2.19.0** (latest, 2026-09-14) | plugins.gradle.org metadata |
| JDK toolchain | **25**: platform classes are class-file version 69; bundled JBR 25.0.4 | Inspected `intellij.platform.collaborationTools.jar` |
| Also covered | `RD-262.8665.400` is **Rider 2026.2.0.2** (release, 2026-08-03), not an EAP. It's on the same `262` branch, so `sinceBuild=262` covers it. An earlier report said it wasn't found; that was wrong, because only the EAP feed had been queried | JetBrains releases feed (`type=eap,release`), checked 2026-10-20 |

## 8. Test strategy

- **No live calls in automated tests.** CI has no `AZDO_PAT`, and any un-stubbed request fails the test.
- **Recorded HTTP fixtures:**
  - Record JSON responses once, manually, against the sandbox `igorvolokhovych/test-pr-reviewer` using read-only calls.
  - Source data: sandbox PR 1 (branch `feature/review-sample`) has 2 iterations, an edit, an add, a delete and a rename, plus 5 threads covering every position type, a reply, a mention and a mix of statuses.
  - Before committing, scrub them: remove auth headers, replace identities and emails with placeholders, and keep GUID shapes.
  - Replay them with a local HTTP server built on the JDK's `HttpServer` (`FixtureServer` in tests; D17).
  - Write calls (create thread, reply, resolve) are tested by **asserting the request bodies** against expected JSON, not by recording live writes.
- **Unit tests:** position mapping (diff line → `threadContext` + `iterationContext`), mention encoding and decoding, thread-state mapping, pagination (`nextSkip`), and rate-limit handling (`Retry-After` on 200 and on 429).
- **Provider contract tests:** one shared suite runs against the Azure DevOps provider (fixtures) and a fake provider. A future GitLab provider will reuse it.
- **AI:** only the fake adapter in tests.
  - Claude and Copilot adapters get parser tests on captured sample outputs. No process launch in CI.
  - Sample outputs are produced manually with dummy code only.
- **UI:** view models are Swing-free and unit-tested with a `FakeReviewProvider`; manual checklist in a sandbox IDE (`runIde`).
- **F1–F5:** the test cases listed per feature in [features/F1-F5.md](features/F1-F5.md). F4's clearing rule is a pure function with table tests. Manual checks follow [sandbox-plan.md](sandbox-plan.md) S1–S4.
- **Plugin verifier:** `verifyPlugin` against 262 builds. Flag internal API usage.
- **Manual sandbox testing** (later phase, by the owner): write calls against `test-pr-reviewer` only.

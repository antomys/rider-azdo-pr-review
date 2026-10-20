# Plan: Azure DevOps PR Review plugin for JetBrains Rider

Status: research and specification only. No application code yet.
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
| Quick filters (mine, needs my review) | `creatorId` / `reviewerId` = my id | available | My id comes from `profile/me` or `connectionData` (**UNVERIFIED** which one) |
| Open MR → details | `GET …/pullrequests/{id}` + `/statuses` | available | |
| Timeline (description, discussions, events) | threads list (system threads included) | partial | System threads (`commentType: system`) stand in for events; there is no dedicated events API. |
| Diff, side-by-side and unified | iterations + iteration changes + Items/Blobs content | partial | The API gives no line hunks, so the IDE diff engine computes them locally from both file versions. |
| Change tree, "viewed" state | iteration changes | partial | The "viewed" state is kept locally, like the bundled plugins do. No REST API for viewed state was found (**UNVERIFIED**). |
| Inline comment on a line / range | `POST …/threads` with `threadContext` + `pullRequestThreadContext` | available | Needs `changeTrackingId` and the `iterationContext`. Multi-line works via start/end positions. |
| Thread replies | `POST …/threads/{t}/comments` with `parentCommentId` | available | |
| Resolve / reopen | `PATCH …/threads/{t}` `status` | available | Resolve = `fixed` (also `wontFix`, `closed`, `byDesign`); reopen = `active`. Send the string names. |
| Edit / delete comment | `PATCH` / `DELETE …/comments/{c}` | available | Delete is a soft delete (`isDeleted`). |
| Reactions (emoji) | comment likes | partial | Only a single "like". |
| @mention autocomplete | IMS `identities?searchFilter=General` (GA); Graph subjectquery (preview) | partial | Encode as `@<GUID>`. Which GUID to use, and whether prefix search works, are **UNVERIFIED** and must be checked in the sandbox. |
| Draft notes / batched review | no equivalent; threads publish immediately | not available | Could be emulated with `pending` thread status (**UNVERIFIED** semantics). Out of MVP. |
| Suggestions (`suggestion` blocks) | no apply API | not available | Render as Markdown only. |
| Approve / unapprove | `PUT …/reviewers/{me}` `vote` 10/5/0/-5/-10 | available | Richer than GitLab |
| Reviewer management | reviewers POST/PUT/DELETE, `isRequired` | available | |
| Merge / close / reopen | `PATCH …/pullrequests/{id}` `status`, `completionOptions`, `autoCompleteSetBy` | available | Out of MVP |
| Create MR | `POST …/pullrequests` | available | Out of MVP (the bundled plugin already does it) |
| Checkout branch | local git (`sourceRefName`) | available | Out of MVP |
| AI review (Duo Code Review runs server-side in GitLab) | none built in; our AI adapter posts the results as threads | n/a | Core differentiator |

## 4. MVP scope

1. **Accounts:** an org URL plus a PAT, stored in IntelliJ `PasswordSafe`. One organization, multiple projects and repos.
2. **List PRs:** a tool window ("Azure DevOps PRs") with Active/Completed/Abandoned and quick filters "Created by me" and "Assigned to me".
3. **Open a PR:** details pane (title, description, branches, reviewers and votes, statuses) and a changed-file tree for the latest iteration.
4. **Side-by-side diff:** the platform diff viewer on base vs. iteration source. Left side = `commonRefCommit`; right side = `sourceRefCommit`.
5. **Inline comments:** gutter "+" on either side creates a thread. Range selection maps to start/end positions.
6. **Reply to threads:** inline in the diff and in a Threads panel.
7. **Resolve and reopen:** toggle between `fixed` and `active`. A dropdown offers the other statuses.
8. **@mention users:** completion in the comment editor via IMS identity search. The comment shows `@Display Name` and posts `@<GUID>`.
9. **AI review:** an action "Review with AI…". It shows a consent dialog that lists exactly which files and diffs will be sent and to which tool. The adapter returns findings. The user previews and selects them, and each selected finding is posted as an `active` thread on its file and line.

### Out of scope (MVP)

- Merge/complete, abandon, auto-complete
- Create PR
- Votes UI. Votes may come in v1.1; the bundled plugin already has them.
- Draft or batched reviews, suggestion apply, likes
- Editor-mode review outside the diff
- Entra ID OAuth (PAT only first)
- Azure DevOps Server (on-prem)
- Notifications and polling
- GitLab provider implementation. Only the interface comes in the MVP.

## 5. Architecture (patterns only)

Following the bundled GitLab plugin:

- **MVVM:** coroutine-scoped view models expose `StateFlow`; Swing component factories bind to them.
- **Platform UI:** tool window plus `diff.DiffExtension` for inlays in the diff viewer.
- **Mentions:** a `completion.contributor`.
- **Layers:** `api` (HTTP + DTOs) → `provider` (the interface below) → `viewmodel` → `ui`. The AI adapters plug in at the `provider` level.

### 5.1 Provider interface (shared by Azure DevOps and a future GitLab provider)

Language-neutral sketch (Kotlin in implementation):

```
interface ReviewProvider {
  val id: ProviderId                                // "azure-devops", "gitlab"
  suspend fun currentUser(): ReviewUser
  suspend fun listReviews(repo: RepoRef, filter: ReviewFilter, page: Page): Paged<ReviewSummary>
  suspend fun getReview(ref: ReviewRef): ReviewDetails          // title, desc, branches, reviewers, state
  suspend fun listRevisions(ref: ReviewRef): List<Revision>     // AzDO iterations / GitLab diff versions
  suspend fun listChanges(ref: ReviewRef, rev: Revision, base: Revision?): List<FileChange>
  suspend fun fileContent(repo: RepoRef, path: String, commit: CommitSha): ByteArray?
  suspend fun listThreads(ref: ReviewRef, rev: Revision?): List<ReviewThread>
  suspend fun createThread(ref: ReviewRef, anchor: ThreadAnchor?, body: Markdown): ReviewThread
  suspend fun reply(ref: ReviewRef, thread: ThreadId, body: Markdown, parent: CommentId?): ReviewComment
  suspend fun editComment(ref: ReviewRef, thread: ThreadId, comment: CommentId, body: Markdown): ReviewComment
  suspend fun deleteComment(ref: ReviewRef, thread: ThreadId, comment: CommentId)
  suspend fun setThreadState(ref: ReviewRef, thread: ThreadId, state: ThreadState)
  suspend fun searchUsers(query: String, limit: Int): List<ReviewUser>
  fun encodeMention(user: ReviewUser): String                   // AzDO "@<GUID>", GitLab "@username"
  val capabilities: ProviderCapabilities                        // multiLine, draftReviews, reactions, threadStates…
}

ThreadAnchor(path, side: LEFT|RIGHT, startLine, endLine, revisionContext)  // provider maps to its own position model
ThreadState: OPEN | RESOLVED | plus providerStates (AzDO: wontFix, closed, byDesign, pending)
```

The Azure DevOps mapping keeps `changeTrackingId` and the iteration pair inside `revisionContext`. These never leak into the UI.

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
| Changes | `GET …/iterations/{n}/changes?$compareTo=&$top(≤2000)&$skip` → `changeTrackingId`, `changeType`, `item.path`, `originalPath`, `objectId` |
| File content | `GET …/items?path=&versionDescriptor.version={sha}&versionDescriptor.versionType=commit&includeContent=true` or `GET …/blobs/{objectId}` |
| Threads | `GET/POST …/pullRequests/{id}/threads[?$iteration&$baseIteration]`, `PATCH …/threads/{t}` |
| Comments | `GET/POST …/threads/{t}/comments`, `PATCH/DELETE …/comments/{c}` |
| Reviewers | `GET/POST …/reviewers`, `PUT/PATCH/DELETE …/reviewers/{id}` |
| Identity search | `GET https://vssps.dev.azure.com/{org}/_apis/identities?searchFilter=General&filterValue=` |

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
| Note | The originally requested EAP build `RD-262.8665.400` was not found in JetBrains' feeds; the closest was 262.8665.68. Target changed to the installed release at the owner's request. | |

## 8. Test strategy

- **No live calls in automated tests.** CI has no `AZDO_PAT`, and any un-stubbed request fails the test.
- **Recorded HTTP fixtures:**
  - Record JSON responses once, manually, against the sandbox `igorvolokhovych/test-pr-reviewer` using read-only calls.
  - Before committing, scrub them: remove auth headers, replace identities and emails with placeholders, and keep GUID shapes.
  - Replay them with a local mock HTTP server (e.g. OkHttp MockWebServer or WireMock; tool choice is in open-decisions.md).
  - Write calls (create thread, reply, resolve) are tested by **asserting the request bodies** against expected JSON, not by recording live writes.
- **Unit tests:** position mapping (diff line → `threadContext` + `iterationContext`), mention encoding and decoding, thread-state mapping, pagination (`nextSkip`), and rate-limit handling (`Retry-After` on 200 and on 429).
- **Provider contract tests:** one shared suite runs against the Azure DevOps provider (fixtures) and a fake provider. A future GitLab provider will reuse it.
- **AI:** only the fake adapter in tests.
  - Claude and Copilot adapters get parser tests on captured sample outputs. No process launch in CI.
  - Sample outputs are produced manually with dummy code only.
- **UI:** `BasePlatformTestCase` / light fixtures for view models; manual test checklist in Rider for diff inlays.
- **Plugin verifier:** `verifyPlugin` against 262 builds. Flag internal API usage.
- **Manual sandbox testing** (later phase, by the owner): write calls against `test-pr-reviewer` only.

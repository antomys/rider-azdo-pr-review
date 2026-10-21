# Review inventory

Scope: `antomys/rider-azdo-pr-review` at `origin/master` (`eaf1dce`, 2026-10-20), reviewed on branch `review/security-performance`. Everything below was read from the checked-out code; nothing is taken from the planning documents.

## What is implemented

A Rider 2026.2 plugin that lists Azure DevOps pull requests, shows a pull request's files, timeline and side-by-side diff, and lets the user comment (inline threads, replies, drafts), vote, add reviewers, complete or abandon the pull request. Authentication is a personal access token (PAT) kept in the IDE `PasswordSafe`. **There is no AI review path in the code**: nothing in `src/main` calls an AI service, spawns a CLI or sends code anywhere but the configured Azure DevOps organization. The AI review listed in `docs/plan.md` and `docs/risks.md` R5 is unbuilt ("Not in 0.1.0" in `docs/product-baseline.md`).

## Source files

| File | Purpose |
|---|---|
| `azdo/AzureDevOpsClient.kt` | HTTP transport over `java.net.http.HttpClient`: Basic auth with the PAT, `Retry-After` and 429 retry, JSON error message extraction, URL building and encoding. |
| `azdo/AzureDevOpsDtos.kt` | `kotlinx.serialization` DTOs for REST 7.1 payloads (pull requests, iterations, changes, threads, comments, reviewers, graph subjects, identities). |
| `azdo/AzureDevOpsMentions.kt` | Parses and renders `@<guid>` mentions in comment Markdown. |
| `azdo/AzureDevOpsProvider.kt` | `ReviewProvider` for Azure DevOps: all REST calls, DTO to model mapping, user and change-tracking-id caches. |
| `review/ReviewModel.kt` | Provider-neutral data model (`ReviewSummary`, `ReviewThread`, `FileChange`, ...). |
| `review/ReviewProvider.kt` | The provider interface the UI depends on, and `ReviewTarget`. |
| `settings/AzureDevOpsAccount.kt` | App-level persistent state: organization name in `azure-devops-pr-review.xml`; PAT read/written through `PasswordSafe`. |
| `settings/AzureDevOpsProjectSettings.kt` | Project-level state (workspace file): Azure DevOps project and repository names. |
| `settings/AzureDevOpsConfigurable.kt` | Settings page (organization, PAT field, project, repository). |
| `settings/AzureDevOpsConnection.kt` | Project service: builds and caches the provider from account and project settings; owns the project-lifetime coroutine scope. |
| `settings/ViewedFilesStore.kt` | App-level persistent map of viewed-file marks keyed `<pr web url>|<path>`. |
| `toolwindow/PullRequestToolWindowFactory.kt` | Creates the list tab, opens details tabs and timeline editors, wires per-tab coroutine scopes. |
| `toolwindow/PullRequestListViewModel.kt` | Swing-free list state: filters, list load, per-PR comment counts (4 parallel). |
| `toolwindow/PullRequestListPanel.kt` | List UI: search, filter chips, rows with avatars, vote icons, comment counts. |
| `toolwindow/PullRequestDetailsViewModel.kt` | Swing-free details state: details, latest iteration, changes, threads, viewed marks, drafts, submit review, navigation. |
| `toolwindow/PullRequestDetailsPanel.kt` | Details tab UI: header, Markdown description, reviewers, files tree, review status and action buttons. |
| `toolwindow/ReviewFilesTree.kt` | Changed-files tree with viewed dots and per-file comment counts. |
| `toolwindow/SubmitReviewPopup.kt` | Submit Review popup: summary comment and vote buttons. |
| `toolwindow/ViewedFiles.kt` | `ViewedFiles` interface and in-memory implementation for tests. |
| `timeline/TimelineFile.kt` | `LightVirtualFile` and `FileEditorProvider` for the "Timeline for #id" editor tab. |
| `timeline/TimelinePanel.kt` | Timeline UI: title, description, threads, system events, drafts, comment composer. |
| `diff/PullRequestDiff.kt` | Diff tab: `DiffRequestChain` per PR, loads both sides of a file from the provider, picks text or binary content. |
| `diff/ReviewDiffActions.kt` | Diff toolbar actions: previous/next file, files popup, Submit Review, previous/next comment. |
| `diff/ReviewDiffExtension.kt` | `DiffExtension` that renders threads and drafts as editor inlays, gutter markers and the "+" comment gutter icon. |
| `ui/Avatars.kt` | Per-provider avatar cache; loads pictures through the provider on `Dispatchers.IO`, paints initials meanwhile. |
| `ui/MarkdownPane.kt` | `JEditorPane` with `HTMLEditorKit` showing `convertMarkdownToHtml(markdown)`; links open in the browser. |
| `ui/CommentField.kt` | Multi-line comment input with `@` mention completion (calls the provider's user search per prefix change). |
| `ui/CommentComposer.kt` | Comment field plus avatar, hints, split button and error line; runs the send action on `Dispatchers.EDT`. |
| `ui/ThreadPanel.kt` | One thread: comments as `MarkdownPane`s, replies, drafts, reply box, resolve/reopen. |
| `ui/DraftPanel.kt` | One pending draft with Post now, edit and delete. |
| `ui/Badge.kt`, `ui/VoteIcons.kt` | Small presentation helpers. |

Tests (`src/test`): `FixtureServer` (local `HttpServer` replaying recorded JSON from `src/test/resources/fixtures/azdo`), `AzureDevOpsClientTest`, `AzureDevOpsProviderTest`, `AzureDevOpsMentionsTest`, `PullRequestListViewModelTest`, `PullRequestDetailsViewModelTest` (both on `FakeReviewProvider`), and `LiveSandboxTest` (skipped unless `AZDO_LIVE_TEST=1` and `AZDO_PAT` are set; not run in this review).

## plugin.xml

- `<depends>com.intellij.modules.platform</depends>` only. No other plugin dependencies, no `<permissions>`-style declarations exist in the IntelliJ plugin model; the plugin declares no actions, no listeners, no application or project listeners.
- Extensions: `toolWindow` (left, `PullRequestToolWindowFactory`), `projectConfigurable` under Tools (`AzureDevOpsConfigurable`), `diff.DiffExtension` (`ReviewDiffExtension`), `fileEditorProvider` (`TimelineEditorProvider`).
- Services are registered by annotation (`@Service`): `AzureDevOpsAccount` (app), `ViewedFilesStore` (app), `AzureDevOpsProjectSettings` (project), `AzureDevOpsConnection` (project).

## Build and dependencies

- Gradle 9.7.1 wrapper, Kotlin 2.4.21, IntelliJ Platform Gradle Plugin 2.19.0, JDK 25 toolchain, platform Rider 2026.2.2 (or a local `/Applications/Rider.app`).
- Runtime dependencies: none bundled. Kotlin stdlib, kotlinx.coroutines, kotlinx.serialization and the Markdown converter come from the IDE (`kotlin.stdlib.default.dependency=false`).
- Test dependencies: `org.junit.jupiter:junit-jupiter:6.1.3`, `junit-platform-launcher`.
- Baseline build on this machine (`./gradlew test --rerun-tasks -PplatformLocalPath=/Applications/Rider.app`): BUILD SUCCESSFUL in 25 s, 57 tests, 52 passed, 5 skipped (live), 0 failed, no Kotlin compiler warnings printed.

## Trust boundaries

### Where the PAT is read, held and sent

1. **Entered** in `AzureDevOpsConfigurable` (`passwordField`, `newPat` String). On apply it is written on a pooled thread via `AzureDevOpsAccount.setPat` into `PasswordSafe` under service name `Azure DevOps PR Review` + organization. It is never written to settings XML (`AccountState` only holds `organization`).
2. **Read** in `AzureDevOpsConnection.target()` on `Dispatchers.IO` via `AzureDevOpsAccount.pat()`.
3. **Held** in memory as: the `pat` String inside `AzureDevOpsConnection.Cached` (for change detection) and as the Base64 `authorization` header String in `AzureDevOpsClient`.
4. **Sent** only by `AzureDevOpsClient.send` as `Authorization: Basic base64(":" + pat)` on every request. The only URLs it is sent to are built from `client.orgUrl` (`https://dev.azure.com/<organization>`) and `client.identityUrl` (`https://vssps.dev.azure.com/<organization>`); `<organization>` is the user's settings string, inserted unencoded. The avatar endpoint (`/_api/_common/identityImage`) is under `orgUrl` too.
5. **Not logged**: there is no logging in `src/main` at all (no `Logger` usage). Error messages are built from the HTTP status and the service's JSON `message` field only.

### Hosts contacted

- `https://dev.azure.com/<organization>` (REST 7.1 and the avatar endpoint).
- `https://vssps.dev.azure.com/<organization>` (Graph subject query, storage keys, identities).
- Any URL a user clicks in rendered Markdown (`BrowserHyperlinkListener` → system browser), and any URL embedded as `<img src>` or similar in rendered HTML (fetched by Swing's `HTMLEditorKit`, without the PAT). See the security findings.
- `BrowserUtil.browse(vm.webUrl())` for "Open in Browser" (constructed from `orgUrl`, encoded project and repository, PR id).

### Where server data is rendered

- **Markdown → HTML → `JEditorPane`** (`MarkdownPane`): PR description (details panel, timeline), every comment body (`ThreadPanel`), draft bodies (`DraftPanel`), the PR title (escaped with `escapeMarkdown`, then wrapped in `**…**` plus a raw `<font>` or link).
- **`JBLabel` with an `<html>` prefix**: `TimelinePanel.item` renders `author.displayName` inside `<html><b>…</b>`.
- **Plain `JBLabel`/`SimpleColoredComponent`/tree renderer**: titles, names, branches, paths, error messages (`e.message`), system event lines.
- **Diff editor**: file contents fetched from the service are shown as `DiffContent` (text or binary); file names only pick the file type. No temp files are written; nothing from a diff is written to disk.
- **Tooltips**: file paths and times.

### Where data leaves the IDE

- To Azure DevOps only: comment bodies, thread status, votes, reviewer additions, PR status changes (complete, abandon). All go through `AzureDevOpsProvider` → `AzureDevOpsClient`.
- Nothing is sent to any AI service; there is no telemetry.
- Local persistence: organization (app XML), project and repository (workspace XML), viewed marks (app XML, roaming disabled), PAT (`PasswordSafe`).

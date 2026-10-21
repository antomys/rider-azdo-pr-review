---
type: entity
title: "Plugin codebase map"
tags: [architecture]
sources: [src-plan, src-engineering-notes, src-product-baseline]
created: 2026-10-20
updated: 2026-10-21
---
# Plugin codebase map

Kotlin sources in `src/main/kotlin/io/github/antomys/azdoprreview/`, layered `azdo` → `review` → view models → UI (MVVM: Swing-free view models expose `StateFlow`; panels collect them on the EDT). `ai/` holds the opt-in AI review.

| Package | What lives there |
|---|---|
| `ai/` | Opt-in AI review: `AiReviewFlow` (consent and preview dialogs), `AiReviewSession`, `AiReviewRequests`, `AiReviewPrompt`, `AiReviewer`, `ClaudeCodeReviewer` (with `ClaudeOutput`), `ProcessRunner`. See [[ai-review]] |
| `azdo/` | `AzureDevOpsClient` (HTTP, PAT auth, `Retry-After`, rejected-token detection), DTOs, `AzureDevOpsProvider` (implements `ReviewProvider`), mention encoding, `AzureDevOpsSignIn` (token check), `AzureDevOpsRemote` (Git remote parsing). See [[sign-in]] |
| `review/` | Provider-neutral model (`ReviewSummary`, `ReviewDetails`, `ReviewThread`, `Vote`, `ThreadAnchor`, …), the `ReviewProvider` interface (now also `listChanges(…, compareTo)`, `listCommits`, `myTeams`), `ReviewMarks` ([[reviewed-marks]]), `Assignments` ([[assigned-to-me]]) |
| `settings/` | Settings page (with the "AI Review" group), organization and tokens per organization (`PasswordSafe`), per-project repository, `ReviewLocation` (the Git remote first, else the settings), `AzureDevOpsConnection` (provider and rejection check), `ViewedFilesStore`, `ReviewMarksStore`, `AiReviewSettings` |
| `toolwindow/` | Tool window factory; list and details view models and panels; `SignInPanel`/`SignInViewModel`; `MarkReviewed`; `ReviewFilesTree`; `SubmitReviewPopup` |
| `timeline/` | "Timeline for #id" editor: `TimelineFile` (virtual file), `TimelineEditorProvider`, `TimelinePanel` |
| `diff/` | `PullRequestDiff.kt` (one reused diff tab; the changes-since-last-view tab), `ReviewDiffExtension` (threads, drafts, gutter in side-by-side and unified viewers), `UnifiedLines` ([[unified-diff]] line mapping), `ReviewDiffActions` (toolbar) |
| `ui/` | Shared components: `CommentField` (mentions), `CommentComposer`, `ThreadPanel`, `DraftPanel`, `Avatars`, `Badge`, `MarkdownPane` (with the `sanitizeHtml` allow-list), `PlainText` (`plainText()` for labels showing server text), `VoteIcons` |

**Key types:**
- `PullRequestDetailsViewModel` is per opened PR. It holds state, threads, viewed marks, the shown file, pending navigation, **drafts** and the update shown by the [[update-view]] banner.
- `PullRequestListViewModel` holds `ListFilters` (including Assigned to me, Show reviewed, Show declined) and comment counts.

Tests: `src/test/kotlin/...`, including `FakeReviewProvider`, `FakeAiReviewer`, `FixtureServer` and the opt-in `LiveSandboxTest`. See [[build-and-verify]] and [[ui-structure]].

Sources: [[src-plan]], [[src-engineering-notes]], [[src-product-baseline]]

---
type: entity
title: "Plugin codebase map"
tags: [architecture]
sources: [src-plan, src-engineering-notes]
created: 2026-10-20
updated: 2026-10-20
---
# Plugin codebase map

Kotlin sources in `src/main/kotlin/io/github/antomys/azdoprreview/`, layered `azdo` → `review` → view models → UI (MVVM: Swing-free view models expose `StateFlow`; panels collect them on the EDT).

| Package | What lives there |
|---|---|
| `azdo/` | `AzureDevOpsClient` (HTTP, PAT auth, `Retry-After`), DTOs, `AzureDevOpsProvider` (implements `ReviewProvider`), mention encoding |
| `review/` | Provider-neutral model (`ReviewSummary`, `ReviewDetails`, `ReviewThread`, `Vote`, `ThreadAnchor`, …) and the `ReviewProvider` interface |
| `settings/` | Settings page, organization and token (`PasswordSafe`), per-project repository, `ViewedFilesStore` |
| `toolwindow/` | Tool window factory; list and details view models and panels; `ReviewFilesTree`; `SubmitReviewPopup` |
| `timeline/` | "Timeline for #id" editor: `TimelineFile` (virtual file), `TimelineEditorProvider`, `TimelinePanel` |
| `diff/` | `ReviewDiff` (one reused diff tab), `ReviewDiffExtension` (threads, drafts, gutter in the viewer), `ReviewDiffActions` (toolbar) |
| `ui/` | Shared components: `CommentField` (mentions), `CommentComposer`, `ThreadPanel`, `DraftPanel`, `Avatars`, `Badge`, `MarkdownPane` (with the `sanitizeHtml` allow-list), `PlainText` (`plainText()` for labels showing server text), `VoteIcons` |

**Key types:**
- `PullRequestDetailsViewModel` is per opened PR. It holds state, threads, viewed marks, the shown file, pending navigation and **drafts**.
- `PullRequestListViewModel` holds `ListFilters` and comment counts.

Tests: `src/test/kotlin/...`, including `FakeReviewProvider`, `FixtureServer` and the opt-in `LiveSandboxTest`. See [[build-and-verify]] and [[ui-structure]].

Sources: [[src-plan]], [[src-engineering-notes]]

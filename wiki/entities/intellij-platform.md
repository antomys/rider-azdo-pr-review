---
type: entity
title: "IntelliJ Platform in Rider 2026.2"
tags: [platform]
sources: [src-engineering-notes, src-risks, src-plan]
created: 2026-10-20
updated: 2026-10-21
---
# IntelliJ Platform in Rider 2026.2

Target: Rider 2026.2.2 (`RD-262.10315.191`), JDK 25, Kotlin 2.4 API. Public APIs only, and the verifier must stay clean ([[build-and-verify]]).

**Pitfalls already hit** (details in `docs/engineering-notes.md`):
- In a JTree subclass, call `setCellRenderer`/`setShowsRootHandles`; the Kotlin property assignments hit protected fields.
- In editor-embedded components (diff inlays), `InplaceButton` ignores clicks; use a `JBLabel` with a mouse listener.
- Popups anchored at the bottom of a panel: `PopupShowOptions.aboveComponent`.
- `TextFieldWithCompletion` has no height before `addNotify`.
- In a completion lookup, Enter must choose the item; mentions use a custom `PrefixMatcher`.
- `JEditorPane`: caret `NEVER_UPDATE`.
- Reuse the diff tab with a `ChainDiffVirtualFile` subclass plus `setCurrentRequest`.
- Toolbar actions go in `DiffUserDataKeys.CONTEXT_ACTIONS`.
- **`UnifiedDiffViewer`** (no `ApiStatus` on the class or the methods used; checked with `javap` on 262.10315.191): one editor for both sides; `transferLineToOnesideStrict` / `transferLineFromOnesideStrict` map lines and return -1 off-side or before the first diff; every rediff replaces the document, so inlays are laid out again after `onAfterRediff`; switching viewers creates a new one (`onViewerCreated` runs again) and disposes the old. `DiffDataKeys.DIFF_VIEWER` is public (reaching toolbar context actions at runtime is **UNVERIFIED**). See [[unified-diff]].
- **List context menu:** `PopupHandler.installSelectionListPopup` opens only on a selected row, so `PullRequestListPanel` adds a mouse listener, before it, that selects the row under a right click. (The engineering notes still mark the selection behavior **UNVERIFIED**.)
- Modal dialogs from coroutines (`MessageDialogBuilder.yesNoCancel(…).show(component)`) are shown through `invokeLater`, outside the coroutine that made the service call.
- `withBackgroundProgress(project, title, cancellable = true)` and `DialogWrapper` with a custom action pass the verifier; `Diff.buildChanges` runs in plain unit tests ([[ai-review]]).
- collaboration-tools list and search factories are internal, so the plugin builds its own components.
- Hot reload doesn't work; restart the sandbox.

Sources: [[src-engineering-notes]], [[src-risks]], [[src-plan]]

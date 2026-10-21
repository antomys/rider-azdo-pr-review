---
type: entity
title: "IntelliJ Platform in Rider 2026.2"
tags: [platform]
sources: [src-engineering-notes, src-risks, src-plan]
created: 2026-10-20
updated: 2026-10-20
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
- collaboration-tools list and search factories are internal, so the plugin builds its own components.
- Hot reload doesn't work; restart the sandbox.

Sources: [[src-engineering-notes]], [[src-risks]], [[src-plan]]

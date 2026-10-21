---
type: concept
title: "Unified diff view"
tags: [product, platform]
sources: [src-product-baseline, src-engineering-notes]
created: 2026-10-21
updated: 2026-10-21
---
# Unified diff view

Threads, drafts, markers and the gutter **+** also work in the platform's unified viewer (chosen with the viewer switcher in the diff toolbar). New in 0.2.0.

- **Line mapping:** `diff/UnifiedLines.kt` maps 0-based lines between each side and the one unified editor through a `Converter`, so the rules are unit-tested (`UnifiedLinesTest`) without a viewer. `UnifiedLines.of(viewer)` wraps `UnifiedDiffViewer.transferLineToOnesideStrict` / `transferLineFromOnesideStrict`, which return -1 when the line isn't on that side or before the first diff. See [[intellij-platform]].
- **Placement:** a base-side (left) thread sits under its deleted or unchanged line, an iteration-side (right) thread under its inserted or unchanged line; file-level threads above the first line.
- **Commenting:** the **+** on a deleted line comments on the base side, on an inserted or unchanged line on the iteration side, as in Azure DevOps. With a selection, the comment takes the clicked line's side and covers the selected lines that side has.
- **Navigation:** ↑ / ↓ order the shown file's threads by their unified position, then move across files as in side-by-side (`diff/ReviewDiffActions.kt`, via `DiffDataKeys.DIFF_VIEWER`).
- **Wiring:** `diff/ReviewDiffExtension.kt` installs the review UI on each viewer from `onViewerCreated`, using the unified editor for both sides. Switching views creates a new viewer and disposes the old one, and every rediff replaces the unified document, so inlays are laid out again after each rediff.
- **Verified** in the sandbox IDE on 2026-10-21 for the 0.2.0 baseline: base-side threads under deleted lines; the gutter + comments on the correct side; drafts ([[draft-review]]) render there and survive switching views.
- **UNVERIFIED:** that `DIFF_VIEWER` reaches the toolbar's context actions at runtime; whether a composer opened before a unified rediff keeps its line.

Sources: [[src-product-baseline]], [[src-engineering-notes]]

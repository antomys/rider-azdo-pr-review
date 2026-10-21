---
type: source
title: "Product baseline (0.2.0)"
tags: [product, process]
authors: [owner, agent]
raw: docs/product-baseline.md
ingested: 2026-10-20
created: 2026-10-20
updated: 2026-10-21
---
# Product baseline (0.2.0)

**The authority on current behavior.** The 0.1.0 behavior was approved by the owner on 2026-10-20 after a sandbox review (release `v0.1.0`, commit `8fd864e`). The owner asked for the 0.2.0 features on 2026-10-21; they were checked in a sandbox IDE that day.

- **Rule:** don't change, remove or restyle anything it describes unless the owner asks. Bug fixes toward it are fine. New features must not alter it. Update the file in the same PR when the owner approves a change. See [[change-rules]].
- **Covers:**
  - settings, the Pull Requests list (search, chips, rows), the details tab (header, files tree, review status, buttons, ⋮ menu) and the timeline tab;
  - the diff (inline threads, gutter, toolbar), the comment box, drafts, threads and mentions;
  - the Submit Review popup and viewed marks;
  - since the 2026-10-20 review: comments and descriptions render Markdown only; raw HTML shows as text and images aren't loaded (see [[src-security-review]]);
  - 0.2.0: the [[unified-diff]] view, the [[sign-in]] panel and rejected-token handling, [[assigned-to-me]] with the Show reviewed / Show declined toggles and the list context menu, [[reviewed-marks]], the [[update-view]] (banner and changes since last view), and the opt-in [[ai-review]].
- **Lists what's not built yet:** a second AI adapter (Copilot CLI); create pull request, checkout, likes, suggestion apply; Azure DevOps Server and OAuth sign-in. See [[plan-vs-implementation]].

Pages built on it: [[draft-review]], [[voting]], [[viewed-marks]], [[mentions]], [[ui-structure]], [[unified-diff]], [[sign-in]], [[assigned-to-me]], [[reviewed-marks]], [[update-view]], [[ai-review]].

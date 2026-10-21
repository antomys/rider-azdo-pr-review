---
type: source
title: "Plan and specification"
tags: [product, architecture, azure-devops]
authors: [owner, agent]
raw: docs/plan.md
ingested: 2026-10-20
created: 2026-10-20
updated: 2026-10-20
---
# Plan and specification

The original research and specification, written before the UI existed (2026-10-20).

- **Why standalone:** Rider's bundled `com.intellij.azureDevops` has no extension points, is pinned to one build and is closed source.
- **UX reference:** JetBrains' bundled GitLab plugin and the open-source collaboration-tools module. Copy patterns, never code.
- **Feature mapping** from GitLab features to Azure DevOps REST 7.1, the MVP scope, and the `ReviewProvider` interface (MVVM, StateFlow view models).
- **Planned AI adapter:** consent dialog, findings previewed before posting.
- **Build target:** Rider 2026.2.2, JDK 25, Gradle plugin 2.19.0.
- **Test strategy:** fixtures, a fake provider, no live calls in CI.

**Partly outdated:** several "out of scope" items were built (votes, drafts, merge/close). See [[plan-vs-implementation]].

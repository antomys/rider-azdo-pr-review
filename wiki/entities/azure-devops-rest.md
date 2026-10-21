---
type: entity
title: "Azure DevOps REST behavior"
tags: [azure-devops]
sources: [src-engineering-notes, src-plan, src-risks, src-features-f1-f5]
created: 2026-10-20
updated: 2026-10-20
---
# Azure DevOps REST behavior

api-version 7.1 (GA). Facts verified in the sandbox; the full list is in `docs/engineering-notes.md`, and endpoints are in `docs/plan.md` §6.

- **Votes:**
  - send `isRequired` with every vote, or a required reviewer becomes optional;
  - draft PRs reject votes;
  - `GET reviewers/{me}` returns 400 when the user isn't a reviewer.
  - See [[voting]].
- **Mentions:** stored as `@<id>`. Graph `subjectquery` matches display-name prefixes only and returns `value: null` on no match. See [[mentions]].
- **Threads:**
  - system threads have no status;
  - statuses 1–6 are active, fixed, wontFix, closed, byDesign, pending;
  - comment edits change `lastContentUpdatedDate`;
  - delete is a soft delete.
- **PR fields:** `labels` is `null` when there are none; `mergeStatus` is `conflicts` when it can't merge; `lastMergeSourceCommit` is needed to complete.
- **Avatars:** `_api/_common/identityImage?id=` works with PAT basic auth.
- **No API for viewed marks:** see [[viewed-marks]].
- **Errors:** 401 or 203 means the token was rejected (see the F1–F5 mapping); honour `Retry-After`.
- **UNVERIFIED:** thread re-tracking after pushes; complete and abandon against a live PR (only request bodies are tested).

Sources: [[src-engineering-notes]], [[src-plan]], [[src-risks]], [[src-features-f1-f5]]

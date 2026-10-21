---
type: entity
title: "Azure DevOps REST behavior"
tags: [azure-devops]
sources: [src-engineering-notes, src-plan, src-risks, src-features-f1-f5]
created: 2026-10-20
updated: 2026-10-21
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
- **No API for viewed marks:** see [[viewed-marks]]. [[reviewed-marks]] are local too.
- **Sign-in check** ([[sign-in]]):
  - `GET {org}/_apis/connectionData` returns the token's user with any valid token, whatever its scopes;
  - `GET {org}/{project}/_apis/git/repositories/{repo}` returns 200 with the repository for a valid PAT;
  - a wrong PAT, or none, on `connectionData` gets **302** to `…vssps.visualstudio.com/_signin…`, not 401. The JDK client doesn't follow redirects, so a 3xx whose `Location` contains `_signin` is mapped to "rejected";
  - an unknown organization gives 404 with HTML; an unknown repository gives 404 with `TF401019`.
- **Teams** ([[assigned-to-me]]): `…/projects/{project}/teams?$mine=true` lists the user's direct teams. `searchCriteria.reviewerId` doesn't expand teams. In `reviewers[]`, `isRequired` is omitted when false; a team has `isContainer: true` and `displayName` `[<project>]\<team>`.
- **Iterations** ([[update-view]]): `reason` is text (e.g. `"push"`); `…/iterations/{id}/commits` is cumulative, newest first, and pages with `top`/`skip` (`$top` is ignored); `…/iterations/{latest}/changes?$compareTo={earlier}` returns only the delta.
- **Errors:** 401, 203 or a redirect to sign-in means the token was rejected (see the F1–F5 mapping); honour `Retry-After`.
- **UNVERIFIED:** thread re-tracking after pushes; complete and abandon against a live PR (only request bodies are tested); the 401 and 203 rejection forms; 401 vs 403 for a missing scope; how several iteration reasons are written; `votedFor`'s shape; `$compareTo` after a rebase or force-push.

Sources: [[src-engineering-notes]], [[src-plan]], [[src-risks]], [[src-features-f1-f5]]

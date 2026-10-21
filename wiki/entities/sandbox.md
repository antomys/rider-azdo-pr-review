---
type: entity
title: "Azure DevOps sandbox"
tags: [testing, process]
sources: [src-sandbox-plan, src-sandbox-inventory]
created: 2026-10-20
updated: 2026-10-20
---
# Azure DevOps sandbox

Organization `igorvolokhovych`, project and repository `test-pr-reviewer`. The only place the plugin or scripts may write.

- **Credentials:** the token comes from the `AZDO_PAT` environment variable for scripts and live tests. Never print, log or commit it.
- **Test PRs:**
  - 1–6: sample and F2–F4 cases;
  - 7: "Add order pricing with customer discounts", rich threads, the test identity as required reviewer;
  - 8: draft with delete and rename;
  - 9: 14 files, no reviewers.
- **Rules:**
  - record every write in `docs/sandbox-inventory.md`;
  - restore votes and reviewer flags after tests (votes must keep `isRequired`);
  - don't merge or abandon sandbox PRs while testing.
- The live tests (`AZDO_LIVE_TEST=1`) are read-only.

Sources: [[src-sandbox-plan]], [[src-sandbox-inventory]]

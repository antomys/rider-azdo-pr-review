---
type: source
title: "F1–F5 feature specification"
tags: [product, open-question]
authors: [owner, agent]
raw: docs/features/F1-F5.md
ingested: 2026-10-20
created: 2026-10-20
updated: 2026-10-20
---
# F1–F5 feature specification

Specified, **not built** in 0.1.0:
- F1 sign-in on first run;
- F2 team-aware "Assigned to me";
- F3 reviewed marking;
- F4 update detection;
- F5 "what changed since I last looked" view.

Also defines the shared error-state mapping: 401/203 → token rejected, 403 → missing scope, 404, 429/`Retry-After`, 5xx, network. Error text never includes the PAT or response bodies.

Related: [[plan-vs-implementation]], [[azure-devops-rest]].

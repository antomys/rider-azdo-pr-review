---
type: source
title: "Security and performance review (2026-10-20)"
tags: [process, architecture, testing, platform]
authors: [agent]
raw: docs/review/
ingested: 2026-10-20
created: 2026-10-20
updated: 2026-10-21
---
# Security and performance review (2026-10-20)

Four documents under `docs/review/`: `inventory.md` (files, `plugin.xml`, trust boundaries), `security-findings.md`, `performance-findings.md`, `improvement-plan.md`, plus `final-report.md` with the status per finding.

- **Trust boundaries:** the PAT lives only in `PasswordSafe` and the `Authorization` header; the only hosts are `dev.azure.com/<org>` and `vssps.dev.azure.com/<org>`; the JDK `HttpClient` never follows redirects; no logging, no file writes, no AI path exists in the code (true at the review; 0.2.0 adds the opt-in [[ai-review]] and the reviewed-marks file of [[reviewed-marks]]). See [[plugin-codebase]].
- **Fixed in the review:** raw HTML in Markdown reaching the `JEditorPane` (S1: `sanitizeHtml` allow-list in `MarkdownPane`), `<html>`-prefixed server strings in labels and tooltips (S2: `plainText()` helper), response decoding on the EDT (P1: `withContext(Dispatchers.Default)` in the provider), one `storagekeys` call per mention hit per keystroke (P2: descriptor cache), `Avatars.waiting` retaining components (P3).
- **Backlog (low):** `Retry-After` cap, IDE certificate manager, id encoding in paths, PAT copy in `AzureDevOpsConnection`, diff size cap, list paging, cache eviction, comment-count cancellation.
- Changes the baseline in one line: see [[src-product-baseline]].

# Risks

Each risk has a likelihood/impact rating (L/M/H) and a mitigation. Research date: 2026-10-20.

## R1. Auth and PAT handling — impact H, likelihood M
- **Leaks:** PATs grant broad access and can leak through logs, crash reports, fixtures or settings XML.
- **Global PAT retirement:** global (all-organizations) PATs stop working on **1 Dec 2026** (Microsoft PAT doc, updated 2026-09-21). Only org-scoped PATs will keep working.
- **Mitigation:**
  - Store PATs only in IntelliJ `PasswordSafe` (macOS Keychain); never in `*.xml` settings.
  - Redact the `Authorization` header in all HTTP logging.
  - Fixture scrubber plus a pre-commit secret scan (e.g. gitleaks).
  - Document the minimum scopes: Code Read (read-only), and Code Read & Write or PR Threads for commenting, plus Identity Read and Graph Read. Recommend short expiry and org-scoped PATs.
- **Later:** Entra ID OAuth via MSAL public client (resource `499b84ac-1321-427f-aa17-267ca6975798`). The legacy Azure DevOps OAuth is deprecated (no new apps since Apr 2025, full retirement in 2026) and must not be used.

## R2. API rate limits — impact M, likelihood M
- **Limits:** 200 TSTUs per user in a sliding 5-minute window. Requests are first delayed (up to 30 s), then rejected with 429. The live sandbox returned no `X-RateLimit-*` headers on a normal request (expected below threshold).
- **Mitigation:**
  - Honour `Retry-After`, which can also arrive on HTTP 200.
  - Watch `X-RateLimit-Remaining` and show the delay in the UI.
  - Cache iterations, changes and file content per commit SHA. Fetch content lazily per opened file.
  - Use no background polling in the MVP; refresh manually.
  - Page with `$top`/`$skip`.

## R3. IntelliJ API changes across builds — impact H, likelihood H
- **Unstable APIs:** much of `com.intellij.collaboration.*` is `@ApiStatus.Internal` or experimental. Diff and inlay APIs change between major versions.
- **Pinned bundled plugin:** the bundled Azure DevOps plugin is pinned to a single build, which shows how JetBrains treats this area.
- **Mitigation:**
  - Target one platform branch (262.*).
  - Run the plugin verifier in CI.
  - Isolate platform-specific UI behind thin adapters. Prefer public APIs and accept some duplicated UI code over internal calls.
  - Budget a compatibility pass per major release (263, …).

## R4. Diff rendering limits — impact M, likelihood M
- **No line hunks:** Azure DevOps returns no line hunks, so diffs are computed locally. The local result can differ from the web UI for whitespace, line endings or renames.
- **Position drift:** thread positions are tied to iterations. After new pushes, the server re-tracks them (`trackingCriteria`), and our local line mapping may disagree.
- **Content we can't diff:** large or binary files. Also, a change list can exceed 2000 entries (`$top` maximum), so pagination is required.
- **Mitigation:**
  - Always post with explicit `iterationContext` and `changeTrackingId`. List threads with `$iteration`/`$baseIteration` so the server returns tracked positions.
  - Show file-level comments when a line can't be mapped.
  - Skip binary or large files with a notice, and test against fixtures with renames and CRLF.

## R5. Sending code to external AI services — impact H, likelihood M
- **Data leaving the machine:** the Claude Code and Copilot CLIs send prompts, including code, to their vendors. This may violate employer or client policies.
- **Mitigation:**
  - The AI review is off by default.
  - Per-run consent dialog lists exact files and diff sizes and the target tool.
  - Path include/exclude globs, with secret-like files (`.env`, `*.pem`, `appsettings.*.json`) excluded by default.
  - No automatic posting: the user previews and selects findings.
  - Run the CLIs with read-only tool permissions in a temp directory.
- **AI output is untrusted:** it is shown as text, never executed, and its file and line references are validated against the diff.
- **Testing:** no live AI calls in automated tests; only the fake adapter.

## R6. License constraints — impact M, likelihood L
- **Reference sources:**
  - The JetBrains bundled GitLab plugin and collaboration-tools are Apache-2.0 (per-file headers).
  - GitLab Duo is MIT.
  - The bundled Azure DevOps plugin is closed source under the JetBrains EULA; do not decompile, copy or hook it.
- **Policy:** copy patterns and UX, not code. If any Apache-2.0 snippet is ever copied, keep its header, add the license and NOTICE, and mark the change.
- **Names and logos:** don't use "GitLab", "JetBrains" or "Azure DevOps" logos or the bundled plugins' icons. "Azure DevOps" may appear only as a descriptive term.
- **Our own license:** still open (see open-decisions.md).

## R7. Coexistence with the bundled Azure DevOps plugin — impact M, likelihood H
- **Overlap:** Rider ships `com.intellij.azureDevops`, which has PR list, diff and reviewers. Users may see two tool windows, duplicate accounts and confusing actions.
- **Mitigation:**
  - Use a distinct plugin name and tool window name.
  - Don't depend on or modify the bundled plugin.
  - Document how to disable the bundled plugin if desired.
- **Watch:** JetBrains may add threads to its plugin, which could make this one redundant. Re-check each Rider release.

## R8. Unverified API behaviours — impact M, likelihood M
- **Verified 2026-10-20 (read-only sandbox calls):**
  - IMS `searchFilter=General` matches only exact names, not prefixes.
  - Graph `subjectquery` (preview API) matches prefixes. Its `descriptor` maps through `graph/storagekeys` to the identity `id`, which is the same as `connectionData.authenticatedUser.id`.
  - Relying on preview and undocumented endpoints is itself a risk; fall back to exact-match IMS search if they change.
- **Still unverified (the sandbox has 0 PRs):**
  - whether the server renders `@<GUID>` as a display name;
  - the shapes of system threads.
- **Status numbers:** only `active=1` is confirmed among the numeric thread statuses.
- **Viewed state:** no REST API was found for the "viewed" state.
- **Mitigation:** verify each against the sandbox with read-only calls plus one manual write session (owner-approved) before implementing that feature. Send enum string names.

# Open decisions

Each decision has a default that applies unless the owner overrides it.

| # | Decision | Default | Alternatives / notes |
|---|---|---|---|
| D1 | Approach vs. bundled `com.intellij.azureDevops` | **Standalone plugin** (decided by owner 2026-10-20) | Companion plugin hooking internals: rejected because it is fragile and has EULA risk. |
| D2 | Plugin display name | **"Azure DevOps PR Review"** | "AzDO Review". Must differ from the bundled "Azure DevOps". |
| D3 | Plugin ID / package | `io.github.antomys.azdoprreview` | |
| D4 | Repository name | `antomys/rider-azdo-pr-review` (private) | |
| D5 | Target IDE / platform | Rider **2026.2.2** (`RD-262.10315.191`), dependency `riderRD:2026.2.2` | The originally requested `RD-262.8665.400` was not found. |
| D6 | Compatibility range | `sinceBuild=262`, `untilBuild=262.*` | Open-ended `untilBuild`: rejected because of internal API churn (risks.md, R3). |
| D7 | Gradle plugin | `org.jetbrains.intellij.platform` **2.19.0** | Bump when a newer release appears. |
| D8 | JDK toolchain | **25** (platform class-file version 69) | |
| D9 | Language | Kotlin with coroutines | Matches the platform's collaboration-tools code. |
| D10 | Azure DevOps api-version | **7.1** (GA) | 7.2 is preview-only. |
| D11 | Auth | PAT in `PasswordSafe`; scopes Code (Read & Write), Identity (Read), Graph (Read) | Entra ID OAuth (MSAL) after the MVP. A token without the profile scope can't call `profile/me`, so use `connectionData`. |
| D12 | Mention search | **Graph subjectquery (preview) + storagekeys**: the only verified prefix search | IMS Identities (GA) for exact-match fallback and for resolving GUIDs. Never the undocumented IdentityPicker. |
| D13 | "Resolve" status | `fixed`; reopen = `active` | Dropdown for `wontFix`, `closed`, `byDesign`. |
| D14 | First AI adapter | **Claude Code CLI** (installed, flags verified) | Copilot CLI second. It isn't installed here and its flags are unverified. |
| D15 | AI output posting | Preview, then the user picks which findings to post | Auto-post: rejected (risks.md, R5). |
| D16 | HTTP client | Platform `HttpClient` / `HttpRequests` with kotlinx.serialization | Ktor or OkHttp: an extra dependency. |
| D17 | Test HTTP replay | OkHttp MockWebServer (test-only dependency) | WireMock: heavier. |
| D18 | Project license | **Proprietary / all rights reserved while private** | MIT or Apache-2.0 if open-sourced. |
| D19 | Commit / PR attribution | No AI or tool mentions in history, branch names or PR text (confirmed by owner 2026-10-20) | |
| D20 | GitLab provider | Interface only in the MVP; no implementation | |

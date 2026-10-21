# Final report: security and performance review

Branch `review/security-performance`, based on `master` (`6fc5de4`), 2026-10-20. Findings: `security-findings.md`, `performance-findings.md`; plan: `improvement-plan.md`.

## Status per finding

| Id | Severity | Finding | Fix | QA verdict |
|---|---|---|---|---|
| S1 | medium | Raw HTML in Markdown rendered by `JEditorPane` (remote image beacons, blind SSRF, odd links) | `sanitizeHtml` allow-list in `ui/MarkdownPane.kt`; tags end at `>` or end of input; `img` dropped; `href` only http/https/mailto; `font` keeps a validated `color` only | Fixed (iteration 2; iteration 1 found an end-of-input bypass and a timeline title regression, both fixed) |
| S2 | medium | Server strings starting with `<html>` rendered as HTML by labels and tooltips | `plainText()` helper (`ui/PlainText.kt`: `html.disable` + renderer reset) at every sink, escaped `<html>` tooltips, escaped name in the timeline header | Fixed |
| P1 | medium | Response handling and JSON decoding on the EDT after every write action | `withContext(Dispatchers.Default)` around every `client.send` in `AzureDevOpsProvider` | Fixed |
| P2 | medium | Up to 21 requests per mention keystroke | `descriptor → ReviewUser` cache in `graphSearch` | Fixed |
| P3 | medium | `Avatars.waiting` retains Swing components forever after a failed picture load | `done` set; register only while a load is in flight; drain on every outcome; re-check after registering | Fixed (iteration 2 closed a check-then-act race) |
| S3–S8, P4–P10 | low | See the findings documents | Not changed | Backlog, listed in `improvement-plan.md` |

Nothing critical or high was found. No dependency was added. Nothing sent to the service changed. There is no AI data path in the plugin.

## Tests

| | Before | After |
|---|---|---|
| Tests (total / passed / failed / skipped) | 57 / 52 / 0 / 5 | 68 / 63 / 0 / 5 |
| New regression tests | | `MarkdownHtmlTest` (7), `AzureDevOpsProviderThreadingTest` (2), `AvatarsTest` (2) |
| Build + tests + verifier | Compatible, 39 s | Compatible, 32–43 s |

Every new test was run against the pre-fix sources by the owning agent and again by QA: `handles responses off the calling dispatcher` failed with `response handled on the calling dispatcher: [edt-like …]`, `looks up a graph descriptor's storage key only once` with `expected: <1> but was: <2>`, `a failed or empty load stops registering components` with `waiting still holds the component after an empty load`, and the Markdown assertions with `<img in <p>Hi <img src="http://evil.example/t.png"> …`. All pass on the fixed code. The skipped tests are `LiveSandboxTest` (needs `AZDO_LIVE_TEST=1` and `AZDO_PAT`; not run).

## Evidence before and after

| Measure | Before | After |
|---|---|---|
| HTTP calls to load the list (fixture, 1 PR) | 3 | 3 (unchanged by design: `docs/product-baseline.md` fixes the per-PR comment count) |
| HTTP calls to open a PR | 5 | 5 |
| `storagekeys` calls for the same Graph hit across two searches | 2 | 1 (regression test) |
| Work on the EDT per write action | HTTP status handling + full thread-list JSON decode (5.5 ms for 600 threads, 10.8 ms end to end) | state update only; decode on `Dispatchers.Default` (regression test) |
| Components retained per user without a picture | one per render, unbounded | none after the load completes (regression test) |
| Raw `<img src="http://…">` in a comment | fetched by Swing on display | escaped as text (regression test, parsed with `ParserDelegator`) |

QA probed the sanitiser with the end-of-input HTML-block case, uppercase tags, newlines and `>` inside attributes, comments, `svg`/`object`/`iframe`/`meta`/`link`/`script`, nested quotes and CRLF attributes: Swing's parser saw no `img`, `form` or unsafe `a` in any case, and ordinary Markdown (bold, code, links, lists, headings, tables, task lists, strikethrough) was unchanged.

## Behaviour change

One line was added to `docs/product-baseline.md` under Comments, approved at the Phase 4 gate: "Comments and descriptions render Markdown only; raw HTML shows as text and images aren't loaded." The wiki pages `src-product-baseline`, `plugin-codebase`, `build-and-verify` and the new `src-security-review` were updated accordingly.

## Remaining risks

- Low findings S3–S8 and P4–P10 are unchanged (backlog in `improvement-plan.md`): no cap on `Retry-After`, JDK default TLS context rather than the IDE's certificate manager (UNVERIFIED whether that matters for corporate CAs), unencoded reviewer and team ids in paths, a second PAT copy in `AzureDevOpsConnection`, unbounded diff content size, list truncated at 50, caches without eviction, comment-count coroutines not cancelled with the list load.
- Residual S1 edge: an author's own malformed raw HTML (an unterminated tag at the end of an HTML block) now loses the text after it, since the remainder becomes the dropped tag's attributes. Only hand-written broken HTML is affected; converter output is never.
- P2's cache, like the existing `users` and `teamMembers` caches, holds a renamed user's old display name until the IDE restarts.
- The mention-id regex pass and DTO mapping in `listThreads` still run on the caller after the decode returns; cheap relative to the decode.
- The live sandbox tests were not run in this review.

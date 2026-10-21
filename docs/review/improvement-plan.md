# Improvement plan

Source: `security-findings.md` and `performance-findings.md` (2026-10-20). Only medium and higher findings become work items; the rest are backlog. Three work items, three owners with disjoint files, no new dependency, no change to what is sent to the service, nothing sent to any AI tool (there is no AI path).

## Work items

### W1 — Rendering: sanitise Markdown HTML and disable HTML in labels (S1, S2)

- **Owner area:** input and rendering.
- **Files:** `ui/MarkdownPane.kt`, `ui/ThreadPanel.kt`, `timeline/TimelinePanel.kt`, `toolwindow/PullRequestListPanel.kt`, `toolwindow/PullRequestDetailsPanel.kt`; new test `src/test/kotlin/io/github/antomys/azdoprreview/ui/MarkdownHtmlTest.kt` (and a label helper test if a helper is introduced).
- **Change:**
  1. In `MarkdownPane`, pass the converter output through a small allow-list sanitiser before `text = …`: keep `p, br, strong, em, b, i, s, del, code, pre, ul, ol, li, blockquote, h1–h6, hr, a, table, thead, tbody, tr, th, td, input` (task-list checkboxes); keep only `href` on `a` and only for `http:`, `https:`, `mailto:`; keep `type`, `checked`, `disabled` on `input`; drop `img` elements; escape any other tag as literal text (`&lt;…&gt;`) so authors see what they wrote. Ordinary Markdown output is unchanged.
  2. At the label sinks listed in S2, set `putClientProperty("html.disable", true)`; in `TimelinePanel.item` escape the display name with `StringUtil.escapeXmlEntities`; for the two tooltips that show server text, build `"<html>" + escapeXmlEntities(text)`.
- **Acceptance test:** the converter string from the findings (`<img …>`, `<a href="file:…">`, `<form>`, `<script>`) rendered through the pane's conversion contains none of `<img`, `<form`, `<script`, `file:`; `**bold** \`code\` [link](https://x) - item` still yields `<strong>`, `<code>`, `<a href="https://x">`, `<li>`; a label created for a title `<html><img src=x>` has `html.disable == true`. Full suite and verifier stay green.
- **Risk:** low. Visible change: raw HTML in descriptions or comments shows as text instead of being rendered, and remote images are no longer loaded (they needed the PAT or an external host anyway). This is the only item that alters the product baseline; `docs/product-baseline.md` gets one line under Comments: "Comments and descriptions render Markdown only; raw HTML shows as text and images aren't loaded."

### W2 — Provider: decode off the EDT and cache Graph lookups (P1, P2)

- **Owner area:** performance (threading and API volume).
- **Files:** `azdo/AzureDevOpsProvider.kt`; tests in `src/test/kotlin/io/github/antomys/azdoprreview/azdo/AzureDevOpsProviderTest.kt` or a new `AzureDevOpsProviderThreadingTest.kt`.
- **Change:**
  1. Wrap the bodies of `get` and `send` in `withContext(Dispatchers.Default)` so the HTTP wait, status handling and JSON decoding never run on the calling (EDT) dispatcher. Callers keep launching on `Dispatchers.EDT` and keep updating UI after the call returns.
  2. In `graphSearch`, cache `descriptor → ReviewUser` (a `ConcurrentHashMap`) and only call `storagekeys` for unknown descriptors.
- **Acceptance test:** (1) with a `clock` lambda that records the thread and a `Retry-After: 0` fixture header, calling `provider.getReview` from `runBlocking(newSingleThreadContext("EDT-like"))` shows the post-response `clock()` call on another thread, and the existing 22 provider tests still pass; (2) two `searchUsers` calls with the same Graph hit make exactly one `storagekeys` request on the fixture server.
- **Risk:** low. No observable behaviour change; dispatcher switch adds a thread hop per call (microseconds).

### W3 — Avatars: stop retaining components after a failed or empty load (P3)

- **Owner area:** performance (memory).
- **Files:** `ui/Avatars.kt`; new test `src/test/kotlin/io/github/antomys/azdoprreview/ui/AvatarsTest.kt`.
- **Change:** track completed loads; `icon()` registers a repaint target only while the load for that user is still in flight; the load's failure/null path removes any waiting set. Painting and the initials fallback are unchanged.
- **Acceptance test:** with a `ReviewProvider` whose `avatar` returns null, after the load completes `icon(user, 20, component)` does not keep `component` reachable (verify by `WeakReference` after `System.gc()` loops, or by reflecting on the waiting map being empty). The picture path still repaints a registered component when an image arrives.
- **Risk:** low. Headless test must avoid creating real Swing peers (use a plain `java.awt.Component` subclass or `JPanel` with `java.awt.headless=true`).

## Integration

- Branch: `review/security-performance` (from `origin/master`). Agents work in worktrees on `review/w1-rendering`, `review/w2-provider`, `review/w3-avatars`; the lead merges them one at a time into `review/security-performance`, running `./gradlew test buildPlugin verifyPlugin -PplatformLocalPath=/Applications/Rider.app` after each merge.
- Gate: W1 changes a described behaviour, so the owner confirms before Phase 5 starts.

## Backlog (low severity, not scheduled)

| Id | Item | Note |
|---|---|---|
| S3 | Cap `Retry-After` waits (e.g. 60 s), ignore negatives | `AzureDevOpsClient.kt:57` |
| S4 | Use the IDE `CertificateManager` SSL context if corporate-CA users report failures | UNVERIFIED need |
| S5 | `encode()` reviewer and team ids in paths | defence in depth |
| S6 | Hash instead of a second PAT copy in `AzureDevOpsConnection.Cached`; remove the old organization's PAT when the organization changes | credentials hygiene |
| S7 | Validate the organization name in settings | clearer error only |
| S8 | Cap diff file content size | changes what huge files show; ask owner |
| P4 | `coroutineScope` around comment-count launches; stale counts after Refresh | 3-line change; staleness is an owner decision |
| P5 | Incremental timeline and inlay rebuilds | larger UI change |
| P6 | "Load more" paging for the list; progress guard in `changeEntries` | feature + guard |
| P7 | Eviction or refresh for `teamMembers` and other caches | staleness |
| P9 | Fetch both diff sides with `async` | small latency win |
| P10 | Cache `currentUser` in the provider | saves 1 call per vote |

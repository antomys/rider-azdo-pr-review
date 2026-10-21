# Performance findings

Status after the fixes: see `final-report.md` (all medium findings Fixed by independent QA; low findings are backlog).

Reviewed on 2026-10-20 against `origin/master` (`eaf1dce`). Measurements come from the fixture server (`src/test/resources/fixtures/azdo`, recorded from sandbox PR 1) and from temporary JUnit tests run with `./gradlew test -PplatformLocalPath=/Applications/Rider.app`; no network calls.

Summary: 3 medium, 7 low.

## Measurements (before)

| What | Result |
|---|---|
| Build and unit tests, cold (`test --rerun-tasks`) | BUILD SUCCESSFUL in 25 s; 57 tests, 52 passed, 5 skipped (live), 0 failed. Class times: `AzureDevOpsClientTest` 0.289 s, `AzureDevOpsProviderTest` 0.199 s, `PullRequestDetailsViewModelTest` 0.035 s, `PullRequestListViewModelTest` 0.022 s, `AzureDevOpsMentionsTest` 0.006 s. |
| HTTP calls to load the list (1 PR in the fixture) | 3: `connectionData`, `pullrequests`, `pullRequests/1/threads` (comment count). With N pull requests: 2 + N, plus one `identities` call per PR whose threads mention someone not yet cached. |
| HTTP calls to open a PR | 5: `pullRequests/1`, `iterations`, `iterations/2/changes`, `threads`, `connectionData`. |
| HTTP calls per mention search (`searchUsers("te")`, empty team and graph fixtures) | 2 (`projects/…/teams`, `graph/subjectquery`). With real data: on first use 1 + one call per team (cached afterwards); on every later keystroke 1 `subjectquery` + one `storagekeys` call per hit, up to 20. |
| JSON decode of `threads.json` (6 threads, 10.5 KB) | 0.13 ms average (warm, 200 runs) |
| JSON decode of a 100× copy (600 threads, 734 KB) | 5.5 ms average (20 runs) |
| `provider.listThreads` end to end over loopback for the 600-thread list | 10.8 ms average (10 runs) |

## Findings

### P1 — Response handling and JSON decoding run on the EDT after every write action (medium, threading)

- **Where:** `azdo/AzureDevOpsProvider.kt:244-248` (`get`/`send` decode on the caller's dispatcher) and `azdo/AzureDevOpsClient.kt:53-69` (everything after `.await()`), called from coroutines launched with `Dispatchers.EDT` in `ui/CommentComposer.kt:106`, `ui/ThreadPanel.kt:192`, `ui/DraftPanel.kt:82`, `toolwindow/SubmitReviewPopup.kt:53`, `toolwindow/PullRequestDetailsPanel.kt:276,301`, `toolwindow/PullRequestListPanel.kt:180`.
- **Evidence (verified by code):** `http.sendAsync(...).await()` suspends without blocking, but the continuation resumes on the launching dispatcher. From an EDT launch, the status check, `response.body()`, `decodeToString()`, `json.decodeFromString` and the mention regex pass in `listThreads` all run on the EDT. Each comment, reply, resolve, vote and submit calls `reloadThreads()`, which decodes the full thread list.
- **Impact estimate (measured):** 0.1 ms for the 6-thread fixture; 5.5 ms decode and about 11 ms end to end for 600 threads, repeated after every action, on the UI thread. Not a freeze, but it scales linearly with discussion size and the IDE's EDT budget is a few ms per frame.
- **Fix:** in `AzureDevOpsProvider.get` and `send`, run the request and decode inside `withContext(Dispatchers.Default)` (or `IO`), so only the state update returns to the EDT. Regression test: give `AzureDevOpsClient` a `clock` lambda that records the current thread, return a `Retry-After` header so `clock()` is invoked after the response, call the provider from a single-thread dispatcher named like the EDT, and assert the post-response call did not run on that thread.

### P2 — Mention search makes up to 21 requests per keystroke (medium, API volume)

- **Where:** `azdo/AzureDevOpsProvider.kt:200-207` (`graphSearch`: one `storagekeys` GET per subject, `take(limit)`), `toolwindow/PullRequestDetailsViewModel.kt:220` (`limit` = `MAX_MENTION_RESULTS` = 20), `ui/CommentField.kt:162-165` (`restartCompletionOnAnyPrefixChange` + blocking wait for the search).
- **Evidence (verified by code; the fixture has no graph hits so the count was 2):** every prefix change restarts completion, each restart calls `searchUsers`, each call issues `subjectquery` and then a sequential `storagekeys` GET per hit; the descriptor-to-id result is never cached (`users` is keyed by id, not descriptor). Typing "Volo" costs 4 keystrokes × (1 + hits) requests. Azure DevOps throttles at 200 TSTUs per 5 minutes (`docs/risks.md` R2).
- **Fix:** cache `descriptor → ReviewUser` in the provider so repeated hits cost nothing, and skip `storagekeys` for descriptors already known. Keep the UI behaviour (suggestions on every keystroke) unchanged. Regression test with the fixture server: two searches with the same hit must issue one `storagekeys` request in total.

### P3 — `Avatars.waiting` retains Swing components forever for users without a picture (medium, memory retention)

- **Where:** `ui/Avatars.kt:40` (adds `repaint` to `waiting` whenever no image is cached), `:47-58` (`waiting.remove` only when an image arrived; a null or failed load leaves the set in place).
- **Evidence (verified by code):** after a failed or empty avatar load, `requested` contains the id so `load` returns at once, `images` stays empty, and every later `icon(user, size, component)` call adds the component to `waiting[user.id]` for good. `Avatars` instances live as long as the provider, which `AzureDevOpsConnection` caches for the project. Each timeline or thread re-render adds new `ThreadPanel`s and labels (with their `PullRequestDetailsViewModel` references) to the set.
- **Impact estimate:** proportional to renders × components per user without a picture; the sandbox returns generated images so this did not show there, but any 404 or network error on `identityImage` triggers it. A reviewer who keeps the IDE open all day on a busy PR accumulates every rendered comment panel.
- **Fix:** record finished loads (`done` set) and only register a component while a load is in flight; clear `waiting` in the failure path too. Regression test: a provider whose `avatar` returns null; after the load completes, `icon()` with a component must not retain it (check with a `WeakReference` after GC, or by reading the private set reflectively).

### P4 — One `threads` request per listed PR, never invalidated, not cancelled with the list load (low, API volume)

- **Where:** `toolwindow/PullRequestListViewModel.kt:127-144`.
- **Evidence (verified):** 1 call per PR (up to the 50 per page) after every list load, 4 in parallel. Counts are cached by PR id for the view model's life (`filterNot { it.ref.id in _commentCounts.value }`), so Refresh never updates them, and the count coroutines are launched in `scope` rather than as children of the refresh job, so cancelling a load (filter change) does not cancel them.
- **Fix:** `docs/product-baseline.md` fixes "Comment counts load after the list, 4 requests at a time", so the volume stays. Wrap the launches in `coroutineScope { … }` so a cancelled refresh cancels them (3-line change). Stale counts are a correctness note for the owner. Backlog.

### P5 — Timeline and diff inlays are rebuilt from scratch on every state change (low, rendering)

- **Where:** `timeline/TimelinePanel.kt:66-69,83-109` (`combine(state, threads, drafts)` → `render` recreates every card and `MarkdownPane`), `diff/ReviewDiffExtension.kt:126-144` (all inlays of the file disposed and recreated).
- **Impact:** a draft edit or a reply re-parses every comment's HTML on the EDT; linear in discussion size, no measurement possible without the UI. Backlog: diff the thread list by id and only rebuild changed cards.

### P6 — List loads only the first page of 50 (low, paging)

- **Where:** `review/ReviewModel.kt:48` (`Page(top = 50)`), `toolwindow/PullRequestListViewModel.kt:123` (no `skip`).
- **Impact:** repositories with more than 50 matching pull requests silently show 50. `changeEntries` (`AzureDevOpsProvider.kt:224-237`) pages correctly but would loop forever if the service ever returned a `nextSkip` equal to the current skip. Backlog: "load more" is a feature; add a progress guard to the changes loop.

### P7 — Caches without eviction (low, memory)

- **Where:** `azdo/AzureDevOpsProvider.kt:36-41` (`users`, `teamMembers`, `trackingIds`), `toolwindow/PullRequestListViewModel.kt:62` (`_commentCounts`).
- **Impact:** small objects, bounded by the number of users, projects and PRs opened; `teamMembers` is also never refreshed, so new team members do not appear until the IDE restarts. Backlog.

### P8 — Blocking wait inside completion (low, threading, UNVERIFIED)

- **Where:** `ui/CommentField.kt:163-165` `ProgressIndicatorUtils.awaitWithCheckCanceled(search)`.
- **Evidence:** completion runs on a background thread under the platform's cancellable read action, and the wait polls `checkCanceled`, so it yields to write actions and is cancelled by the next keystroke. Its latency is P2. Nothing to change once P2 is fixed.

### P9 — Diff sides are fetched sequentially under `runBlockingCancellable` (low, latency, UNVERIFIED that the platform never calls `process` on the EDT)

- **Where:** `diff/PullRequestDiff.kt:92-107`.
- **Evidence:** `CacheDiffRequestChainProcessor` loads producers under a background progress, and `runBlockingCancellable` requires a progress context, which is consistent with the manual UI checks recorded in `docs/engineering-notes.md`. The left and right contents are two sequential requests. Backlog: fetch both with `async`, halving the open latency of modified files.

### P10 — Voting costs five requests (low, API volume)

- **Where:** `azdo/AzureDevOpsProvider.kt:139-145` (`currentUser()` + PR GET before the PUT), then `PullRequestDetailsViewModel.vote` reloads details and threads.
- **Fix:** the view model already knows `me`; the provider could cache `currentUser`. Backlog.

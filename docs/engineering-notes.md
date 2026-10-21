# Engineering notes

Facts verified while building 0.1.0 (2026-10-20), against Rider 2026.2.2 (`RD-262.10315.191`) and the sandbox organization. Anything not verified is marked **UNVERIFIED**.

## Build and checks

- Build against the installed Rider: `./gradlew test buildPlugin verifyPlugin -PplatformLocalPath=/Applications/Rider.app`. Without the property, Gradle downloads Rider 2026.2.2.
- Toolchain:
  - JDK 25 (platform class files are version 69);
  - Kotlin 2.4.21 with `apiVersion`/`languageVersion` 2.4, because Rider bundles stdlib 2.4.0;
  - `jvmDefault = NO_COMPATIBILITY`, so implementing platform interfaces doesn't generate bridges that the verifier reports as overrides of deprecated or experimental members.
- The plugin bundles nothing: the IDE provides the Kotlin stdlib and kotlinx.serialization. The zip holds only the plugin jar and its searchable-options jar.
- `verifyPlugin` must stay **Compatible**, with no deprecated, experimental, scheduled-for-removal or internal API usage. Read `build/reports/pluginVerifier/…/verification-verdict.txt`.
- `gradle.properties` raises the Gradle (2 GB) and Kotlin daemon (3 GB) heaps. Non-incremental builds against the full platform run out of memory otherwise.
- After changing a model class's constructor, old test classes can fail with `NoSuchMethodError`. Delete `build/classes/kotlin/test` and `build/kotlin/compileTestKotlin`, then rerun.
- Tests: 68 (5 live tests skipped unless `AZDO_LIVE_TEST=1` and `AZDO_PAT` are set). Unit tests replay recorded fixtures through `FixtureServer` and make no network calls.

## Sandbox IDE (`runIde`)

- `./gradlew runIde -PplatformLocalPath=/Applications/Rider.app` starts a sandbox Rider under `.intellijPlatform/sandbox/`. Its log is `log_runIde/idea.log`.
- Plugin hot reload does not work for this plugin ("expected not to be loaded but was included in the module graph"). Quit and relaunch the sandbox instead.
- `./gradlew --stop` also kills a running sandbox.
- Don't switch git branches while `runIde` or a build runs. Use `git worktree` for other branches.

## IntelliJ Platform pitfalls

- **JTree subclasses:** `cellRenderer = …` and `showsRootHandles = …` assign JTree's *protected fields* in Kotlin, not the setters, so the UI never updates and rows render `toString()`. Call `setCellRenderer(…)` and `setShowsRootHandles(…)`.
- **Kotlin names:**
  - inside a property getter, `field` is the backing field, not a member named `field`;
  - a member `location()` hides `Component.location`;
  - a helper named `run` loses to the stdlib `run {}`.
- **Components embedded in an editor** (diff inlays): `InplaceButton` doesn't react to clicks there. Use a `JBLabel` with a hand cursor and a `MouseListener`. Links (`ActionLink`) work.
- **Popups anchored at the bottom of a panel:** use `popup.show(PopupShowOptions.aboveComponent(anchor))`, otherwise they run off the window.
- **`TextFieldWithCompletion`** has no height until its editor exists (after `addNotify`). Don't freeze a wrapper's preferred size at construction; override `getPreferredSize` instead.
- **Completion lookup** opens semi-focused, so Enter would insert a newline. Map Enter to `IdeActions.ACTION_CHOOSE_LOOKUP_ITEM_REPLACE` while a lookup is active. The default CamelHump matcher matches a later word only by one letter, so mentions use a custom `PrefixMatcher`.
- **`JEditorPane`** for Markdown: set the caret to `DefaultCaret.NEVER_UPDATE`, or the scroll pane jumps to it.
- **Diff tab reuse:** subclass `ChainDiffVirtualFile` and keep the `CacheDiffRequestChainProcessor` it creates; switch files with `setCurrentRequest(index)`. Open it with `DiffEditorTabFilesManager.showDiffFile(file, focus)`.
- **Diff toolbar:** put actions on each request with `DiffUserDataKeys.CONTEXT_ACTIONS`. They come after the platform's own actions and move into the › overflow when the editor is narrow.
- **Initial scroll in a diff:** wait for `DiffViewerListener.onAfterRediff`, then `invokeLater`.
- **collaboration-tools:** `ReviewListComponentFactory` and `ReviewListSearchPanelFactory` are `@ApiStatus.Internal`, and `CommentInputActionsComponentFactory` is scheduled for removal. The plugin builds its own list, chips and comment components.
- **Public API used:** `PopupShowOptions.aboveComponent` and `TextFieldWithCompletion` carry no API-status annotation in 262.
- **Unified diff viewer** (`com.intellij.diff.tools.fragmented.UnifiedDiffViewer`, in `lib/intellij.platform.diff.impl.jar`), checked with `javap` on 262.10315.191:
  - the class, its package and the methods used carry no `ApiStatus` annotation (only `myModel`, `createUi` and `getDifferenceIterable` are `@Internal`);
  - one editor (`getEditor()`) shows both sides; `getDocument(Side)` is each side's own document;
  - `transferLineToOnesideStrict(side, line)` maps a 0-based side line to the unified line (`LineNumberConvertor.convertInv`), and `transferLineFromOnesideStrict(side, line)` the reverse (`convert`). Both return -1 when the line isn't on that side or before the first diff is computed (no convertor). The non-strict variants approximate instead;
  - every rediff replaces the whole unified document (`Document.setText` in `apply`), then `onAfterRediff` and the listeners' `onAfterRediff` run. Inlays and highlighters placed before must be laid out again after each rediff;
  - switching viewers (`DiffRequestProcessor.switchToDiffTool` → `updateRequest(true)`) creates a new viewer, and the processor's `createState` calls `DiffExtension.onViewerCreated` for each viewer it creates; the old viewer is disposed, taking everything registered with it;
  - `DiffDataKeys.DIFF_VIEWER` is public (its neighbours `NAVIGATION_CALLBACK`, `PREV_NEXT_DIFFERENCE_ITERABLE`, `CROSS_FILE_PREV_NEXT_DIFFERENCE_ITERABLE` and `PREV_NEXT_FILE_ITERABLE` are `@Internal`); `DiffRequestProcessor$DefaultState` provides it. That it reaches the diff toolbar's context actions is **UNVERIFIED** at runtime.
  - Whether a composer inlay opened before a unified rediff (e.g. after changing the whitespace setting) keeps its line is **UNVERIFIED**.

## Azure DevOps REST (api-version 7.1), verified in the sandbox

- **Votes:**
  - `PUT …/pullRequests/{id}/reviewers/{me}` with `{"vote": n, "isRequired": b}`. Leaving out `isRequired` turns a required reviewer into an optional one, so read the PR first and send the current value.
  - `GET …/reviewers/{me}` returns 400 when the user isn't a reviewer.
  - Draft pull requests reject votes: "You cannot vote on draft pull requests."
  - Vote values: 10 approved, 5 approved with suggestions, 0 none, −5 waiting for author, −10 rejected.
- **Add reviewer:** `PUT …/reviewers/{id}` with `{"vote":0,"isRequired":false}`.
- **Complete:** `PATCH …/pullRequests/{id}` with `{"status":"completed","lastMergeSourceCommit":{"commitId":…}}`, using the PR's current `lastMergeSourceCommit`. **Abandon:** `{"status":"abandoned"}`. Neither was run against the sandbox; only the request bodies are tested.
- **PR fields:**
  - `labels` is `null` when there are none, so filter on `active`;
  - `mergeStatus` is e.g. `succeeded` or `conflicts`;
  - `isDraft`.
- **Comments:**
  - `lastContentUpdatedDate` equals `publishedDate` until the comment is edited;
  - edit with `PATCH …/threads/{t}/comments/{c}` and `{"content": …}`.
- **Threads:**
  - system threads (pushes, votes, reviewer changes) have no `status`, and their first comment has `commentType: system`;
  - status numbers 1–6 are active, fixed, wontFix, closed, byDesign, pending.
- **Mentions:**
  - stored as `@<identity id>`;
  - Graph `subjectquery` matches only display-name prefixes ("Volo" matches "Volokhovych Ihor"; "Ihor" doesn't), and returns `{"count":0,"value":null}` when nothing matches;
  - project team members come from `GET {org}/_apis/projects/{project}/teams` then `…/teams/{id}/members` (`identity.{id,displayName,uniqueName}`).
- **Avatars:** `GET {org}/_api/_common/identityImage?id=<id>` returns `image/png` with PAT basic auth. Sandbox users without a photo get a generated initials image.
- **Viewed marks:** no public API for Azure DevOps' own reviewed marks was found (**UNVERIFIED** that none exists), so they are stored locally (`ViewedFilesStore`, key `<pr web url>|<path>`, value = file version).
- **Token failures (checked 2026-10-21 with curl and `LiveSandboxTest`, read-only):**
  - a wrong PAT, and no `Authorization` header at all, on `GET {org}/_apis/connectionData` get **302** (`text/html`) with `Location: https://…vssps.visualstudio.com/_signin?realm=dev.azure.com&reply_to=…`, not 401. The JDK `HttpClient` doesn't follow redirects by default, so the client maps a 3xx whose `Location` contains `_signin` to "rejected";
  - the 401 and 203-with-HTML forms were not observed (**UNVERIFIED**; they stay mapped to "rejected" as in the spec);
  - an unknown organization gives 404 with an HTML page (no JSON `message`); an unknown repository gives 404 with `TF401019: The Git repository with name or identifier … does not exist or you do not have permissions …`;
  - `GET {org}/{project}/_apis/git/repositories/{repo}` returns 200 with the repository (`id`, `name`, `project`, `remoteUrl`, …) for a valid PAT; the sign-in check uses it;
  - whether a PAT missing a scope gets 401 or 403 is **UNVERIFIED** (needs a scope-limited test PAT from the owner, sandbox-plan S1 step 5). Because a 401 could mean a missing scope, `AzureDevOpsConnection` treats a rejection as real only when `connectionData` with the same token is rejected too.

## AI review: Claude Code CLI (checked 2026-10-21)

Only `claude --help`, `claude --version` and `claude auth --help` were run; no prompt was ever sent.

- **Version:** `~/.local/bin/claude` is a symlink to `~/.local/share/claude/versions/2.1.306`; `--version` prints `2.1.306 (Claude Code)`.
- **Flags used, as `--help` describes them:**
  - `-p, --print`: print the response and exit; skips the workspace trust dialog, so run it only in a directory you trust (we use an empty temp directory);
  - `--tools <tools...>`: `""` disables all built-in tools. It is variadic, so a positional prompt after it would be read as a tool name; the prompt goes on stdin instead;
  - `--strict-mcp-config`: only MCP servers from `--mcp-config`; we pass none, so none load;
  - `--no-session-persistence`: sessions aren't saved to disk (only with `--print`);
  - `--output-format json`: "single result" (only with `--print`);
  - `--json-schema <schema>`: JSON Schema for structured output validation, passed inline (only with `--print`).
- **Flags not used:**
  - `--bare`: Anthropic auth becomes strictly `ANTHROPIC_API_KEY` or an `apiKeyHelper`, "OAuth and keychain are never read", which breaks a normal subscription sign-in;
  - `--restricted`: would also ignore the user's settings files; its effect on sign-in is untested;
  - `--allowedTools` and `--permission-mode`: not needed once `--tools ""` removes the tools.
- **UNVERIFIED** (needs a real run):
  - the JSON envelope: an object with `type: "result"`, `is_error`, `result` and, with `--json-schema`, `structured_output`. The parser also accepts the answer as JSON in `result` (bare, fenced or with text around it), an array of messages, and an envelope that is itself the answer;
  - that `-p` reads the prompt from stdin when no prompt argument is given (the help says "useful for pipes");
  - that structured output still works with `--tools ""`. The prompt repeats the schema, so a plain JSON answer in `result` also works;
  - the wording of the not-signed-in error (the parser looks for "log in", "sign in", "API key", "authenticat" or "OAuth" to add a sign-in hint);
  - which environment variables sign-in needs besides HOME and USER. On macOS the subscription login is in the keychain. Passed through: PATH (plus `~/.local/bin`), HOME, USER, LOGNAME, LANG, LC_ALL, LC_CTYPE, TMPDIR/TMP/TEMP, the Windows profile variables, proxies, `NODE_EXTRA_CA_CERTS`, and Claude's own `ANTHROPIC_API_KEY`, `ANTHROPIC_AUTH_TOKEN`, `ANTHROPIC_BASE_URL`, `CLAUDE_CODE_OAUTH_TOKEN`, `CLAUDE_CONFIG_DIR`. Everything else is dropped, including `AZDO_PAT` and any other `*_TOKEN`/`*SECRET*`. Bedrock and Vertex credentials aren't passed, so those setups won't work.
- **JDK processes (verified by `ProcessRunnerTest`, macOS):**
  - once a child exits, the JDK drains and closes its stdout and stderr pipes, so readers don't hang on grandchildren that still hold them;
  - the grandchildren keep running, though. Kill `process.descendants()` before the process itself, because orphans get a new parent and drop out of the list. The tree-kill tests fail without this.
- **Platform:**
  - `com.intellij.util.diff.Diff.buildChanges(T[], T[])` (`intellij.platform.util.diff.jar`) has no API-status annotation and runs in plain unit tests without an application;
  - `StringUtil.formatFileSize` is decimal: 1000 → "1 kB", 1024 → "1.02 kB". The size cap is 200 000 bytes;
  - `withBackgroundProgress(project, title, cancellable = true)` and `DialogWrapper` with a custom `DialogWrapperAction` pass the verifier (Compatible, no internal or deprecated use).

## Release

- Version: `pluginVersion` in `gradle.properties`. Compatibility: `pluginSinceBuild=262`, `pluginUntilBuild=262.*`.
- `buildPlugin` writes `build/distributions/rider-azdo-pr-review-<version>.zip`.
- Distribution is a **private GitHub release** with the zip attached (owner's choice, 2026-10-20): no Marketplace listing, no public plugin repository.
- `scripts/install-plugin.sh` installs the latest release (or a local or freshly built zip) into Rider's plugins directory.

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

## Release

- Version: `pluginVersion` in `gradle.properties`. Compatibility: `pluginSinceBuild=262`, `pluginUntilBuild=262.*`.
- `buildPlugin` writes `build/distributions/rider-azdo-pr-review-<version>.zip`.
- Distribution is a **private GitHub release** with the zip attached (owner's choice, 2026-10-20): no Marketplace listing, no public plugin repository.
- `scripts/install-plugin.sh` installs the latest release (or a local or freshly built zip) into Rider's plugins directory.

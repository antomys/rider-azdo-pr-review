# Product baseline (0.2.0)

What the plugin does today. The 0.1.0 behavior was approved by the owner on 2026-10-20 after reviewing it in a sandbox Rider (release `v0.1.0`, commit `8fd864e`). The owner asked for the 0.2.0 features on 2026-10-21: the unified diff view, sign-in, "Assigned to me", reviewed marks with update detection and the update view, and the opt-in AI review.

**Rules for changes**
- Everything below is the intended behavior. Don't change, remove or restyle any of it unless the owner asks for that specific change.
- Bug fixes that make the plugin behave as described here are fine. New features must not alter what's described here; if one would, ask first.
- When the owner approves a change to this behavior, update this file in the same pull request.

The layout and interaction model follow JetBrains' bundled GitLab merge-request plugin.

## Settings

**Settings | Tools | Azure DevOps PR Review**
- Organization: the name in `https://dev.azure.com/<organization>`, stored for the whole IDE.
- Personal access token: stored in the IDE password storage, never shown. Leaving the field empty keeps the saved token. Required scopes: Code (Read & write), Identity (Read), Graph (Read).
- Project and repository: stored per IDE project.

## Tool window "Azure DevOps PR Review"

Docked on the left, with a merge icon. The first tab, "Pull Requests", is the list and can't be closed. Each opened pull request adds a closeable tab named `#<id>`.

### Pull Requests tab

- **Search field** (with history): filters the loaded list by title, `#id` or number, author name, or source branch. Typing filters immediately; it doesn't reload.
- **Filter row:**
  - Funnel icon, with a green dot when any filter or search differs from the default. Clicking it offers presets: Open, Created by me, Waiting for my review, All states.
  - Chips: **State** (Open, Completed, Abandoned), **Author**, **Reviewer**, **Label**.
    - An unset chip reads "Author ▾"; a set chip shows its value with × to clear it, e.g. "Me (Volokhovych Ihor) ×".
    - The default is State = Open.
    - Author and Reviewer offer the current user, the loaded pull requests' authors and reviewers, then directory search results.
    - Label offers the labels of the loaded pull requests.
  - State, Author and Reviewer reload the list from the service. Label and search filter the loaded list only.
- **Rows**, two lines:
  - Line 1: the title (grey for drafts), then on the right a grey **DRAFT** badge, a tag icon if the PR has labels (labels in the tooltip), up to 3 reviewer avatars each preceded by its vote icon, and a comment icon with the count of discussion threads.
  - Line 2 (grey): `#<id> · created <date>, by <author>`, plus the state when it isn't Open.
- Comment counts load after the list, 4 requests at a time.
- Double-click or Enter opens the pull request.
- Empty states: "Loading pull requests…"; the error text with "Retry"; "No pull requests" or "No pull requests match the search", with "Reset filters" when filters aren't the default. Missing settings show the sign-in panel instead (see below).
- **Review state (0.2.0):** reviewed marks are local to the IDE and never sent to Azure DevOps (see "Reviewed marks").
  - The funnel's popup also offers the preset **Assigned to me** (after Waiting for my review) and, below a separator, the checkable toggles **Show reviewed** and, while Assigned to me is on, **Show declined**. A toggle that is on also lights the green dot. Choosing a preset keeps Show reviewed.
  - **Assigned to me** lists the pull requests in the configured repository where the user is a reviewer directly or through one of their project teams (state chip as usual, Open by default). While it's on, the Reviewer chip reads "Me and my teams ×"; clearing it, or choosing a reviewer, returns to the normal Reviewer filter. If a team's pull requests can't be loaded, the list still shows the rest with a grey warning line "Some team assignments couldn't be loaded" under the filters.
  - In Assigned to me, line 2 also shows the user's role (Required, Optional, or "via team <name>"), their vote name (the team's vote for a team-only assignment), "Declined" when they declined, and "Needs review" for pull requests not marked reviewed. Rows are sorted: needs review, then updated since review, then reviewed; newest first within each. Pull requests the user declined are hidden unless Show declined is on.
  - Pull requests marked reviewed are hidden from every list view unless Show reviewed is on; then they show a grey **Reviewed** badge after the DRAFT badge. A pull request whose reviewed mark was cleared by a new iteration shows a grey **Updated: iteration N → M** badge instead, with the reasons in brackets when known (push, force-push, rebase, retarget), and is listed again.
  - When every loaded pull request that matches is hidden because it's marked reviewed, the empty state reads "Pull requests marked reviewed are hidden" with a "Show reviewed" link.
  - Right-clicking a row selects it and opens a context menu with **Mark as Reviewed**, or **Mark as Not Reviewed** for a reviewed pull request.

### Sign-in panel

Replaces the Pull Requests tab's content while the organization, token, project or repository isn't set, or after Azure DevOps rejected the saved token. There's no prompt at IDE startup.
- Title "Sign in to Azure DevOps", then the fields **Organization**, **Personal access token** (masked), **Project** and **Repository**.
  - For a project not set up yet, organization, project and repository are prefilled from the project's Git remote (`origin` first) when it is an Azure DevOps URL: `https://dev.azure.com/{org}/{project}/_git/{repo}`, the same with `{org}@`, `https://{org}.visualstudio.com/{project}/_git/{repo}`, or `git@ssh.dev.azure.com:v3/{org}/{project}/{repo}`. Otherwise they show the saved settings.
  - When a token is saved, the token field can be left empty to use it ("Leave empty to use the saved token.").
- The required scopes, and that tokens for all organizations stop working on 1 December 2026, so the token should be for this organization only.
- **Create token** opens `https://dev.azure.com/<organization>/_usersSettings/tokens` for the organization in the field.
- **Sign In** (or Enter in a field) first checks the token: whose it is, then access to the repository, with "Checking the token…".
  - Success: saves the organization (IDE-wide), the token (password storage) and the project and repository (per project), then loads the list without a restart.
  - Failure: the error appears under the button with **Retry**; nothing is saved.
- **Rejected token:** when any call (list, pull request tab, diff, timeline) is rejected (HTTP 401, a 203 sign-in page, or a redirect to sign-in) and the same token can't read its own user either, the panel shows "Azure DevOps rejected the token. It may have expired or been revoked." at the top, and the Pull Requests tab is selected.
  - The saved token is kept until a new one validates.
  - After signing in with a new token, the `#<id>` tabs opened with the old one close, with their unsent drafts. Signing in again with the saved token keeps them.
- Errors elsewhere use the same wording: "The token can't do this. It needs: Code (Read & write), Identity (Read), Graph (Read)." (403); "Project, repository or pull request not found, or the token has no access to it." (404); "Azure DevOps is limiting requests. Try again in a minute." (429 after the retries); "Azure DevOps is unavailable (HTTP 5xx)."; "Can't reach <host>." (network). The others add the HTTP status and the service's message, never the token or response bodies.

### Opening a pull request

Opens (or re-focuses) both:
1. the **`#<id>` details tab** in the tool window, and
2. the **"Timeline for #<id>"** editor tab.

Closing the details tab closes its timeline tab.

### Details tab

- Toolbar: Back (to the list), Refresh, Open in Browser.
- Header:
  - bold title followed by a `#<id>` link to the pull request in the browser;
  - a **View Timeline** link;
  - **Changes**, with `source → target` branches and a branch icon on the right;
  - a **Show details / Hide details** link. Details are: the state badge (Active, Draft, Completed, Abandoned), "<author> opened <date>" with the author's avatar, the description as Markdown, and the reviewers with avatar, vote icon, vote name and "required".
- **Files tree:**
  - folders and files, single-child folders merged (`src/Orders`);
  - each folder shows a grey "N files" count;
  - files show the file-type icon, the name colored by change kind (added, deleted, modified), "← old name" for renames, and a grey comment count;
  - a **blue dot** at the right edge marks a file not viewed yet. Clicking the dot toggles the viewed mark.
  - Double-click or Enter opens the file in the diff, focused. A single click switches the diff to that file only when the diff is already open.
  - The selection follows the file shown in the diff.
- **Review status** at the bottom, one line each, in this order:
  - "Merge conflicts: resolve them before merging"
  - "Draft: votes are possible once it's published"
  - "Missing reviewer" (no reviewers)
  - "Rejected by …", "Changes requested by …", "Approved by …" (approved and approved with suggestions)
  - "⚠ [avatars] Waiting for review from …" (reviewers without a vote)
  - Completed or Abandoned pull requests show only that state, with no buttons.
- **Buttons** (open pull requests):
  - Primary: **Set Myself as Reviewer** when the user isn't a reviewer and has no drafts; otherwise **Submit Review…**, or **Submit Review (N)…** with N pending drafts.
  - **⋮** menu, opening upward: Request Review… (pick a person), Merge… (confirmation, then complete; disabled for drafts and conflicts), Rebase (always disabled), Close Pull Request (confirmation, then abandon).
  - Then, after a separator (0.2.0): **Mark as Reviewed**, or **Mark as Not Reviewed** when it's marked reviewed (see "Reviewed marks").
- **Update banner (0.2.0)** at the top of the tab, above the title, when the pull request has iterations newer than the one the user saw when they last opened it:
  - bold "Updated: iteration N → M" and a × that dismisses it for this tab;
  - one grey line per newer iteration: "Iteration M · <date> · <reasons>";
  - "K new commit(s)" with up to 10 commits (short id and first line), and "K file(s) changed" with up to 10 paths, then "and N more"; "Loading changes…" until they load;
  - links **Show changes since last view** (disabled when no files changed) and **Show all changes** (the normal diff, at the first file).
  - It stays while the tab is refreshed, until dismissed. Opening the tab records the latest iteration as viewed, so the next opening shows no banner unless another iteration arrives.
  - Popups anchored at the bottom open above their button.

### Timeline editor tab

- The title in bold with a grey `#<id>`.
- The author's avatar, "<author> opened the pull request · <date>", and the description as Markdown ("No description" when empty).
- Then everything in creation order:
  - **discussion threads** as cards, with a `File.cs:<line>` link for line threads that opens the diff at that line;
  - **system events** (pushes, votes, reviewer changes) as one grey line each, with a blue bar on the left;
  - pending pull-request-level drafts at the end.
- A comment box at the bottom: **Comment** posts immediately; its drop-down has **Save as Draft**.

### Diff (review)

- One editor tab per pull request, named `PR #<id>: <title>`, reused when switching files. Showing a file marks it viewed.
- Side-by-side diff of base vs. the latest iteration. Added and deleted files show one side.
- **Threads** appear inline under their line (file-level threads above the first line). Resolved threads are collapsed to one struck-through grey line; clicking expands them.
- **Gutter:**
  - hovering a line shows a **+** to start a comment on that line, or on the selected lines when the selection includes it;
  - lines with threads or drafts show a **speech-bubble** marker with the comment count in its tooltip.
- **Toolbar**, after the platform's own actions:
  - **← N/M files →**. Clicking "N/M files" opens the files tree as a popup, with Expand All / Collapse All and the current file selected. Double-clicking a file there opens it and closes the popup; the blue dot toggles viewed.
  - **Submit Review…**, or **Submit Review (N)…** with pending drafts.
  - **↑ / ↓**: previous or next comment, in file order then line order. It moves across files and puts the caret on the thread's line.
  - When the editor is narrow, the platform moves the last actions into the toolbar's › overflow.
- **Unified view** (the platform's viewer switcher in the diff toolbar) shows the same threads, drafts, markers and gutter **+**:
  - a base-side (left) thread sits under its deleted or unchanged line, an iteration-side (right) thread under its inserted or unchanged line; file-level threads above the first line;
  - the **+** on a deleted line comments on the base side, on an inserted or unchanged line on the iteration side, as in Azure DevOps. With a selection, the comment takes the clicked line's side and covers the selected lines that side has;
  - **↑ / ↓** order the shown file's threads by where they appear in the unified editor, then move across files as in side-by-side;
  - switching between side-by-side and unified while the diff is open shows the review UI in the new view.

- **Changes since last view (0.2.0):** a separate editor tab, `PR #<id>: changes since iteration N`, opened from the update banner. It lists only the files changed between iteration N and the latest one (the platform's own file navigation moves between them); left is the file at iteration N's source commit, right at the latest iteration's. It has no inline threads, comment gutter or review toolbar, and showing a file there doesn't mark it viewed, because threads are positioned against the merge-base diff. Opening it again replaces the previous one.

## Comments

- **Comment box** (new thread, reply, edit, timeline):
  - the user's avatar on the left;
  - a multi-line field: Enter adds a line, ⌘⏎ / Ctrl+Enter runs the main action, Escape cancels;
  - hint text "⌘⏎ to <action>  ⏎ to add new line", which shortens before the buttons get cut off;
  - Cancel where applicable, and the action button. Errors appear on their own line and the text is kept.
- **Drafts are the default in the diff:**
  - a new thread's split button is **Save as Draft ▾**, with **Post Now** in the drop-down;
  - replies in the diff work the same way;
  - in the timeline, Comment posts immediately and Save as Draft is the alternative.
- **Pending draft:**
  - avatar, name, a **Post now** link, a grey **PENDING** badge, and **edit** (pencil) and **delete** (trash) icons on the right, then the text;
  - a reply draft is indented inside its thread, and a resolved thread with a pending reply stays expanded.
- Drafts are held in memory for the opened pull request. They are lost when its tab closes or the IDE restarts.
- **Threads:**
  - header: avatar, author, "<time ago>" plus " · updated <time ago>" if edited (absolute time in the tooltip), and a status badge;
  - replies are indented with a line on their left;
  - footer: **Reply** and **Resolve** or **Reopen**;
  - resolved threads start collapsed and have a **Collapse** link when expanded.
- **@mentions:**
  - typing `@` opens suggestions: thread participants first, then project team members and directory search;
  - Enter or Tab inserts `@Name`, which is posted as Azure DevOps' `@<id>` mention and shown as `@Name` when read back.
- Comments and descriptions render Markdown only; raw HTML shows as text and images aren't loaded.

## Submit Review popup

- "Submit Review", then "No pending comments" or "N pending comment(s) will be posted".
- An optional summary comment field, with mentions.
- Buttons:
  - **Approve** or **Approve with Suggestions** as the main button. It's "Approve with Suggestions" while the user has drafts or open threads they started. The drop-down holds the other approve option and **Reject**.
  - **Request Changes**: votes "Wait for author".
  - **Comment**: no vote.
  - ⌘⏎ runs the main button.
- Draft pull requests: vote buttons disabled, with "Draft pull requests can't be voted on".
- Shows "Your current vote: …" when the user has voted.
- Sending order: every draft in the order it was written, then the summary as a pull request comment, then the vote. A draft is removed once it's sent, so after a failure only the unsent ones remain.
- Voting keeps the user's required or optional reviewer status.

## Viewed marks

- Stored locally per pull request and file. A mark counts only while the file's version (blob) is unchanged, so a new push to that file clears it.
- Not synced to Azure DevOps or across machines.

## AI review (opt-in)

Off by default. Nothing is sent anywhere until the user enables it and confirms a run.

- **Settings**, a new "AI Review" group at the end of **Settings | Tools | Azure DevOps PR Review**, stored for the whole IDE (nothing secret):
  - **Enable Review with AI** (unchecked by default); the other fields are disabled until it's checked;
  - **Claude Code CLI**: the path to `claude`; empty means `claude` on PATH, then `~/.local/bin`. **Detect** shows "Found: <version> at <path>" or "Not found: <reason>";
  - **Exclude files**: globs separated by commas or new lines, never sent. A glob without `/` matches the file name, one with `/` the path; case is ignored. Defaults: `.env`, `.env.*`, `*.pem`, `*.key`, `*.pfx`, `*.p12`, `id_rsa*`, `appsettings.*.json`, `secrets.*`, `*.keystore`;
  - **Extra instructions**: added to the prompt;
  - **Max findings**: default 20 (1–100).
- **"Review with AI…"**: the last item of the details tab's **⋮** menu, after a separator, shown only while the setting is enabled (the ⋮ menu exists only for open pull requests).
- **Preparing** (background task with Cancel): checks that the CLI runs (`--version`), then diffs every changed file of the latest iteration against the merge base (unified diff, 3 lines of context). Files matching an exclude glob aren't downloaded. Binary files, files without text changes (pure renames) and files past the 200 kB total are left out.
- **Consent dialog**, on every run:
  - "Send these diffs to Claude Code?" and that the diffs, title and description go to Anthropic through Claude Code under the user's own Claude account, and nothing is posted until findings are chosen;
  - the tool's version and location, and the command line without the prompt;
  - **Files to send**, each with a checkbox (all ticked) and its diff size, and a running total; unticking leaves a file out;
  - **Not sent**: each left-out file with its reason ("matches .env.*", "binary file", "no text changes", "over the 200 kB limit");
  - **Send** (disabled when no file is ticked) and **Cancel**.
- **Review** (background task with Cancel): Claude Code runs with no tools, no MCP servers and no saved session, in an empty temporary directory that is deleted afterwards, with the prompt on stdin and a 5-minute limit. Cancel or the limit stops the CLI and everything it started. The CLI gets a minimal environment, never the Azure DevOps token.
- **Findings** are checked before they are shown: a finding whose file wasn't sent, or whose lines aren't in the file on that side, is dropped, and so are those past Max findings.
  - No findings: "Claude Code found nothing to report.", plus the dropped count.
- **Preview dialog** "AI Review Findings":
  - "N findings from Claude Code. Info findings start unticked." and, when some were dropped, "Dropped N: … not on a file or line of the diff, … over the limit of 20.";
  - each finding: a checkbox (ticked for errors and warnings, unticked for info), the severity with its icon, `File.cs:12` or `File.cs:12–14` ("(base)" for a deleted line; the full path in the tooltip), the title in an editable field, and the body as Markdown with **Edit** / **Preview** to change it;
  - **Add as Drafts** (default): each ticked finding becomes a pending draft on its file and lines, shown as PENDING in the diff and sent with Submit Review;
  - **Post Now**: each ticked finding is posted at once as an active thread on its file and lines; after a failure, the message says how many were posted;
  - the comment is `**<title>**`, a blank line, then the body; nothing else is added.
- AI text is shown only as sanitised Markdown or plain text, like comments.
- Errors (CLI not found, not signed in, timeout, non-zero exit, unreadable output) show in a "Review with AI" error dialog with the end of the CLI's error output; values of Claude sign-in variables are masked.

## Reviewed marks (0.2.0)

- Stored locally per organization, repository, pull request and user, in an IDE-level file (not in the project, not synced). Nothing is written to Azure DevOps and votes don't change.
- **Mark as Reviewed** reads the pull request's iterations and records the latest one and its source commit. If that iteration is newer than the one the user saw when they last opened the pull request, a dialog says "This PR was updated since you opened it (iteration N → M)." with **Mark Anyway**, **Review Update** (opens the pull request from the list, or reloads the tab so the update banner shows) and Cancel.
- A reviewed mark is cleared when the pull request has a newer iteration or a different source commit (a retarget counts). The list compares each reviewed pull request's last merge source commit with the reviewed one and reads the iterations only when they differ; opening the pull request always checks.
- Records of completed or abandoned pull requests untouched for 90 days are removed when the list loads.

## Not built yet

These are planned or specified in `docs/`, but not built. Building them is a new feature, not a change to the baseline.
- a second AI adapter (Copilot CLI);
- create pull request, checkout, likes, suggestion apply;
- Azure DevOps Server (on-premises) and OAuth sign-in.

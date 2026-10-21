# Product baseline (0.1.0)

What the plugin does today, as approved by the owner on 2026-10-20 after reviewing it in a sandbox Rider (release `v0.1.0`, commit `8fd864e`).

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

## Not in 0.1.0

These are planned or specified in `docs/`, but not built. Building them is a new feature, not a change to the baseline.
- threads in the unified diff view;
- the AI review;
- the F1–F5 features (sign-in prompt, team-aware "Assigned to me", reviewed marking, update detection, update view);
- create pull request, checkout, likes, suggestion apply;
- Azure DevOps Server (on-premises) and OAuth sign-in.

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
- Empty states: "Loading pull requests…"; "Azure DevOps isn't configured" with an "Open settings" link; the error text with "Retry"; "No pull requests" or "No pull requests match the search", with "Reset filters" when filters aren't the default.

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

## Not in 0.1.0

These are planned or specified in `docs/`, but not built. Building them is a new feature, not a change to the baseline.
- threads in the unified diff view;
- the AI review;
- the F1–F5 features (sign-in prompt, team-aware "Assigned to me", reviewed marking, update detection, update view);
- create pull request, checkout, likes, suggestion apply;
- Azure DevOps Server (on-premises) and OAuth sign-in.

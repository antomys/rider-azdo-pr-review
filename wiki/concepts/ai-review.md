---
type: concept
title: "AI review (opt-in)"
tags: [product, architecture, open-question]
sources: [src-product-baseline, src-engineering-notes, src-risks]
created: 2026-10-21
updated: 2026-10-21
---
# AI review (opt-in)

The plugin's only AI path: the user's own Claude Code CLI reviews a pull request's diffs, and the user picks which findings become comments. New in 0.2.0; off by default. Rules for agents are in [[change-rules]].

- **Code** (`ai/`): `AiReviewFlow` (`start`, the consent and preview dialogs), `AiReviewSession` (Swing-free: prepare, review, add drafts or post), `AiReviewRequests` (builds the diffs, exclusions, validates findings; pure), `AiReviewPrompt` (prompt and JSON schema), `AiReviewer` (adapter interface), `ClaudeCodeReviewer` (the CLI adapter and the `ClaudeOutput` parser), `ProcessRunner` (child process with an exact environment). Settings in `settings/AiReviewSettings.kt`, shown as the "AI Review" group in `AzureDevOpsConfigurable.kt`.
- **Opt-in:** **Enable Review with AI** (IDE-wide, nothing secret); CLI path (empty means `claude` on PATH, then `~/.local/bin`) with **Detect**; exclude globs (defaults include `.env*`, keys, `appsettings.*.json`, `secrets.*`); extra instructions; max findings (default 20). "Review with AI…" is the last ⋮ item on open PRs, only while enabled. See [[ui-structure]].
- **Flow:**
  1. Prepare: `claude --version`, then unified diffs (3 lines of context) of each changed file, merge base vs latest iteration. Excluded, binary, rename-only files and anything past 200 kB total are left out.
  2. Consent dialog on every run: what goes to Anthropic under the user's account, tool version and path, the command line without the prompt, each file with a checkbox and its size, the total, and the files not sent with reasons. Nothing is sent until **Send**.
  3. Review: `claude -p --tools "" --strict-mcp-config --no-session-persistence --output-format json --json-schema …`, prompt on stdin, in an empty temp directory that is deleted afterwards, 5-minute limit, process-tree kill (descendants first) on cancel.
  4. Findings are validated: not on a sent file or on lines of that side → dropped; over Max findings → dropped.
  5. Preview "AI Review Findings": tick, edit title and body; **Add as Drafts** (default; see [[draft-review]]) or **Post Now** as active threads. AI text renders only as sanitised Markdown or plain text.
- **Environment scrubbing:** the CLI gets PATH, HOME, user, locale, temp, proxy and CA variables plus Claude's own sign-in variables; everything else is dropped, including `AZDO_PAT` and any `*_TOKEN`/`*SECRET*`. Bedrock and Vertex setups won't work. Values of Claude sign-in variables are masked in error output.
- **Flags not used:** `--bare` (would break subscription sign-in), `--restricted`, `--allowedTools`, `--permission-mode`.
- **Testing:** never run the CLI with a prompt in tests or development; tests use `FakeAiReviewer` and hand-written outputs (`ClaudeOutputTest`, `ProcessRunnerTest`, …). See [[build-and-verify]].
- **Verified:** CLI 2.1.306 `--help`, `--version`, `auth --help` only; JDK process behavior by `ProcessRunnerTest`. In the sandbox IDE on 2026-10-21 for the 0.2.0 baseline: Detect finds the CLI; the consent dialog lists files, sizes and the command, and nothing is sent until Send (Send was not clicked).
- **UNVERIFIED:** a real run and its output envelope (`type: "result"`, `is_error`, `result`, `structured_output`; the parser is tolerant); that `-p` reads stdin without a prompt argument; structured output with `--tools ""`; the not-signed-in error wording; which environment variables sign-in needs beyond HOME and USER.
- **Not built:** a second adapter (Copilot CLI); path include globs (R5).

Sources: [[src-product-baseline]], [[src-engineering-notes]], [[src-risks]]

# Wiki Log

Append-only chronological record of operations on the wiki. Each entry begins with `## [YYYY-MM-DD] <op> | <description>` so it's parseable with `grep "^## \[" log.md | tail -N`.

Operations:
- `ingest` — a source was processed into the wiki.
- `query` — a question was answered against the wiki (typically only logged when the answer was filed back as synthesis).
- `lint` — a health check was run.
- `schema` — the schema was modified.
- `shard` — an index was sharded.

---

## [2026-10-20] ingest | docs/ (plan, risks, open decisions, F1–F5, sandbox plan and inventory) plus the new product baseline and engineering notes; 20 pages created

## [2026-10-20] ingest | docs/review/ (inventory, security and performance findings, plan, final report) and the baseline's Markdown-only rendering line; 1 page created, 3 updated

## [2026-10-21] update | release 0.1.1: curl install one-liner, script downloads releases with curl; release-and-install and index updated

## [2026-10-21] ingest | 0.2.0 features: unified diff view, F1 sign-in and rejected tokens, F2 Assigned to me, F3–F5 reviewed marks, update detection and update view, opt-in AI review (product baseline, engineering notes, risks, CLAUDE.md AI rule); 6 pages created, 16 updated

## [2026-10-21] update | repository from the open project's Git remote: sign-in asks only for organization and token, a repository drop-down without a remote, tokens per organization; sign-in and baseline source pages updated

## [2026-10-21] update | v0.2.0 released from c3b870c; release-and-install page and CLAUDE.md current release updated

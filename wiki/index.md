# Wiki Index

The catalog of all pages in this wiki. Each entry: a wikilink to the page and a one-line summary. The LLM reads this first when answering queries to identify candidate pages.

Keep summaries tight — one line each. The index is engineered to be cheap to read; a fat index defeats its purpose.

When this file exceeds ~300 lines or the wiki passes ~150 pages, shard into `wiki/indexes/<type>.md` and replace this file with a directory of shards. See the `scaling-playbook.md` reference in the `llm-wiki` skill for the migration procedure.

---

**Start here:** [[change-rules]] (what agents may and may not change), then [[ui-structure]] or [[plugin-codebase]].

## Sources

- [[src-product-baseline]] — approved 0.1.0 behavior; outranks every other page
- [[src-engineering-notes]] — verified build, platform, REST and release facts
- [[src-plan]] — original plan and spec (partly outdated)
- [[src-risks]] — R1–R12 risks and mitigations
- [[src-open-decisions]] — D1–D28 decisions with defaults
- [[src-features-f1-f5]] — F1–F5 spec (not built) and error-state mapping
- [[src-sandbox-plan]] — sandbox test PR plan
- [[src-sandbox-inventory]] — every sandbox artifact and write

## Entities

- [[plugin-codebase]] — packages, key types, tests
- [[azure-devops-rest]] — verified REST behavior and pitfalls
- [[intellij-platform]] — Rider 2026.2 platform pitfalls already hit
- [[sandbox]] — test organization, PRs and write rules

## Concepts

- [[change-rules]] — baseline, attribution, git, secrets, sandbox rules
- [[ui-structure]] — each UI surface mapped to its source file
- [[draft-review]] — in-memory drafts and Submit Review ordering
- [[voting]] — votes, suggested vote, reviewer status
- [[viewed-marks]] — local viewed marks keyed by file version
- [[mentions]] — mention search and `@<id>` encoding
- [[build-and-verify]] — build, tests, verifier, sandbox IDE
- [[release-and-install]] — private release and install script

## Synthesis

- [[plan-vs-implementation]] — what the plan said vs what 0.1.0 has

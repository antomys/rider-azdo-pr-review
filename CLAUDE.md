# Azure DevOps PR Review: Rider plugin

A standalone JetBrains Rider plugin (`io.github.antomys.azdoprreview`) for reviewing Azure DevOps pull requests in the IDE. Its UX follows JetBrains' bundled GitLab merge-request plugin. Kotlin, IntelliJ Platform, targets Rider 2026.2 (build 262) only. Current release: 0.1.1.

## The product baseline is fixed

`docs/product-baseline.md` describes what the plugin does today, as approved by the owner.
- **Don't change, remove or restyle any behavior it describes unless the owner asks for that specific change.**
- Fixing a bug so the plugin matches the baseline is fine.
- New features must leave the baseline intact. If a request would change it, say so and ask first.
- When the owner approves a change, update `docs/product-baseline.md` in the same pull request.

## Rules

- **Git:** never push to `master`. Work on a branch and open a pull request; the owner merges.
- **Attribution:** no AI or tool mentions and no `Co-Authored-By` trailers in commits, branch names or pull request text.
- **Secrets:** the plugin keeps the token in the IDE password storage. Scripts and live tests read it from `AZDO_PAT`. Never print, log or commit a token, and never put one in settings XML, fixtures or error messages.
- **Azure DevOps writes:**
  - only to the sandbox: organization `igorvolokhovych`, project and repository `test-pr-reviewer`;
  - record every write in `docs/sandbox-inventory.md`;
  - restore votes afterwards, keeping `isRequired`;
  - don't merge or abandon sandbox PRs.
- **No AI service calls** from the plugin; don't send code to external services.
- **Report only what you verified.** Mark the rest **UNVERIFIED**.
- **Code style:** match the surrounding code (KDoc on types and public functions, short comments that explain why, Swing-free view models). Keep changes surgical.

## Commands

```sh
./gradlew test buildPlugin verifyPlugin -PplatformLocalPath=/Applications/Rider.app   # must pass; verifier must say Compatible
./gradlew runIde -PplatformLocalPath=/Applications/Rider.app                          # sandbox Rider for manual checks
scripts/install-plugin.sh [--version vX.Y.Z | --zip FILE | --build]                    # install into the local Rider 2026.2
```

- No hot reload: quit and restart the sandbox after changes.
- `./gradlew --stop` also kills the sandbox.
- Don't switch branches while `runIde` or a build runs; use `git worktree`.
- `NoSuchMethodError` in tests after a model change means stale classes: delete `build/classes/kotlin/test` and `build/kotlin/compileTestKotlin`.

## Where things are

- Code map: `wiki/entities/plugin-codebase.md`.
- UI surface → file: `wiki/concepts/ui-structure.md`.
- Layers: `azdo/` (REST client and provider) → `review/` (provider-neutral model and `ReviewProvider`) → `toolwindow/`, `timeline/`, `diff/`, `ui/`.
- Verified platform pitfalls and Azure DevOps REST facts: `docs/engineering-notes.md`. Read it before touching Swing trees, diff inlays, popups, completion or votes.
- Plan, risks, decisions and specs: `docs/plan.md` (partly outdated: see `wiki/synthesis/plan-vs-implementation.md`), `docs/risks.md`, `docs/open-decisions.md`, `docs/features/F1-F5.md`.

## LLM Wiki

This project maintains an LLM-curated wiki at `wiki/`, following Andrej Karpathy's "LLM Wiki" pattern (https://gist.github.com/karpathy/442a6bf555914893e9891c11519de94f). Its sources are the files in `docs/`.

- **Reading:** before answering questions that rely on knowledge accumulated in this project, read `wiki/index.md` and use its one-line summaries to find the pages you need. Cite with `[[wikilinks]]`. If the index doesn't surface good candidates, fall back to `wiki_search.py` from the `llm-wiki` skill for local hybrid retrieval; add `--no-embed` for dependency-free BM25.
- **Adding a source:** follow the `llm-wiki` skill's ingest workflow:
  - place pages under `wiki/sources/`, `wiki/entities/`, `wiki/concepts/` or `wiki/synthesis/`;
  - make surgical updates to the pages a source touches, rather than rewrites;
  - update the index and append a one-line entry to `wiki/log.md`.
- **When a doc in `docs/` changes:** re-ingest it.
- **Scaling discipline:** atomic pages (400-line soft cap, 800-line hard cap), sharded indexes past ~150 pages or 300 index lines, required YAML frontmatter on every page, `[[wikilinks]]` for every cross-reference.
- Full conventions live in `wiki/SCHEMA.md`; treat it as authoritative when it disagrees with this summary. Where the wiki and `docs/product-baseline.md` disagree, the baseline wins.

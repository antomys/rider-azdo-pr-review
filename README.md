# rider-azdo-pr-review

A JetBrains Rider plugin for reviewing Azure DevOps pull requests in the IDE: PR list, side-by-side diff, inline threads, replies, resolve and reopen, @mentions, and an optional AI-assisted review.

Status: milestone 1. The core Azure DevOps client and review provider are done; there is no UI yet.

- [Plan and specification](docs/plan.md)
- [Risks](docs/risks.md)
- [Open decisions](docs/open-decisions.md)

## Build

Requires a JDK 25 toolchain. Gradle provisions one if none is detected.

```
./gradlew test buildPlugin verifyPlugin -PplatformLocalPath=/Applications/Rider.app
```

`platformLocalPath` builds against a local Rider 2026.2.2 installation. Without it, Gradle downloads Rider 2026.2.2.

Tests replay recorded and anonymised Azure DevOps responses from `src/test/resources/fixtures/azdo` and make no network calls.

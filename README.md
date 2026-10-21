# rider-azdo-pr-review

A JetBrains Rider plugin for reviewing Azure DevOps pull requests in the IDE: PR list, side-by-side diff, inline threads, replies, resolve and reopen, @mentions, and an optional AI-assisted review.

Status: 0.1.0, for Rider 2026.2 (build 262).

## Install

With the GitHub CLI signed in to an account that can read this repository:

```
scripts/install-plugin.sh            # latest release; also --version vX.Y.Z, --zip FILE or --build
```

Then restart Rider and continue with step 3. Or install by hand:

1. Download `rider-azdo-pr-review-<version>.zip` from the repository's [Releases](https://github.com/antomys/rider-azdo-pr-review/releases).
2. In Rider: **Settings | Plugins | ⚙ | Install Plugin from Disk…**, choose the zip, and restart Rider.
3. In **Settings | Tools | Azure DevOps PR Review**, enter the organization, a personal access token
   (scopes: Code Read & write, Identity Read, Graph Read), the project and the repository.
4. Open the **Azure DevOps PR Review** tool window.

To update, install the newer zip the same way.

- [Plan and specification](docs/plan.md)
- [Risks](docs/risks.md)
- [Open decisions](docs/open-decisions.md)
- [F1–F5: sign-in, assigned to me, reviewed marking, update detection, update view](docs/features/F1-F5.md)
- [Sandbox plan](docs/sandbox-plan.md)
- [Product baseline](docs/product-baseline.md): what 0.1.0 does; changes need the owner's approval
- [Engineering notes](docs/engineering-notes.md): verified platform and Azure DevOps facts

## Build

Requires a JDK 25 toolchain. Gradle provisions one if none is detected.

```
./gradlew test buildPlugin verifyPlugin -PplatformLocalPath=/Applications/Rider.app
```

`platformLocalPath` builds against a local Rider 2026.2.2 installation. Without it, Gradle downloads Rider 2026.2.2.

Tests replay recorded and anonymised Azure DevOps responses from `src/test/resources/fixtures/azdo` and make no network calls.

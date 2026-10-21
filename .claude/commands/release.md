---
description: Prepare a private release of the plugin
argument-hint: <version, e.g. 0.2.0>
---
Prepare release $ARGUMENTS of the plugin. Ask the owner before any step that pushes, tags or publishes.

1. On a branch from `origin/master`, set `pluginVersion=$ARGUMENTS` in `gradle.properties` and add a `<change-notes>` entry for it in `src/main/resources/META-INF/plugin.xml`.
2. Run /verify. Stop if anything fails.
3. Check the zip in `build/distributions/` holds only the plugin jars, and that no token, organization URL or personal data is in it.
4. After the owner approves: push the branch and open a pull request. Once it's merged, create the release with `gh release create v$ARGUMENTS build/distributions/rider-azdo-pr-review-$ARGUMENTS.zip --target <merge commit>`. Releases are private GitHub releases only: no Marketplace, no public plugin repository.
5. Download the asset back and compare its SHA-256 with the local zip.

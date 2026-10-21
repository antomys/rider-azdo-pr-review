---
description: Run the tests, build the plugin and run the plugin verifier
---
Run `./gradlew test buildPlugin verifyPlugin -PplatformLocalPath=/Applications/Rider.app` from the repository root.

Report:
- the test totals from `build/test-results/test/*.xml` (tests, skipped, failures, errors) and any failing test with its message;
- the verifier verdict from `build/reports/pluginVerifier/*/plugins/*/*/verification-verdict.txt`. Anything other than "Compatible", or any deprecated, experimental, scheduled-for-removal or internal API usage, is a failure;
- compiler warnings (`w:` lines).

If tests fail with `NoSuchMethodError`, delete `build/classes/kotlin/test` and `build/kotlin/compileTestKotlin` and run again before reporting.

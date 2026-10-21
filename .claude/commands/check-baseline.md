---
description: Check the current changes against the product baseline
---
Compare the changes on this branch (`git diff origin/master...HEAD`, plus uncommitted changes) with `docs/product-baseline.md`.

For every user-visible behavior the changes add, remove or alter, say whether the baseline describes it. List anything that changes baseline behavior, with the file and line, as **needs owner approval**, unless the owner asked for exactly that change in this conversation. If the owner approved a change, check that `docs/product-baseline.md` is updated to match.

Don't edit anything; report only.

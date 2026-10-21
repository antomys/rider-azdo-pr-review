---
type: concept
title: "Viewed marks"
tags: [product, architecture]
sources: [src-product-baseline, src-engineering-notes]
created: 2026-10-20
updated: 2026-10-20
---
# Viewed marks

- Local only: `ViewedFilesStore` is an application service, keyed `<PR web url>|<path>`, with the file's version (blob id) as the value.
- A mark counts only while the version matches, so a push that changes the file clears it.
- Set automatically when a file is shown in the diff; toggled with the blue dot in the files tree or the files popup.
- Not synced to Azure DevOps (no API found, **UNVERIFIED**) or across machines.

Sources: [[src-product-baseline]], [[src-engineering-notes]]

---
type: concept
title: "Viewed marks"
tags: [product, architecture]
sources: [src-product-baseline, src-engineering-notes]
created: 2026-10-20
updated: 2026-10-21
---
# Viewed marks

- Local only: `ViewedFilesStore` is an application service, keyed `<PR web url>|<path>`, with the file's version (blob id) as the value.
- A mark counts only while the version matches, so a push that changes the file clears it.
- Set automatically when a file is shown in the diff (not in the changes-since-last-view tab, see [[update-view]]); toggled with the blue dot in the files tree or the files popup.
- Per file. The per-pull-request "reviewed" state is separate: see [[reviewed-marks]].
- Not synced to Azure DevOps (no API found, **UNVERIFIED**) or across machines.

Sources: [[src-product-baseline]], [[src-engineering-notes]]

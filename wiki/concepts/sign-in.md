---
type: concept
title: "Sign-in and rejected tokens (F1)"
tags: [product, azure-devops]
sources: [src-product-baseline, src-engineering-notes, src-risks, src-features-f1-f5]
created: 2026-10-21
updated: 2026-10-21
---
# Sign-in and rejected tokens (F1)

How the plugin gets a working token and what happens when Azure DevOps rejects it. New in 0.2.0; the UI text is in `docs/product-baseline.md` ("Sign-in panel").

- **Which repository:** the open project's Git repository. `settings/ReviewLocation.kt` takes organization, project and repository from its Azure DevOps remote (found from the project folder upwards, `AzureDevOpsRemote.find`); only without one are the saved organization and the per-project Settings fields used. Tokens are stored per organization. The owner asked for this on 2026-10-21: give only the credentials, never type the repository.
- **When it shows:** the sign-in panel replaces the Pull Requests tab's content while there's no token for the organization, no repository is known, or after the saved token was rejected. No prompt at IDE startup.
- **Code:** `toolwindow/SignInPanel.kt` (Swing) and `SignInViewModel.kt` (Swing-free, `SignInFields`, `SignInStore`); token check and repository list in `azdo/AzureDevOpsSignIn.kt`; the Git remote (`origin` first) in `azdo/AzureDevOpsRemote.kt` (`dev.azure.com`, `{org}@dev.azure.com`, `{org}.visualstudio.com` and `ssh.dev.azure.com:v3` URLs); switching panels in `toolwindow/PullRequestToolWindowFactory.kt`.
- **Flow:** the panel asks only for **Organization** and **Personal access token**, and names the repository and where it comes from. **Sign In** checks `connectionData` (whose token it is; works with any valid token whatever its scopes), then `GET …/git/repositories/{repo}` when the repository is known. On success it saves the organization (IDE-wide) and the token (`PasswordSafe`, per organization) and loads the list without a restart. Without a known repository it lists `GET {org}/_apis/git/repositories` (enabled ones, sorted) in a drop-down with **Open**; the choice is saved per IDE project, and nothing is saved before it. On failure the error shows under the button with Retry and nothing is saved. An empty token field reuses the saved token.
- **Rejected token:** a 401, a 203 sign-in page, or a 3xx redirect whose `Location` contains `_signin` counts as rejected (`azdo/AzureDevOpsClient.kt`). `settings/AzureDevOpsConnection.kt` treats it as real only when `connectionData` with the same token is rejected too, because a 401 might mean a missing scope. Then the panel shows "Azure DevOps rejected the token…" and the Pull Requests tab is selected. The saved token is kept until a new one validates; signing in with a new token closes the `#<id>` tabs opened with the old one, with their unsent drafts ([[draft-review]]).
- **Error wording** (403, 404, 429, 5xx, network) is shared with the rest of the plugin; never the token or response bodies. See [[azure-devops-rest]].
- **Verified** in the sandbox IDE on 2026-10-21 for the 0.2.0 baseline: the panel appears with incomplete settings; a wrong repository shows the 404 text inline and saves nothing; the right one loads the list without restart. Later the same day: with no remote and no saved repository, Sign In offered the repository drop-down and Open loaded the list; with an Azure DevOps remote, a wrong repository in Settings was ignored and the remote's list loaded; a remote in an organization without a saved token showed the panel with that organization and "Pull requests of Fiber Project / web-app, from this project's Git remote." With curl and `LiveSandboxTest`: a wrong or missing PAT on `connectionData` gets **302** to `_signin`, not 401.
- **UNVERIFIED:** a token revoked mid-session; the 401 and 203 forms; whether a PAT missing a scope gets 401 or 403. A PAT can't read its own expiry (R12).

Sources: [[src-product-baseline]], [[src-engineering-notes]], [[src-risks]], [[src-features-f1-f5]]

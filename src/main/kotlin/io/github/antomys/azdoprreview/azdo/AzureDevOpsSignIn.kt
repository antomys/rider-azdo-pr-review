package io.github.antomys.azdoprreview.azdo

import io.github.antomys.azdoprreview.azdo.AzureDevOpsClient.Companion.encode
import io.github.antomys.azdoprreview.azdo.AzureDevOpsClient.Companion.url
import io.github.antomys.azdoprreview.review.RepoRef
import io.github.antomys.azdoprreview.review.ReviewUser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** Checks a token before it is saved. */
object AzureDevOpsSignIn {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Returns the token's user from `connectionData`, which works with any valid token whatever its scopes, then, when
     * [repo] is given, checks that the token can read it. Throws [AzureDevOpsException] otherwise.
     */
    suspend fun validate(client: AzureDevOpsClient, repo: RepoRef?): ReviewUser {
        val user = AzureDevOpsProvider(client).currentUser()
        if (repo != null) {
            val path = "/${encode(repo.project)}/_apis/git/repositories/${encode(repo.repository)}"
            withContext(Dispatchers.Default) { client.send("GET", url(client.orgUrl, path, "api-version" to "7.1")) }
        }
        return user
    }

    /** The enabled repositories of every project the token can read, sorted by project then name, to choose from. */
    suspend fun repositories(client: AzureDevOpsClient): List<RepoRef> = withContext(Dispatchers.Default) {
        val body = client.send("GET", url(client.orgUrl, "/_apis/git/repositories", "api-version" to "7.1")).decodeToString()
        json.decodeFromString<ListDto<GitRepositoryDto>>(body).value
            .filter { it.isDisabled != true }
            .map { RepoRef(it.project.name, it.name) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, RepoRef::project).thenBy(String.CASE_INSENSITIVE_ORDER, RepoRef::repository))
    }
}

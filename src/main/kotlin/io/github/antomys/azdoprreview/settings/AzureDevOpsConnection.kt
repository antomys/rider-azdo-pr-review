package io.github.antomys.azdoprreview.settings

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import io.github.antomys.azdoprreview.azdo.AzureDevOpsClient
import io.github.antomys.azdoprreview.azdo.AzureDevOpsProvider
import io.github.antomys.azdoprreview.review.RepoRef
import io.github.antomys.azdoprreview.review.ReviewTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Builds the provider for this project from the saved settings. [scope] lives as long as the project. */
@Service(Service.Level.PROJECT)
class AzureDevOpsConnection(private val project: Project, val scope: CoroutineScope) {
    private class Cached(val organization: String, val pat: String, val provider: AzureDevOpsProvider)

    /** Reused while the account doesn't change, so its user cache survives refreshes. */
    @Volatile
    private var cached: Cached? = null

    /** Null while the settings are incomplete. */
    suspend fun target(): ReviewTarget? = withContext(Dispatchers.IO) {
        val account = AzureDevOpsAccount.getInstance()
        val settings = project.service<AzureDevOpsProjectSettings>()
        val organization = account.organization
        val pat = account.pat()
        if (organization.isEmpty() || pat.isNullOrEmpty() || settings.project.isEmpty() || settings.repository.isEmpty()) {
            return@withContext null
        }
        val provider = cached?.takeIf { it.organization == organization && it.pat == pat }?.provider
            ?: AzureDevOpsProvider(AzureDevOpsClient.forOrganization(organization, pat))
                .also { cached = Cached(organization, pat, it) }
        ReviewTarget(provider, RepoRef(settings.project, settings.repository))
    }
}

package io.github.antomys.azdoprreview.settings

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import io.github.antomys.azdoprreview.azdo.AzureDevOpsClient
import io.github.antomys.azdoprreview.azdo.AzureDevOpsException
import io.github.antomys.azdoprreview.azdo.AzureDevOpsProvider
import io.github.antomys.azdoprreview.azdo.AzureDevOpsSignIn
import io.github.antomys.azdoprreview.review.ReviewTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

/** Builds the provider for this project from the saved settings. [scope] lives as long as the project. */
@Service(Service.Level.PROJECT)
class AzureDevOpsConnection(private val project: Project, val scope: CoroutineScope) {
    private class Cached(val organization: String, val pat: String, val provider: AzureDevOpsProvider)

    /** Reused while the account doesn't change, so its user cache survives refreshes. */
    @Volatile
    private var cached: Cached? = null

    private val _rejected = MutableStateFlow<String?>(null)

    /** Why the saved token stopped working, until the next sign-in; null while it works. */
    val rejected: StateFlow<String?> = _rejected.asStateFlow()

    /** The provider whose rejection is being confirmed, so parallel failures check it once. */
    private val checking = AtomicReference<Cached?>()

    /**
     * The repository of the project's Azure DevOps Git remote, or else the one in the settings, with its organization's
     * token. Null while the organization, its token or the repository is missing.
     */
    suspend fun target(): ReviewTarget? = withContext(Dispatchers.IO) {
        val location = ReviewLocation.of(project)
        val repo = location.repo ?: return@withContext null
        val organization = location.organization.ifEmpty { return@withContext null }
        val pat = AzureDevOpsAccount.getInstance().pat(organization)
        if (pat.isNullOrEmpty()) return@withContext null
        ReviewTarget(provider(organization, pat), repo)
    }

    /** Clears [rejected] after a token validated in the sign-in panel. */
    fun signedIn() {
        _rejected.value = null
    }

    private fun provider(organization: String, pat: String): AzureDevOpsProvider = synchronized(this) {
        cached?.takeIf { it.organization == organization && it.pat == pat }?.provider ?: run {
            var entry: Cached? = null
            val client = AzureDevOpsClient.forOrganization(organization, pat) { entry?.let(::onRejected) }
            Cached(organization, pat, AzureDevOpsProvider(client)).also { entry = it; cached = it }.provider
        }
    }

    /**
     * A call with [entry]'s token was rejected. Some APIs may answer 401 for a missing scope (**UNVERIFIED**), so it
     * counts only when `connectionData`, which any valid token can read, is rejected too. Then the provider is dropped
     * and [rejected] is set; the stored token stays until a new one validates, so a transient failure can't wipe it.
     */
    private fun onRejected(entry: Cached) {
        if (cached !== entry || !checking.compareAndSet(null, entry)) return
        scope.launch {
            try {
                AzureDevOpsSignIn.validate(AzureDevOpsClient.forOrganization(entry.organization, entry.pat), null)
            } catch (e: AzureDevOpsException) {
                if (e.kind == AzureDevOpsException.Kind.REJECTED) {
                    synchronized(this@AzureDevOpsConnection) { if (cached === entry) cached = null }
                    _rejected.value = AzureDevOpsClient.REJECTED_MESSAGE
                }
            } finally {
                checking.set(null)
            }
        }
    }
}

package io.github.antomys.azdoprreview.settings

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import io.github.antomys.azdoprreview.azdo.AzureDevOpsClient
import io.github.antomys.azdoprreview.azdo.AzureDevOpsException
import io.github.antomys.azdoprreview.azdo.AzureDevOpsProvider
import io.github.antomys.azdoprreview.azdo.AzureDevOpsSignIn
import io.github.antomys.azdoprreview.review.RepoRef
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

    /** Null while the settings are incomplete. */
    suspend fun target(): ReviewTarget? = withContext(Dispatchers.IO) {
        val account = AzureDevOpsAccount.getInstance()
        val settings = project.service<AzureDevOpsProjectSettings>()
        val organization = account.organization
        val pat = account.pat()
        if (organization.isEmpty() || pat.isNullOrEmpty() || settings.project.isEmpty() || settings.repository.isEmpty()) {
            return@withContext null
        }
        ReviewTarget(provider(organization, pat), RepoRef(settings.project, settings.repository))
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

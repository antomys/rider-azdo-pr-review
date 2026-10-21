package io.github.antomys.azdoprreview.toolwindow

import io.github.antomys.azdoprreview.review.RepoRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the sign-in panel edits. An empty [pat] means "use the saved token". */
data class SignInFields(val organization: String, val pat: String, val project: String, val repository: String)

/** Where a validated sign-in is saved. Calls may block (password storage), so they run off the EDT. */
interface SignInStore {
    /** The saved token for [organization], or null. */
    fun savedPat(organization: String): String?

    fun setPat(organization: String, pat: String)

    fun saveSettings(organization: String, project: String, repository: String)
}

/**
 * Validates a token and saves it only when it works. Swing-free.
 *
 * [validate] throws when the token or repository can't be used; its message is shown as is. [signedIn] runs once after
 * saving, with `true` when a new token was saved (not the stored one reused).
 */
class SignInViewModel(
    private val scope: CoroutineScope,
    private val store: SignInStore,
    private val validate: suspend (organization: String, pat: String, repo: RepoRef) -> Unit,
    private val signedIn: (newToken: Boolean) -> Unit,
) {
    sealed interface State {
        data object Idle : State
        data object Validating : State
        data class Failed(val message: String) : State
        data object SignedIn : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var job: Job? = null

    /** Validates [fields], then saves them; nothing is saved on failure. */
    fun signIn(fields: SignInFields): Job {
        job?.cancel()
        return scope.launch {
            _state.value = State.Validating
            _state.value = try {
                attempt(fields)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                State.Failed(e.message ?: e.javaClass.simpleName)
            }
        }.also { job = it }
    }

    private suspend fun attempt(fields: SignInFields): State {
        val organization = fields.organization.trim()
        if (!ORGANIZATION.matches(organization)) return State.Failed("Enter the organization: the name in https://dev.azure.com/<organization>.")
        val project = fields.project.trim()
        val repository = fields.repository.trim()
        if (project.isEmpty() || repository.isEmpty()) return State.Failed("Enter the project and repository.")
        val typed = fields.pat.trim()
        val pat = typed.ifEmpty { withContext(Dispatchers.IO) { store.savedPat(organization) } }
            ?: return State.Failed("Enter a personal access token.")

        validate(organization, pat, RepoRef(project, repository))

        withContext(Dispatchers.IO) {
            store.saveSettings(organization, project, repository)
            if (typed.isNotEmpty()) store.setPat(organization, typed)
        }
        signedIn(typed.isNotEmpty())
        return State.SignedIn
    }

    companion object {
        /** Azure DevOps organization names: letters, digits and hyphens. */
        private val ORGANIZATION = Regex("[A-Za-z0-9][A-Za-z0-9-]*")

        /** The page where [organization]'s tokens are created, or null while the name isn't valid. */
        fun tokenUrl(organization: String): String? =
            organization.trim().takeIf(ORGANIZATION::matches)?.let { "https://dev.azure.com/$it/_usersSettings/tokens" }
    }
}

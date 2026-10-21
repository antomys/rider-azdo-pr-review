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

/** What the sign-in panel asks for. An empty [pat] means "use the saved token". */
data class SignInFields(val organization: String, val pat: String)

/** Where a validated sign-in is saved. Calls may block (password storage), so they run off the EDT. */
interface SignInStore {
    /** The saved token for [organization], or null. */
    fun savedPat(organization: String): String?

    fun setPat(organization: String, pat: String)

    /** Remembers [organization] for projects whose repository isn't on an Azure DevOps Git remote. */
    fun saveOrganization(organization: String)

    /** Remembers the repository chosen for this IDE project, which has no Azure DevOps Git remote. */
    fun saveRepository(repo: RepoRef)
}

/**
 * Validates a token and saves it only when it works. Swing-free.
 *
 * [repo] is the repository the project's pull requests come from (its Git remote, or the settings), or null when it
 * isn't known: then a valid token leads to [State.ChooseRepository] with the [repositories] it can read.
 * [validate] throws when the token or repository can't be used; its message is shown as is. [signedIn] runs once after
 * saving, with `true` when a new token was saved (not the stored one reused).
 */
class SignInViewModel(
    private val scope: CoroutineScope,
    private val store: SignInStore,
    private val repo: RepoRef?,
    private val validate: suspend (organization: String, pat: String, repo: RepoRef?) -> Unit,
    private val repositories: suspend (organization: String, pat: String) -> List<RepoRef>,
    private val signedIn: (newToken: Boolean) -> Unit,
) {
    sealed interface State {
        data object Idle : State
        data object Validating : State
        data class Failed(val message: String) : State

        /** The token works, but the project has no Azure DevOps remote: one of [repositories] has to be picked. */
        data class ChooseRepository(val repositories: List<RepoRef>) : State
        data object SignedIn : State
    }

    /** A validated token waiting for the repository choice; nothing is saved before it. */
    private class Pending(val organization: String, val pat: String, val newToken: Boolean)

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var job: Job? = null
    private var pending: Pending? = null

    /** Validates [fields] (and the known repository), then saves them; nothing is saved on failure. */
    fun signIn(fields: SignInFields): Job = step { attempt(fields) }

    /** Saves the token validated by [signIn] together with the chosen [repo]. */
    fun choose(repo: RepoRef): Job = step {
        val pending = pending ?: return@step State.Idle
        save(pending, repo)
    }

    /** Runs one step of the sign-in, showing progress, then its outcome. */
    private fun step(block: suspend () -> State): Job {
        job?.cancel()
        return scope.launch {
            _state.value = State.Validating
            _state.value = try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                State.Failed(e.message ?: e.javaClass.simpleName)
            }
        }.also { job = it }
    }

    private suspend fun attempt(fields: SignInFields): State {
        pending = null
        val organization = fields.organization.trim()
        if (!ORGANIZATION.matches(organization)) return State.Failed("Enter the organization: the name in https://dev.azure.com/<organization>.")
        val typed = fields.pat.trim()
        val pat = typed.ifEmpty { withContext(Dispatchers.IO) { store.savedPat(organization) } }
            ?: return State.Failed("Enter a personal access token.")

        validate(organization, pat, repo)
        val validated = Pending(organization, pat, newToken = typed.isNotEmpty())
        if (repo != null) return save(validated, null)

        val found = repositories(organization, pat)
        if (found.isEmpty()) return State.Failed("The token can't read any repository in $organization.")
        pending = validated
        return State.ChooseRepository(found)
    }

    private suspend fun save(validated: Pending, chosen: RepoRef?): State {
        withContext(Dispatchers.IO) {
            store.saveOrganization(validated.organization)
            if (validated.newToken) store.setPat(validated.organization, validated.pat)
            chosen?.let(store::saveRepository)
        }
        pending = null
        signedIn(validated.newToken)
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

package io.github.antomys.azdoprreview.toolwindow

import io.github.antomys.azdoprreview.azdo.AzureDevOpsClient
import io.github.antomys.azdoprreview.azdo.AzureDevOpsException
import io.github.antomys.azdoprreview.review.RepoRef
import io.github.antomys.azdoprreview.toolwindow.SignInViewModel.State
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SignInViewModelTest {
    private class FakeStore(private val saved: String? = null) : SignInStore {
        val pats = mutableListOf<Pair<String, String>>()
        val organizations = mutableListOf<String>()
        val repositories = mutableListOf<RepoRef>()

        override fun savedPat(organization: String) = saved
        override fun setPat(organization: String, pat: String) {
            pats += organization to pat
        }
        override fun saveOrganization(organization: String) {
            organizations += organization
        }
        override fun saveRepository(repo: RepoRef) {
            repositories += repo
        }
    }

    private val fields = SignInFields(" fabrikam ", " new-token ")
    private val remoteRepo = RepoRef("Fiber Project", "web-app")
    private val available = listOf(RepoRef("Backend", "api"), RepoRef("Fiber Project", "web-app"))
    private val validated = mutableListOf<Triple<String, String, RepoRef?>>()
    private val listed = mutableListOf<Pair<String, String>>()
    private val signedIn = mutableListOf<Boolean>()

    private fun vm(scope: CoroutineScope, store: SignInStore, repo: RepoRef?, failure: Exception? = null, repos: List<RepoRef> = available) =
        SignInViewModel(
            scope, store, repo,
            validate = { org, pat, r ->
                validated += Triple(org, pat, r)
                failure?.let { throw it }
            },
            repositories = { org, pat ->
                listed += org to pat
                repos
            },
            signedIn = { signedIn += it },
        )

    @Test
    fun `with the repository known from the Git remote, saves the token exactly once after it validates`() = runBlocking {
        val store = FakeStore()
        val vm = vm(this, store, remoteRepo)

        vm.signIn(fields).join()

        assertEquals(State.SignedIn, vm.state.value)
        assertEquals(listOf(Triple("fabrikam", "new-token", remoteRepo)), validated)
        assertEquals(listOf("fabrikam" to "new-token"), store.pats)
        assertEquals(listOf("fabrikam"), store.organizations)
        assertEquals(emptyList<RepoRef>(), store.repositories)
        assertEquals(emptyList<Pair<String, String>>(), listed)
        assertEquals(listOf(true), signedIn)
    }

    @Test
    fun `without a known repository, offers the token's repositories and saves only after the choice`() = runBlocking {
        val store = FakeStore()
        val vm = vm(this, store, repo = null)

        vm.signIn(fields).join()

        assertEquals(State.ChooseRepository(available), vm.state.value)
        assertEquals(listOf(Triple("fabrikam", "new-token", null as RepoRef?)), validated)
        assertEquals(listOf("fabrikam" to "new-token"), listed)
        assertEquals(emptyList<Pair<String, String>>(), store.pats)
        assertEquals(emptyList<Boolean>(), signedIn)

        vm.choose(RepoRef("Backend", "api")).join()

        assertEquals(State.SignedIn, vm.state.value)
        assertEquals(listOf("fabrikam" to "new-token"), store.pats)
        assertEquals(listOf(RepoRef("Backend", "api")), store.repositories)
        assertEquals(listOf(true), signedIn)
    }

    @Test
    fun `says so when the token can't read any repository`() = runBlocking {
        val store = FakeStore()
        val vm = vm(this, store, repo = null, repos = emptyList())

        vm.signIn(fields).join()

        assertEquals(State.Failed("The token can't read any repository in fabrikam."), vm.state.value)
        assertEquals(emptyList<Pair<String, String>>(), store.pats)
    }

    @Test
    fun `saves nothing when the token is rejected`() = runBlocking {
        val store = FakeStore()
        val vm = vm(this, store, remoteRepo, AzureDevOpsException(401, "${AzureDevOpsClient.REJECTED_MESSAGE} (HTTP 401)"))

        vm.signIn(fields).join()

        assertEquals(State.Failed("${AzureDevOpsClient.REJECTED_MESSAGE} (HTTP 401)"), vm.state.value)
        assertEquals(emptyList<Pair<String, String>>(), store.pats)
        assertEquals(emptyList<String>(), store.organizations)
        assertEquals(emptyList<Boolean>(), signedIn)
    }

    @Test
    fun `retries with the saved token when the field is empty, without saving it again`() = runBlocking {
        val store = FakeStore(saved = "saved-token")
        val vm = vm(this, store, remoteRepo)

        vm.signIn(fields.copy(pat = "")).join()

        assertEquals(State.SignedIn, vm.state.value)
        assertEquals("saved-token", validated.single().second)
        assertEquals(emptyList<Pair<String, String>>(), store.pats)
        assertEquals(listOf(false), signedIn)
    }

    @Test
    fun `asks for missing fields before calling the service`() = runBlocking {
        val vm = vm(this, FakeStore(), remoteRepo)

        vm.signIn(fields.copy(pat = "")).join()
        assertEquals(State.Failed("Enter a personal access token."), vm.state.value)
        vm.signIn(fields.copy(organization = "https://dev.azure.com/fabrikam")).join()
        assertEquals(State.Failed("Enter the organization: the name in https://dev.azure.com/<organization>."), vm.state.value)

        assertEquals(emptyList<Triple<String, String, RepoRef?>>(), validated)
    }

    @Test
    fun `links to the organization's token page`() {
        assertEquals("https://dev.azure.com/fabrikam/_usersSettings/tokens", SignInViewModel.tokenUrl(" fabrikam "))
        assertNull(SignInViewModel.tokenUrl(""))
        assertNull(SignInViewModel.tokenUrl("a/b"))
    }
}

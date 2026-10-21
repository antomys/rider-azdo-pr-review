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
        val settings = mutableListOf<Triple<String, String, String>>()

        override fun savedPat(organization: String) = saved
        override fun setPat(organization: String, pat: String) {
            pats += organization to pat
        }
        override fun saveSettings(organization: String, project: String, repository: String) {
            settings += Triple(organization, project, repository)
        }
    }

    private val fields = SignInFields(" fabrikam ", " new-token ", "Fiber Project", "web-app")
    private val validated = mutableListOf<Triple<String, String, RepoRef>>()
    private val signedIn = mutableListOf<Boolean>()

    private fun vm(scope: CoroutineScope, store: SignInStore, failure: Exception? = null) =
        SignInViewModel(
            scope, store,
            validate = { org, pat, repo ->
                validated += Triple(org, pat, repo)
                failure?.let { throw it }
            },
            signedIn = { signedIn += it },
        )

    @Test
    fun `saves the token exactly once after it validates`() = runBlocking {
        val store = FakeStore()
        val vm = vm(this, store)

        vm.signIn(fields).join()

        assertEquals(State.SignedIn, vm.state.value)
        assertEquals(listOf(Triple("fabrikam", "new-token", RepoRef("Fiber Project", "web-app"))), validated)
        assertEquals(listOf("fabrikam" to "new-token"), store.pats)
        assertEquals(listOf(Triple("fabrikam", "Fiber Project", "web-app")), store.settings)
        assertEquals(listOf(true), signedIn)
    }

    @Test
    fun `saves nothing when the token is rejected`() = runBlocking {
        val store = FakeStore()
        val vm = vm(this, store, AzureDevOpsException(401, "${AzureDevOpsClient.REJECTED_MESSAGE} (HTTP 401)"))

        vm.signIn(fields).join()

        assertEquals(State.Failed("${AzureDevOpsClient.REJECTED_MESSAGE} (HTTP 401)"), vm.state.value)
        assertEquals(emptyList<Pair<String, String>>(), store.pats)
        assertEquals(emptyList<Triple<String, String, String>>(), store.settings)
        assertEquals(emptyList<Boolean>(), signedIn)
    }

    @Test
    fun `retries with the saved token when the field is empty, without saving it again`() = runBlocking {
        val store = FakeStore(saved = "saved-token")
        val vm = vm(this, store)

        vm.signIn(fields.copy(pat = "")).join()

        assertEquals(State.SignedIn, vm.state.value)
        assertEquals("saved-token", validated.single().second)
        assertEquals(emptyList<Pair<String, String>>(), store.pats)
        assertEquals(listOf(false), signedIn)
    }

    @Test
    fun `asks for missing fields before calling the service`() = runBlocking {
        val vm = vm(this, FakeStore())

        vm.signIn(fields.copy(pat = "")).join()
        assertEquals(State.Failed("Enter a personal access token."), vm.state.value)
        vm.signIn(fields.copy(organization = "https://dev.azure.com/fabrikam")).join()
        assertEquals(State.Failed("Enter the organization: the name in https://dev.azure.com/<organization>."), vm.state.value)
        vm.signIn(fields.copy(repository = " ")).join()
        assertEquals(State.Failed("Enter the project and repository."), vm.state.value)

        assertEquals(emptyList<Triple<String, String, RepoRef>>(), validated)
    }

    @Test
    fun `links to the organization's token page`() {
        assertEquals("https://dev.azure.com/fabrikam/_usersSettings/tokens", SignInViewModel.tokenUrl(" fabrikam "))
        assertNull(SignInViewModel.tokenUrl(""))
        assertNull(SignInViewModel.tokenUrl("a/b"))
    }
}

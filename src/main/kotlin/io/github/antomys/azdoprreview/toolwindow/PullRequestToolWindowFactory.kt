package io.github.antomys.azdoprreview.toolwindow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.panels.Wrapper
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import io.github.antomys.azdoprreview.azdo.AzureDevOpsClient
import io.github.antomys.azdoprreview.azdo.AzureDevOpsSignIn
import io.github.antomys.azdoprreview.diff.ReviewDiff
import io.github.antomys.azdoprreview.review.RepoRef
import io.github.antomys.azdoprreview.review.ReviewRef
import io.github.antomys.azdoprreview.review.ReviewSummary
import io.github.antomys.azdoprreview.review.ReviewTarget
import io.github.antomys.azdoprreview.settings.AzureDevOpsAccount
import io.github.antomys.azdoprreview.settings.AzureDevOpsConfigurable
import io.github.antomys.azdoprreview.settings.AzureDevOpsConnection
import io.github.antomys.azdoprreview.settings.AzureDevOpsProjectSettings
import io.github.antomys.azdoprreview.settings.ReviewLocation
import io.github.antomys.azdoprreview.settings.ReviewMarksStore
import io.github.antomys.azdoprreview.settings.ViewedFilesStore
import io.github.antomys.azdoprreview.timeline.TimelineFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.swing.JComponent

class PullRequestToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val connection = project.service<AzureDevOpsConnection>()
        val scope = connection.scope.childScope()
        val vm = PullRequestListViewModel(scope, ReviewMarksStore.getInstance(), connection::target)
        val panel = PullRequestListPanel(
            vm, scope,
            openSettings = {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, AzureDevOpsConfigurable::class.java)
                vm.refresh()
            },
            openReview = { review -> vm.target?.let { openDetails(project, toolWindow, scope, it, review) } },
        )
        val tab = Wrapper(panel)
        val list = addContent(toolWindow, tab, "Pull Requests", scope, closeable = false)
        showSignInWhenNeeded(project, toolWindow, scope, vm, connection, tab, panel, list)
        vm.refresh()
    }

    /**
     * Puts the sign-in panel in place of the list while the settings are incomplete or the saved token was rejected
     * (by any call, also from a pull request tab: then the list tab is selected to show it).
     */
    private fun showSignInWhenNeeded(
        project: Project, toolWindow: ToolWindow, scope: CoroutineScope, vm: PullRequestListViewModel,
        connection: AzureDevOpsConnection, tab: Wrapper, listPanel: JComponent, list: Content,
    ) {
        var signInScope: CoroutineScope? = null
        scope.launch(Dispatchers.EDT) {
            combine(vm.state, connection.rejected) { state, reason ->
                when {
                    reason != null -> SignIn(reason)
                    state is PullRequestListViewModel.State.NotConfigured -> SignIn(null)
                    else -> null
                }
            }.distinctUntilChanged().collect { signIn ->
                signInScope?.cancel()
                signInScope = null
                if (signIn == null) {
                    tab.setContent(listPanel)
                    return@collect
                }
                val panelScope = scope.childScope().also { signInScope = it }
                val (location, hasSavedPat) = withContext(Dispatchers.IO) {
                    ReviewLocation.of(project).let { it to !AzureDevOpsAccount.getInstance().pat(it.organization).isNullOrEmpty() }
                }
                val signInVm = SignInViewModel(
                    panelScope, AccountStore(project), location.repo,
                    validate = { organization, pat, repo -> AzureDevOpsSignIn.validate(AzureDevOpsClient.forOrganization(organization, pat), repo) },
                    repositories = { organization, pat -> AzureDevOpsSignIn.repositories(AzureDevOpsClient.forOrganization(organization, pat)) },
                    signedIn = { newToken ->
                        scope.launch(Dispatchers.EDT) {
                            // Tabs opened with a rejected token can't work any more; they close rather than fail on every action.
                            if (newToken && signIn.reason != null) {
                                toolWindow.contentManager.contents.filter { it.isCloseable }.forEach { toolWindow.contentManager.removeContent(it, true) }
                            }
                            vm.refresh()
                            connection.signedIn()
                        }
                    },
                )
                tab.setContent(SignInPanel(signInVm, panelScope, location.organization, sourceText(location), signIn.reason, hasSavedPat))
                if (signIn.reason != null) toolWindow.contentManager.setSelectedContent(list)
            }
        }
    }

    /** Which repository the sign-in is for, and where that comes from. */
    private fun sourceText(location: ReviewLocation): String {
        val repo = location.repo
            ?: return "This project has no Azure DevOps Git remote: after signing in, choose the repository to review."
        val name = "${repo.project} / ${repo.repository}"
        return if (location.fromRemote) "Pull requests of $name, from this project's Git remote." else "Pull requests of $name, from the settings."
    }

    /** Why the sign-in panel is shown: the rejection message, or null for missing settings. */
    private data class SignIn(val reason: String?)

    /** Saves a validated sign-in: organization app-wide, token per organization in the password storage, a chosen repository per project. */
    private class AccountStore(private val project: Project) : SignInStore {
        private val account = AzureDevOpsAccount.getInstance()

        override fun savedPat(organization: String) = account.pat(organization)

        override fun setPat(organization: String, pat: String) = account.setPat(organization, pat)

        override fun saveOrganization(organization: String) {
            account.organization = organization
        }

        override fun saveRepository(repo: RepoRef) {
            project.service<AzureDevOpsProjectSettings>().let {
                it.project = repo.project
                it.repository = repo.repository
            }
        }
    }

    /** Shows the review's details tab and its timeline editor, reusing them if they're already open. */
    private fun openDetails(project: Project, toolWindow: ToolWindow, parent: CoroutineScope, target: ReviewTarget, review: ReviewSummary) {
        val contents = toolWindow.contentManager
        contents.contents.firstOrNull { it.getUserData(REVIEW) == review.ref }?.let {
            contents.setSelectedContent(it)
            it.getUserData(TIMELINE)?.let { timeline -> FileEditorManager.getInstance(project).openFile(timeline, true) }
            return
        }
        val scope = parent.childScope()
        val vm = PullRequestDetailsViewModel(scope, target, review, ViewedFilesStore.getInstance(), ReviewMarksStore.getInstance())
        val diff = ReviewDiff(project, vm, scope)
        val timeline = TimelineFile(project, vm, scope, diff)
        val openTimeline = { FileEditorManager.getInstance(project).openFile(timeline, true); Unit }
        val list = contents.getContent(0)!!
        val panel = PullRequestDetailsPanel(project, vm, scope, diff, onBack = { contents.setSelectedContent(list) }, openTimeline = openTimeline)
        addContent(toolWindow, panel, "#${review.ref.id}", scope, closeable = true).apply {
            putUserData(REVIEW, review.ref)
            putUserData(TIMELINE, timeline)
        }
        // The timeline shows this tab's data, so it closes with the tab.
        scope.coroutineContext.job.invokeOnCompletion {
            ApplicationManager.getApplication().invokeLater({ FileEditorManager.getInstance(project).closeFile(timeline) }, project.disposed)
        }
        vm.refresh()
        openTimeline()
    }

    /** Adds a tab whose [scope] is cancelled when the tab closes. */
    private fun addContent(toolWindow: ToolWindow, component: JComponent, name: String, scope: CoroutineScope, closeable: Boolean) =
        ContentFactory.getInstance().createContent(component, name, false).also {
            it.isCloseable = closeable
            Disposer.register(it) { scope.cancel() }
            toolWindow.contentManager.addContent(it)
            toolWindow.contentManager.setSelectedContent(it)
        }

    private fun CoroutineScope.childScope() = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext.job))

    private companion object {
        val REVIEW = Key.create<ReviewRef>("AzureDevOpsPrReview.review")
        val TIMELINE = Key.create<TimelineFile>("AzureDevOpsPrReview.timeline")
    }
}

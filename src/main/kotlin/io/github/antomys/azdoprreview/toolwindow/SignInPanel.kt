package io.github.antomys.azdoprreview.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.EDT
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.AlignY
import com.intellij.ui.dsl.builder.COLUMNS_MEDIUM
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.antomys.azdoprreview.azdo.AzureDevOpsClient
import io.github.antomys.azdoprreview.review.RepoRef
import io.github.antomys.azdoprreview.toolwindow.SignInViewModel.State
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Rectangle
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.Scrollable

/**
 * Asks for the organization and a personal access token, and signs in. Shown in place of the pull request list. The
 * repository comes from the project's Git remote (or the settings), which [source] describes; without one, the
 * repositories the token can read are offered once it validates. [reason] says why, when the saved token was rejected;
 * [hasSavedPat] allows leaving the token empty to try the saved one again.
 */
class SignInPanel(
    private val vm: SignInViewModel,
    scope: CoroutineScope,
    initialOrganization: String,
    source: String,
    reason: String?,
    hasSavedPat: Boolean,
) : JPanel(BorderLayout()) {
    private val organization = JBTextField(initialOrganization)
    private val pat = JBPasswordField()
    private val repositories = ComboBox<RepoRef>().apply {
        renderer = object : SimpleListCellRenderer<RepoRef>() {
            override fun customize(list: JList<out RepoRef>, value: RepoRef?, index: Int, selected: Boolean, hasFocus: Boolean) {
                text = value?.let { "${it.project} / ${it.repository}" }.orEmpty()
            }
        }
        isSwingPopup = false
    }
    private val open = JButton("Open").apply { addActionListener { choose() } }
    private lateinit var chooseRow: Row
    private val signIn = JButton("Sign In").apply { addActionListener { signIn() } }
    private val progress = JBLabel("Checking the token…", AnimatedIcon.Default.INSTANCE, JBLabel.LEADING).apply { isVisible = false }
    private val error = wrapping("").apply { foreground = UIUtil.getErrorForeground() }
    private val retry = ActionLink("Retry") { signIn() }
    private lateinit var errorRow: Row
    private lateinit var retryRow: Row

    init {
        listOf(organization, pat).forEach { it.addActionListener { signIn() } }
        val content = panel {
            if (reason != null) {
                row {
                    icon(AllIcons.General.Warning).align(AlignY.TOP)
                    cell(wrapping(reason)).align(AlignX.FILL).resizableColumn()
                }
            }
            row { label("Sign in to Azure DevOps").bold() }
            row("Organization:") {
                cell(organization).columns(COLUMNS_MEDIUM).comment("The name in https://dev.azure.com/&lt;organization&gt;")
            }
            row("Personal access token:") {
                cell(pat).columns(COLUMNS_MEDIUM)
                    .comment(if (hasSavedPat) "Leave empty to use the saved token." else "Stored in the IDE password storage.")
            }
            row {
                comment(
                    "Scopes: ${AzureDevOpsClient.SCOPES.replace("&", "&amp;")}.<br>" +
                        "Tokens for all organizations stop working on 1 December 2026: create one for this organization only.",
                )
            }
            row { link("Create token") { openTokenPage() } }
            row { cell(wrapping(source)).align(AlignX.FILL).resizableColumn() }
            row {
                cell(signIn)
                cell(progress)
            }
            chooseRow = row("Repository:") {
                cell(repositories).columns(COLUMNS_MEDIUM)
                cell(open)
            }.visible(false)
            errorRow = row { cell(error).align(AlignX.FILL).resizableColumn() }.visible(false)
            retryRow = row { cell(retry) }.visible(false)
        }.apply { border = JBUI.Borders.empty(12, 16) }
        add(ScrollPaneFactory.createScrollPane(WidthTracking(content), true), BorderLayout.CENTER)

        scope.launch(Dispatchers.EDT) { vm.state.collect(::render) }
    }

    private fun signIn() {
        if (vm.state.value == State.Validating) return
        vm.signIn(SignInFields(organization.text, String(pat.password)))
    }

    private fun choose() {
        val repo = repositories.selectedItem as? RepoRef ?: return
        vm.choose(repo)
    }

    private fun openTokenPage() {
        val url = SignInViewModel.tokenUrl(organization.text)
        if (url == null) {
            showError("Enter the organization first.", retry = false)
        } else {
            BrowserUtil.browse(url)
        }
    }

    private fun render(state: State) {
        val busy = state == State.Validating
        progress.isVisible = busy
        listOf(organization, pat, signIn, repositories, open).forEach { it.isEnabled = !busy }
        when (state) {
            is State.Failed -> showError(state.message, retry = true)
            else -> {
                errorRow.visible(false)
                retryRow.visible(false)
            }
        }
        if (state is State.ChooseRepository) {
            repositories.model = DefaultComboBoxModel(state.repositories.toTypedArray())
            chooseRow.visible(true)
            repositories.requestFocusInWindow()
        } else if (state !is State.Validating) {
            chooseRow.visible(false)
        }
        // The token isn't needed once it's saved or checked.
        if (state == State.SignedIn) pat.text = ""
    }

    private fun showError(message: String, retry: Boolean) {
        error.text = message
        errorRow.visible(true)
        retryRow.visible(retry)
    }

    /** Plain text (never HTML) that wraps at the panel's width. */
    private fun wrapping(text: String) = JBTextArea(text).apply {
        isEditable = false
        isOpaque = false
        lineWrap = true
        wrapStyleWord = true
        columns = COLUMNS_MEDIUM
        border = JBUI.Borders.empty()
        font = UIUtil.getLabelFont()
    }

    /** Lets the form follow the tool window's width, so long messages wrap instead of scrolling sideways. */
    private class WidthTracking(view: JComponent) : JPanel(BorderLayout()), Scrollable {
        init {
            isOpaque = false
            add(view, BorderLayout.NORTH)
        }

        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
        override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = JBUI.scale(16)
        override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = visibleRect.height
        override fun getScrollableTracksViewportWidth() = true
        override fun getScrollableTracksViewportHeight() = false
    }
}

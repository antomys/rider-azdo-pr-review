package io.github.antomys.azdoprreview.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.service
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.COLUMNS_MEDIUM
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.rows
import com.intellij.ui.dsl.builder.selected
import io.github.antomys.azdoprreview.ai.Availability
import io.github.antomys.azdoprreview.ai.ClaudeCodeReviewer
import io.github.antomys.azdoprreview.ui.plainText
import javax.swing.JLabel

class AzureDevOpsConfigurable(private val project: Project) : BoundConfigurable(DISPLAY_NAME) {
    private val account = AzureDevOpsAccount.getInstance()
    private val settings = project.service<AzureDevOpsProjectSettings>()
    private val ai = AiReviewSettings.getInstance()

    /** A newly typed PAT. The saved one is never shown; an empty field keeps it. */
    private var newPat = ""

    override fun createPanel() = panel {
        group("Account") {
            row("Organization:") {
                textField().bindText(account::organization).columns(COLUMNS_MEDIUM)
                    .comment("The name in https://dev.azure.com/&lt;organization&gt;")
            }
            row("Personal access token:") {
                passwordField().bindText(::newPat).columns(COLUMNS_MEDIUM)
                    .comment(
                        "Leave empty to keep the saved token. Stored in the IDE password storage.<br>" +
                            "Scopes: Code (Read &amp; write), Identity (Read), Graph (Read).",
                    )
            }
        }
        group("Repository") {
            // The project's Git remote wins over these fields; a local file, read once when the page opens.
            val remote = ReviewLocation.remote(project)
            row {
                comment(
                    if (remote != null) {
                        "This project's Git remote is ${remote.organization} / ${remote.project} / ${remote.repository}: its pull requests are reviewed, and the fields below aren't used."
                    } else {
                        "This project has no Azure DevOps Git remote, so the pull requests of the repository below are reviewed."
                    },
                )
            }
            row("Project:") { textField().bindText(settings::project).columns(COLUMNS_MEDIUM) }
            row("Repository:") { textField().bindText(settings::repository).columns(COLUMNS_MEDIUM) }
        }
        group("AI Review") {
            lateinit var enabled: Cell<JBCheckBox>
            row {
                enabled = checkBox("Enable Review with AI").bindSelected(ai::enabled)
                    .comment("Sends the pull request's diffs to Claude Code, under your own Claude account, after you confirm each run.")
            }
            indent {
                lateinit var status: Cell<JLabel>
                row("Claude Code CLI:") {
                    val path = textField().bindText(ai::cliPath).columns(COLUMNS_LARGE)
                        .comment("Leave empty to use claude from PATH or ~/.local/bin")
                    button("Detect") { detect(path.component.text, status.component) }
                }
                row("") { status = label("").applyToComponent { plainText() } }
                row("Exclude files:") {
                    textArea().bindText(ai::excludes).rows(4).columns(COLUMNS_MEDIUM)
                        // Setting the text leaves the caret at the end, which would scroll past the first globs.
                        .also { cell -> cell.onReset { cell.component.caretPosition = 0 } }
                        .comment("Never sent. Globs separated by commas or new lines; a glob with / matches the path, otherwise the file name.")
                }
                row("Extra instructions:") { textArea().bindText(ai::instructions).rows(3).columns(COLUMNS_LARGE) }
                row("Max findings:") { intTextField(1..AiReviewSettings.MAX_FINDINGS_LIMIT).bindIntText(ai::maxFindings) }
            }.enabledIf(enabled.selected)
        }
    }

    /** Shows whether the CLI at [path] (or on PATH when empty) runs, and its version; `--version` runs off the EDT. */
    private fun detect(path: String, status: JLabel) {
        status.text = "Checking…"
        ApplicationManager.getApplication().executeOnPooledThread {
            val text = when (val availability = ClaudeCodeReviewer(path).availability()) {
                is Availability.Available -> "Found: ${availability.version} at ${availability.location}"
                is Availability.Unavailable -> "Not found: ${availability.reason}"
            }
            ApplicationManager.getApplication().invokeLater({ status.text = text }, ModalityState.any())
        }
    }

    override fun apply() {
        super.apply()
        val pat = newPat.trim()
        newPat = ""
        if (pat.isNotEmpty()) ApplicationManager.getApplication().executeOnPooledThread { account.setPat(pat) }
    }

    companion object {
        const val DISPLAY_NAME = "Azure DevOps PR Review"
    }
}

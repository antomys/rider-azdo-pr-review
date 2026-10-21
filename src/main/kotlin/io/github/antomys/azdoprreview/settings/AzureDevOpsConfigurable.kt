package io.github.antomys.azdoprreview.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.ui.dsl.builder.COLUMNS_MEDIUM
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel

class AzureDevOpsConfigurable(project: Project) : BoundConfigurable(DISPLAY_NAME) {
    private val account = AzureDevOpsAccount.getInstance()
    private val settings = project.service<AzureDevOpsProjectSettings>()

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
            row("Project:") { textField().bindText(settings::project).columns(COLUMNS_MEDIUM) }
            row("Repository:") { textField().bindText(settings::repository).columns(COLUMNS_MEDIUM) }
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

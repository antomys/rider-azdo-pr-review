package io.github.antomys.azdoprreview.settings

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import io.github.antomys.azdoprreview.azdo.AzureDevOpsRemote
import io.github.antomys.azdoprreview.review.RepoRef
import java.io.File

/**
 * Which organization and repository an IDE project's pull requests come from. [repo] is null while it isn't known;
 * [fromRemote] tells whether it came from the project's Git remote rather than the settings.
 */
data class ReviewLocation(val organization: String, val repo: RepoRef?, val fromRemote: Boolean) {
    companion object {
        /**
         * The project's Azure DevOps Git remote wins, so the plugin follows the repository that is open. Without one,
         * the saved organization and the project and repository from the settings are used.
         */
        fun of(remote: AzureDevOpsRemote?, organization: String, project: String, repository: String): ReviewLocation =
            if (remote != null) {
                ReviewLocation(remote.organization, RepoRef(remote.project, remote.repository), fromRemote = true)
            } else {
                ReviewLocation(organization, RepoRef(project, repository).takeIf { project.isNotEmpty() && repository.isNotEmpty() }, fromRemote = false)
            }

        /** Reads [project]'s Git remote and settings. Touches the file system: call off the EDT. */
        fun of(project: Project): ReviewLocation {
            val settings = project.service<AzureDevOpsProjectSettings>()
            return of(remote(project), AzureDevOpsAccount.getInstance().organization, settings.project, settings.repository)
        }

        /** The Azure DevOps remote of the repository holding [project], if any. Reads files: call off the EDT. */
        fun remote(project: Project): AzureDevOpsRemote? =
            project.basePath?.let { runCatching { AzureDevOpsRemote.find(File(it)) }.getOrNull() }
    }
}

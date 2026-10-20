package io.github.antomys.azdoprreview.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros

/** The Azure DevOps project and repository reviewed from this IDE project (workspace file, not shared). */
@Service(Service.Level.PROJECT)
@State(name = "AzureDevOpsPrReviewProject", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class AzureDevOpsProjectSettings : SimplePersistentStateComponent<AzureDevOpsProjectSettings.ProjectState>(ProjectState()) {
    class ProjectState : BaseState() {
        var project by string()
        var repository by string()
    }

    var project: String
        get() = state.project.orEmpty()
        set(value) {
            state.project = value.trim().ifEmpty { null }
        }

    var repository: String
        get() = state.repository.orEmpty()
        set(value) {
            state.repository = value.trim().ifEmpty { null }
        }
}

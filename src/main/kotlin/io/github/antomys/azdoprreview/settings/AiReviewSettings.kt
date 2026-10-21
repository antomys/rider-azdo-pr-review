package io.github.antomys.azdoprreview.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import io.github.antomys.azdoprreview.ai.AiReviewOptions
import io.github.antomys.azdoprreview.ai.AiReviewRequests

/** The opt-in AI review (IDE-wide, off by default). Nothing secret: the CLI signs in with its own account. */
@Service(Service.Level.APP)
@State(name = "AzureDevOpsPrReviewAi", storages = [Storage("azure-devops-pr-review.xml")])
class AiReviewSettings : SimplePersistentStateComponent<AiReviewSettings.AiState>(AiState()) {
    class AiState : BaseState() {
        var enabled by property(false)
        var cliPath by string()
        var excludes by string(AiReviewRequests.DEFAULT_EXCLUDES.joinToString("\n"))
        var instructions by string()
        var maxFindings by property(AiReviewRequests.DEFAULT_MAX_FINDINGS)
    }

    var enabled: Boolean
        get() = state.enabled
        set(value) {
            state.enabled = value
        }

    /** Empty: `claude` on PATH or in `~/.local/bin`. */
    var cliPath: String
        get() = state.cliPath.orEmpty()
        set(value) {
            state.cliPath = value.trim().ifEmpty { null }
        }

    /** Globs, separated by commas or new lines. */
    var excludes: String
        get() = state.excludes.orEmpty()
        set(value) {
            state.excludes = value.trim()
        }

    var instructions: String
        get() = state.instructions.orEmpty()
        set(value) {
            state.instructions = value.trim().ifEmpty { null }
        }

    var maxFindings: Int
        get() = state.maxFindings.coerceIn(1, MAX_FINDINGS_LIMIT)
        set(value) {
            state.maxFindings = value.coerceIn(1, MAX_FINDINGS_LIMIT)
        }

    fun options() = AiReviewOptions(AiReviewRequests.parseGlobs(excludes), instructions, maxFindings)

    companion object {
        const val MAX_FINDINGS_LIMIT = 100

        fun getInstance(): AiReviewSettings = service()
    }
}

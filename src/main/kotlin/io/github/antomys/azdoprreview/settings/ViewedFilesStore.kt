package io.github.antomys.azdoprreview.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import io.github.antomys.azdoprreview.toolwindow.ViewedFiles

/**
 * Viewed-file marks, kept locally: Azure DevOps has no public API for its own per-file "reviewed" marks.
 * Keys are "<review url>|<path>", values the viewed version.
 */
@Service(Service.Level.APP)
@State(name = "AzureDevOpsPrReviewViewedFiles", storages = [Storage("azure-devops-pr-review-viewed.xml", roamingType = RoamingType.DISABLED)])
class ViewedFilesStore : SimplePersistentStateComponent<ViewedFilesStore.ViewedState>(ViewedState()), ViewedFiles {
    class ViewedState : BaseState() {
        var files by map<String, String>()
    }

    @Synchronized
    override fun viewed(review: String): Map<String, String> {
        val prefix = "$review|"
        return state.files.filterKeys { it.startsWith(prefix) }.mapKeys { it.key.removePrefix(prefix) }
    }

    @Synchronized
    override fun setViewed(review: String, path: String, version: String?) {
        val key = "$review|$path"
        // A new map, not an in-place change, so the state registers the modification and gets saved.
        state.files = state.files.toMutableMap().apply { if (version == null) remove(key) else put(key, version) }
    }

    companion object {
        fun getInstance(): ViewedFilesStore = service()
    }
}

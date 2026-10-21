package io.github.antomys.azdoprreview.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import io.github.antomys.azdoprreview.review.ReviewMark
import io.github.antomys.azdoprreview.review.ReviewMarkStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Reviewed and last-viewed marks, kept in an IDE-level file outside the project so they are never committed, and not
 * synced. Keys are `{organization}/{repositoryId}/{pullRequestId}/{userId}`, values [ReviewMark.encode]d.
 */
@Service(Service.Level.APP)
@State(name = "AzureDevOpsPrReviewReviews", storages = [Storage("azure-devops-pr-review-reviews.xml", roamingType = RoamingType.DISABLED)])
class ReviewMarksStore : SimplePersistentStateComponent<ReviewMarksStore.MarksState>(MarksState()), ReviewMarkStore {
    class MarksState : BaseState() {
        var marks by map<String, String>()
    }

    private val _changes = MutableStateFlow(0L)
    override val changes: StateFlow<Long> = _changes.asStateFlow()

    @Synchronized
    override fun get(key: String): ReviewMark? = state.marks[key]?.let(ReviewMark::decode)

    @Synchronized
    override fun set(key: String, mark: ReviewMark?) {
        // A new map, not an in-place change, so the state registers the modification and gets saved.
        state.marks = state.marks.toMutableMap().apply { if (mark == null) remove(key) else put(key, mark.encode()) }
        _changes.value++
    }

    @Synchronized
    override fun all(): Map<String, ReviewMark> = state.marks.mapNotNull { (key, value) -> ReviewMark.decode(value)?.let { key to it } }.toMap()

    companion object {
        fun getInstance(): ReviewMarksStore = service()
    }
}

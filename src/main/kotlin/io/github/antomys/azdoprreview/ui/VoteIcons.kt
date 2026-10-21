package io.github.antomys.azdoprreview.ui

import com.intellij.icons.AllIcons
import io.github.antomys.azdoprreview.review.Vote
import javax.swing.Icon

object VoteIcons {
    /** The vote's status icon; null when the reviewer hasn't voted. */
    fun of(vote: Vote): Icon? = when (vote) {
        Vote.APPROVED -> AllIcons.RunConfigurations.TestPassed
        Vote.APPROVED_WITH_SUGGESTIONS -> AllIcons.General.InspectionsOK
        Vote.WAITING_FOR_AUTHOR -> AllIcons.General.Warning
        Vote.REJECTED -> AllIcons.RunConfigurations.TestFailed
        Vote.NONE -> null
    }

    val NAMES = mapOf(
        Vote.APPROVED to "Approved",
        Vote.APPROVED_WITH_SUGGESTIONS to "Approved with suggestions",
        Vote.WAITING_FOR_AUTHOR to "Waiting for author",
        Vote.REJECTED to "Rejected",
        Vote.NONE to "No vote",
    )
}

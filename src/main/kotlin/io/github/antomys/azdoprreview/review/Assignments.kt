package io.github.antomys.azdoprreview.review

import kotlinx.coroutines.CancellationException

/** How the user is asked to review: directly as a required or optional reviewer, or through one of their teams. */
enum class AssignmentRole { REQUIRED, OPTIONAL, TEAM }

/** The user's place on a review: [vote] is the team's vote when the assignment is only through [team]. */
data class Assignment(val role: AssignmentRole, val vote: Vote, val declined: Boolean, val team: ReviewUser? = null) {
    val label: String
        get() = when (role) {
            AssignmentRole.REQUIRED -> "Required"
            AssignmentRole.OPTIONAL -> "Optional"
            AssignmentRole.TEAM -> "via team ${team?.displayName.orEmpty()}"
        }

    companion object {
        /** [me]'s assignment on [review]; a direct one wins over a team's. Null when neither is a reviewer. */
        fun of(review: ReviewSummary, me: ReviewUser, teams: List<ReviewUser>): Assignment? {
            review.reviewers.firstOrNull { it.user.id == me.id }?.let {
                return Assignment(if (it.isRequired) AssignmentRole.REQUIRED else AssignmentRole.OPTIONAL, it.vote, it.hasDeclined)
            }
            for (team in teams) {
                val reviewer = review.reviewers.firstOrNull { it.user.id == team.id } ?: continue
                return Assignment(AssignmentRole.TEAM, reviewer.vote, declined = false, team = team)
            }
            return null
        }
    }
}

/** Reviews assigned to the user and their teams. [complete] is false when some team assignments couldn't be loaded. */
data class AssignedReviews(val reviews: List<ReviewSummary>, val teams: List<ReviewUser>, val complete: Boolean)

/**
 * Reviews matching [filter] where [me] or one of [me]'s teams is a reviewer. The host doesn't expand teams when
 * filtering by reviewer, so each team is queried separately and the results are merged by review id. A team that can't
 * be queried is skipped; the user's own query must succeed.
 */
suspend fun ReviewProvider.listAssignedToMe(repo: RepoRef, filter: ReviewFilter, me: ReviewUser): AssignedReviews {
    var complete = true
    suspend fun <T> orSkip(call: suspend () -> List<T>): List<T> = try {
        call()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        complete = false
        emptyList()
    }
    val teams = orSkip { myTeams(repo) }
    val mine = listReviews(repo, filter.copy(reviewerId = me.id))
    val viaTeams = teams.flatMap { team -> orSkip { listReviews(repo, filter.copy(reviewerId = team.id)) } }
    return AssignedReviews((mine + viaTeams).distinctBy { it.ref.id }, teams, complete)
}

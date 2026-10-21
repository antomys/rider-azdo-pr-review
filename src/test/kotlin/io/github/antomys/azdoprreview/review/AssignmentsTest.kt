package io.github.antomys.azdoprreview.review

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class AssignmentsTest {
    private val repo = RepoRef("project", "repo")
    private val me = ReviewUser("me", "Me")
    private val team = ReviewUser("team", "Team")

    private fun review(id: Int, vararg reviewers: Reviewer) = ReviewSummary(
        ReviewRef(repo, id), "PR $id", ReviewUser("a", "Author"), "f", "main", ReviewState.OPEN, false, Instant.EPOCH, reviewers = reviewers.toList(),
    )

    @Test
    fun `a direct assignment is required or optional with my vote`() {
        val required = Assignment.of(review(1, Reviewer(me, Vote.APPROVED, isRequired = true)), me, listOf(team))
        assertEquals(Assignment(AssignmentRole.REQUIRED, Vote.APPROVED, declined = false), required)
        assertEquals("Required", required?.label)
        val optional = Assignment.of(review(2, Reviewer(me, Vote.APPROVED_WITH_SUGGESTIONS, isRequired = false)), me, listOf(team))
        assertEquals("Optional", optional?.label)
    }

    @Test
    fun `a team-only assignment shows the team and its vote`() {
        val assignment = Assignment.of(review(4, Reviewer(team.copy(displayName = "[p]\\Team"), Vote.WAITING_FOR_AUTHOR, false)), me, listOf(team))
        assertEquals(Assignment(AssignmentRole.TEAM, Vote.WAITING_FOR_AUTHOR, declined = false, team = team), assignment)
        assertEquals("via team Team", assignment?.label)
    }

    @Test
    fun `a direct assignment wins over a team's`() {
        val both = review(3, Reviewer(team, Vote.NONE, true), Reviewer(me, Vote.REJECTED, false, hasDeclined = true))
        assertEquals(Assignment(AssignmentRole.OPTIONAL, Vote.REJECTED, declined = true), Assignment.of(both, me, listOf(team)))
    }

    @Test
    fun `no assignment when neither I nor my teams review`() {
        assertNull(Assignment.of(review(9, Reviewer(ReviewUser("x", "X"), Vote.NONE, true)), me, listOf(team)))
    }

    @Test
    fun `merges my and my teams' reviews by id`() = runBlocking {
        val provider = FakeReviewProvider(
            me = me,
            teams = listOf(team),
            byReviewer = mapOf("me" to listOf(review(2), review(3)), "team" to listOf(review(3), review(4))),
        )
        val assigned = provider.listAssignedToMe(repo, ReviewFilter(), me)

        assertEquals(listOf(2, 3, 4), assigned.reviews.map { it.ref.id })
        assertTrue(assigned.complete)
        assertEquals(listOf("me", "team"), provider.filters.map { it.reviewerId })
    }

    @Test
    fun `a team that can't be queried is skipped`() = runBlocking {
        val other = ReviewUser("other-team", "Other")
        val provider = FakeReviewProvider(
            me = me,
            teams = listOf(team, other),
            byReviewer = mapOf("me" to listOf(review(2)), "other-team" to listOf(review(6))),
            failingReviewers = setOf("team"),
        )
        val assigned = provider.listAssignedToMe(repo, ReviewFilter(), me)

        assertEquals(listOf(2, 6), assigned.reviews.map { it.ref.id })
        assertFalse(assigned.complete)
    }
}

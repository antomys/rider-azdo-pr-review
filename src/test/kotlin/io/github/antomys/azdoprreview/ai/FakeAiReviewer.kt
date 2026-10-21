package io.github.antomys.azdoprreview.ai

/** Returns canned findings (or throws [failure]) and records the requests it got; used by every view-model test. */
class FakeAiReviewer(
    private val findings: List<AiFinding> = emptyList(),
    private val failure: AiReviewException? = null,
    private val skipped: Int = 0,
) : AiReviewer {
    val requests = mutableListOf<AiReviewRequest>()

    override val id = "fake"
    override val name = "Fake reviewer"

    override fun availability() = Availability.Available("1.0", "fake")

    override fun commandLine() = "fake-reviewer"

    override suspend fun review(request: AiReviewRequest): AiReviewResult {
        requests += request
        failure?.let { throw it }
        return AiReviewResult(findings, skipped)
    }
}

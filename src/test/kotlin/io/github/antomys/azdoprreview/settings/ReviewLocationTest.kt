package io.github.antomys.azdoprreview.settings

import io.github.antomys.azdoprreview.azdo.AzureDevOpsRemote
import io.github.antomys.azdoprreview.review.RepoRef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReviewLocationTest {
    private val remote = AzureDevOpsRemote("fabrikam", "Fiber Project", "web-app")

    @Test
    fun `the Git remote wins over the settings`() {
        assertEquals(
            ReviewLocation("fabrikam", RepoRef("Fiber Project", "web-app"), fromRemote = true),
            ReviewLocation.of(remote, "contoso", "Other", "other-repo"),
        )
    }

    @Test
    fun `without a remote the settings are used`() {
        assertEquals(
            ReviewLocation("contoso", RepoRef("Other", "other-repo"), fromRemote = false),
            ReviewLocation.of(null, "contoso", "Other", "other-repo"),
        )
    }

    @Test
    fun `without a remote or a saved repository the repository is unknown`() {
        assertEquals(ReviewLocation("contoso", null, fromRemote = false), ReviewLocation.of(null, "contoso", "Other", ""))
        assertEquals(ReviewLocation("", null, fromRemote = false), ReviewLocation.of(null, "", "", ""))
    }
}

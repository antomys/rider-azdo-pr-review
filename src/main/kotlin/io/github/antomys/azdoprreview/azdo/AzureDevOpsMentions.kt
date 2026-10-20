package io.github.antomys.azdoprreview.azdo

import io.github.antomys.azdoprreview.review.ReviewUser

/**
 * Azure DevOps stores mentions in comment Markdown as `@<identity-id>` and returns them unchanged,
 * without a display-name map, so the client resolves ids itself.
 */
object AzureDevOpsMentions {
    private val MENTION = Regex("@<([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})>")

    fun encode(userId: String) = "@<$userId>"

    fun ids(text: String): Set<String> = MENTION.findAll(text).map { it.groupValues[1].lowercase() }.toSet()

    /** Replaces known mentions with `@Display Name`; unknown ids stay as they are. */
    fun render(text: String, users: Map<String, ReviewUser>): String =
        MENTION.replace(text) { m -> users[m.groupValues[1].lowercase()]?.let { "@${it.displayName}" } ?: m.value }
}

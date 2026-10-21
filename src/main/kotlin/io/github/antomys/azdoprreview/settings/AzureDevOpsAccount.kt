package io.github.antomys.azdoprreview.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/** The Azure DevOps organization (IDE-wide). Its PAT lives in the IDE password storage, never in settings files. */
@Service(Service.Level.APP)
@State(name = "AzureDevOpsPrReviewAccount", storages = [Storage("azure-devops-pr-review.xml")])
class AzureDevOpsAccount : SimplePersistentStateComponent<AzureDevOpsAccount.AccountState>(AccountState()) {
    class AccountState : BaseState() {
        var organization by string()
    }

    var organization: String
        get() = state.organization.orEmpty()
        set(value) {
            state.organization = value.trim().ifEmpty { null }
        }

    /** [organization]'s token, kept per organization. Reads the password storage, which can be slow: call off the EDT. */
    fun pat(organization: String = this.organization): String? =
        organization.ifEmpty { null }?.let { PasswordSafe.instance.getPassword(credentials(it)) }

    /** Writes the password storage, which can be slow: call off the EDT. */
    fun setPat(pat: String) = setPat(organization, pat)

    /** Saves [pat] as [organization]'s token. Writes the password storage, which can be slow: call off the EDT. */
    fun setPat(organization: String, pat: String) {
        val org = organization.ifEmpty { return }
        PasswordSafe.instance.setPassword(credentials(org), pat)
    }

    companion object {
        fun getInstance(): AzureDevOpsAccount = service()

        private fun credentials(organization: String) =
            CredentialAttributes(generateServiceName("Azure DevOps PR Review", organization))
    }
}

package io.github.antomys.azdoprreview.toolwindow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.ui.Messages
import io.github.antomys.azdoprreview.review.ReviewMarks.MarkResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.swing.JComponent

/** "Mark as Reviewed" from the list or a pull request's tab. Marks are local: nothing is sent to Azure DevOps. */
object MarkReviewed {
    /**
     * Marks at once, or, when the pull request has an iteration newer than the one the user last opened, first asks
     * whether to mark anyway or [reviewUpdate].
     */
    fun run(scope: CoroutineScope, parent: JComponent, mark: suspend (force: Boolean) -> MarkResult, reviewUpdate: () -> Unit) {
        scope.launch(Dispatchers.EDT) {
            val result = attempt(parent) { mark(false) } ?: return@launch
            if (result !is MarkResult.UpdatedSinceViewed) return@launch
            // The dialog is modal: show it outside the coroutine.
            ApplicationManager.getApplication().invokeLater {
                val choice = MessageDialogBuilder.yesNoCancel(
                    "Mark as Reviewed", "This PR was updated since you opened it (iteration ${result.viewed} → ${result.latest}).",
                ).yesText("Mark Anyway").noText("Review Update").show(parent)
                when (choice) {
                    Messages.YES -> scope.launch(Dispatchers.EDT) { attempt(parent) { mark(true) } }
                    Messages.NO -> reviewUpdate()
                }
            }
        }
    }

    private suspend fun <T> attempt(parent: JComponent, call: suspend () -> T): T? = try {
        call()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val message = e.message ?: e.javaClass.simpleName
        ApplicationManager.getApplication().invokeLater { Messages.showErrorDialog(parent, message, "Mark as Reviewed") }
        null
    }
}

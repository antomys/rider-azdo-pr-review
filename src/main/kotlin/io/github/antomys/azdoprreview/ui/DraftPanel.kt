package io.github.antomys.azdoprreview.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.antomys.azdoprreview.toolwindow.DraftComment
import io.github.antomys.azdoprreview.toolwindow.PullRequestDetailsViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Icon
import java.awt.FlowLayout
import javax.swing.JPanel
import javax.swing.SwingConstants

/** A comment not sent yet: "Name  Post now  PENDING" with edit and delete buttons, then the text. */
class DraftPanel(
    private val project: Project,
    private val draft: DraftComment,
    private val vm: PullRequestDetailsViewModel,
    private val scope: CoroutineScope,
) : JPanel(BorderLayout(JBUI.scale(10), 0)) {
    private val avatars = Avatars.of(vm.target.provider)

    init {
        isOpaque = false
        showDraft()
    }

    private fun showDraft() {
        removeAll()
        val me = vm.me()
        me?.let { add(JBLabel(avatars.icon(it, AVATAR_SIZE, this)).apply { verticalAlignment = SwingConstants.TOP }, BorderLayout.WEST) }
        val error = JBLabel().apply { foreground = UIUtil.getErrorForeground() }
        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
                isOpaque = false
                add(JBLabel(me?.displayName ?: "You").apply { font = JBFont.label().asBold() })
                add(ActionLink("Post now") { post(error) }.apply { toolTipText = "Send this comment now, without the rest of the review" })
                add(Badge("PENDING"))
                add(error)
            }, BorderLayout.WEST)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0)).apply {
                isOpaque = false
                add(iconButton("Edit", AllIcons.Actions.Edit) { showEditor() })
                add(iconButton("Delete", AllIcons.Actions.GC) { vm.deleteDraft(draft) })
            }, BorderLayout.EAST)
        }
        add(JPanel(BorderLayout(0, JBUI.scale(4))).apply {
            isOpaque = false
            add(header, BorderLayout.NORTH)
            add(MarkdownPane(draft.body), BorderLayout.CENTER)
        }, BorderLayout.CENTER)
        relayout()
    }

    private fun showEditor() {
        removeAll()
        val composer = CommentComposer(
            project, scope, vm.me(), avatars, "Edit the comment", Mentions(vm::searchUsers, vm::encodeMention),
            primary = CommentAction("Save", "save") { vm.editDraft(draft, it.text, it.mentioned) },
            cancel = ::showDraft,
        )
        composer.setText(draft.body, draft.mentioned)
        add(composer, BorderLayout.CENTER)
        relayout()
        composer.focus()
    }

    private fun post(error: JBLabel) {
        scope.launch(Dispatchers.EDT) {
            try {
                vm.postDraft(draft)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error.text = e.message ?: e.javaClass.simpleName
            }
        }
    }

    /** A clickable icon. InplaceButton needs hover events, which components embedded in an editor don't always get. */
    private fun iconButton(name: String, icon: Icon, action: () -> Unit) = JBLabel(icon).apply {
        toolTipText = name
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = action()
        })
    }

    private fun relayout() {
        revalidate()
        repaint()
        parent?.revalidate()
    }

    private companion object {
        const val AVATAR_SIZE = 24
    }
}

package io.github.antomys.azdoprreview.ui

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.PrefixMatcher
import com.intellij.codeInsight.lookup.CharFilter
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonShortcuts
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.actionSystem.ShortcutSet
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.util.ProgressIndicatorUtils
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.util.textCompletion.TextCompletionProvider
import com.intellij.util.textCompletion.TextFieldWithCompletion
import com.intellij.util.ui.JBUI
import io.github.antomys.azdoprreview.review.ReviewUser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.future.future
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.KeyEvent
import java.util.concurrent.ConcurrentHashMap
import javax.swing.JPanel
import javax.swing.KeyStroke

/** Where a comment input finds people to mention, and how a mention is written in the stored text. */
class Mentions(val search: suspend (String) -> List<ReviewUser>, val encode: (ReviewUser) -> String)

/**
 * A multi-line comment box. Typing `@` offers people to mention; a chosen person shows as `@Name` and is stored in the
 * provider's mention format. ⌘/Ctrl+Enter calls [onSubmit] and Escape [onCancel], unless the completion popup is open.
 */
class CommentField(
    project: Project,
    private val scope: CoroutineScope,
    placeholder: String,
    private val mentions: Mentions,
    /** Lines shown while the field is empty; it grows with the text up to twelve. */
    private val minLines: Int = 3,
) : JPanel(BorderLayout()) {
    var onSubmit: (() -> Unit)? = null
    var onCancel: (() -> Unit)? = null

    /** People chosen from the popup, by the name inserted for them. */
    private val chosen = ConcurrentHashMap<String, ReviewUser>()

    private val input = TextFieldWithCompletion(project, MentionCompletion(), "", false, true, false, false)

    init {
        isOpaque = false
        input.setPlaceholder(placeholder)
        input.setShowPlaceholderWhenFocused(true)
        input.addSettingsProvider { editor ->
            editor.settings.isUseSoftWraps = true
            editor.setVerticalScrollbarVisible(true)
            editor.contentComponent.border = JBUI.Borders.empty(4, 6)
        }
        input.border = JBUI.Borders.customLine(JBColor.border(), 1)
        input.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                if (event.newFragment.toString() == "@") input.editor?.let { AutoPopupController.getInstance(project).scheduleAutoPopup(it) }
            }
        })
        shortcut(CommonShortcuts.getCtrlEnter()) { onSubmit }
        shortcut(CommonShortcuts.ESCAPE) { onCancel }
        // The completion popup opens without full focus, where Enter would start a new line; make Enter pick the person.
        object : DumbAwareAction() {
            override fun actionPerformed(e: AnActionEvent) {
                val actions = ActionManager.getInstance()
                actions.getAction(IdeActions.ACTION_CHOOSE_LOOKUP_ITEM_REPLACE)?.let { actions.tryToExecute(it, e.inputEvent, input, e.place, true) }
            }

            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = input.editor?.let { LookupManager.getActiveLookup(it) } != null
            }

            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }.registerCustomShortcutSet(CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0)), input)
        add(input)
    }

    /** The text with chosen mentions in the provider's format. */
    val text: String
        get() = encode(displayText, chosen, mentions.encode)

    /** The text as typed, mentions as `@Name`. */
    val displayText: String
        get() = input.text.trim()

    /** The people chosen from the popup, by the name shown for them. */
    val mentioned: Map<String, ReviewUser>
        get() = chosen.toMap()

    fun setText(text: String, mentioned: Map<String, ReviewUser> = emptyMap()) {
        input.text = text
        chosen.clear()
        chosen.putAll(mentioned)
    }

    fun clear() = setText("")

    fun focus() {
        input.requestFocusInWindow()
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        input.isEnabled = enabled
    }

    override fun getPreferredSize(): Dimension {
        val preferred = super.getPreferredSize()
        val line = input.getFontMetrics(input.font).height
        return Dimension(preferred.width, preferred.height.coerceIn(line * minLines + JBUI.scale(12), line * 12))
    }

    private fun shortcut(shortcuts: ShortcutSet, handler: () -> (() -> Unit)?) {
        object : DumbAwareAction() {
            override fun actionPerformed(e: AnActionEvent) {
                handler()?.invoke()
            }

            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = handler() != null && input.editor?.let { LookupManager.getActiveLookup(it) } == null
            }

            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }.registerCustomShortcutSet(shortcuts, input)
    }

    private inner class MentionCompletion : TextCompletionProvider {
        override fun getAdvertisement(): String? = null

        /** The word typed after an `@` that starts the text or follows whitespace; null when the caret isn't in a mention. */
        override fun getPrefix(text: String, offset: Int): String? {
            val at = text.lastIndexOf('@', offset - 1)
            if (at < 0 || (at > 0 && !text[at - 1].isWhitespace())) return null
            return text.substring(at + 1, offset).takeIf { prefix -> prefix.all(::isNameChar) }
        }

        override fun applyPrefixMatcher(result: CompletionResultSet, prefix: String): CompletionResultSet =
            result.withPrefixMatcher(UserMatcher(prefix))

        override fun acceptChar(c: Char): CharFilter.Result =
            if (isNameChar(c)) CharFilter.Result.ADD_TO_PREFIX else CharFilter.Result.HIDE_LOOKUP

        override fun fillCompletionVariants(parameters: CompletionParameters, prefix: String, result: CompletionResultSet) {
            result.restartCompletionOnAnyPrefixChange()
            val search = scope.future { mentions.search(prefix) }
            val users = try {
                ProgressIndicatorUtils.awaitWithCheckCanceled(search)
            } catch (e: ProcessCanceledException) {
                search.cancel(true)
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Mentions are a convenience: without search results the text is still sent as typed.
                emptyList()
            }
            users.forEach { user ->
                val element = LookupElementBuilder.create(user, user.displayName)
                    .withIcon(AllIcons.General.User)
                    .withTypeText(user.uniqueName, true)
                    .withInsertHandler { context, _ ->
                        chosen[user.displayName] = user
                        context.document.insertString(context.tailOffset, " ")
                        context.editor.caretModel.moveToOffset(context.tailOffset + 1)
                    }
                result.addElement(element)
            }
        }

        private fun isNameChar(c: Char) = c.isLetterOrDigit() || c == '.' || c == '_' || c == '-'
    }
}

/** [text] with each `@Name` of [mentioned] written as the provider's mention. */
fun encode(text: String, mentioned: Map<String, ReviewUser>, encode: (ReviewUser) -> String): String =
    mentioned.entries.sortedByDescending { it.key.length }.fold(text) { result, (name, user) -> result.replace("@$name", encode(user)) }

/** Matches people the way [ReviewUser.matches] does: the platform's matchers only match a later word for a single letter. */
private class UserMatcher(prefix: String) : PrefixMatcher(prefix) {
    override fun prefixMatches(name: String) = ReviewUser("", name).matches(prefix)

    override fun prefixMatches(element: LookupElement) =
        (element.`object` as? ReviewUser)?.matches(prefix) ?: super.prefixMatches(element)

    override fun cloneWithPrefix(prefix: String): PrefixMatcher = UserMatcher(prefix)
}

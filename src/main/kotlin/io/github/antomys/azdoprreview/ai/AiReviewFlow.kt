package io.github.antomys.azdoprreview.ai

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.text.StringUtil
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.ui.JBColor
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.antomys.azdoprreview.review.Side
import io.github.antomys.azdoprreview.settings.AiReviewSettings
import io.github.antomys.azdoprreview.toolwindow.PullRequestDetailsViewModel
import io.github.antomys.azdoprreview.toolwindow.path
import io.github.antomys.azdoprreview.ui.MarkdownPane
import io.github.antomys.azdoprreview.ui.plainText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Rectangle
import java.awt.event.ActionEvent
import javax.swing.AbstractButton
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.Scrollable
import javax.swing.SwingConstants

/**
 * "Review with AI…": consent (every run), the review in the background with Cancel, then a preview where the user
 * picks the findings to add as drafts or post. Dialogs run on the EDT; fetching, the CLI and parsing run off it.
 */
object AiReviewFlow {
    private const val TITLE = "Review with AI"

    fun start(project: Project, pr: PullRequestDetailsViewModel, scope: CoroutineScope) {
        val settings = AiReviewSettings.getInstance()
        val session = AiReviewSession(pr, ClaudeCodeReviewer(settings.cliPath), settings.options())
        val reviewer = session.reviewer
        val id = pr.summary.ref.id
        scope.launch(Dispatchers.EDT) {
            try {
                val (availability, prepared) = withBackgroundProgress(project, "Preparing the AI review of #$id", cancellable = true) {
                    val availability = withContext(Dispatchers.IO) { reviewer.availability() }
                    availability to (if (availability is Availability.Available) session.prepare() else null)
                }
                if (availability !is Availability.Available || prepared == null) {
                    val reason = (availability as? Availability.Unavailable)?.reason.orEmpty()
                    Messages.showErrorDialog(project, "$reason.\n\nSet the Claude Code CLI path in Settings | Tools | Azure DevOps PR Review.", TITLE)
                    return@launch
                }
                val consent = ConsentDialog(project, reviewer, availability, prepared)
                if (!consent.showAndGet()) return@launch
                val result = withBackgroundProgress(project, "Reviewing #$id with ${reviewer.name}", cancellable = true) {
                    session.review(prepared, consent.leftOut())
                }
                if (result.findings.isEmpty()) {
                    Messages.showInfoMessage(project, listOfNotNull("${reviewer.name} found nothing to report.", droppedText(result)).joinToString("\n"), TITLE)
                    return@launch
                }
                val preview = PreviewDialog(project, reviewer.name, result)
                preview.show()
                when (preview.exitCode) {
                    DialogWrapper.OK_EXIT_CODE -> session.addDrafts(preview.selected())
                    PreviewDialog.POST_NOW_EXIT_CODE -> withBackgroundProgress(project, "Posting AI review findings to #$id", cancellable = true) {
                        session.postNow(preview.selected())
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiReviewException) {
                Messages.showErrorDialog(project, listOfNotNull(e.message, e.details?.takeIf { it.isNotBlank() }).joinToString("\n\n"), TITLE)
            } catch (e: Exception) {
                Messages.showErrorDialog(project, e.message ?: e.javaClass.simpleName, TITLE)
            }
        }
    }

    /** E.g. "Dropped 3: 2 not on a file or line of the diff, 1 over the limit of 20.", or null when none were dropped. */
    fun droppedText(result: ValidatedFindings, maxFindings: Int = AiReviewSettings.getInstance().maxFindings): String? {
        if (result.dropped == 0) return null
        val reasons = listOfNotNull(
            "${result.outsideDiff} not on a file or line of the diff".takeIf { result.outsideDiff > 0 },
            "${result.overLimit} over the limit of $maxFindings".takeIf { result.overLimit > 0 },
        )
        return "Dropped ${result.dropped}: ${reasons.joinToString(", ")}."
    }
}

/** What will be sent, where, and under whose account; the user can leave files out. */
private class ConsentDialog(
    project: Project,
    private val reviewer: AiReviewer,
    private val availability: Availability.Available,
    private val prepared: AiReviewSession.Prepared,
) : DialogWrapper(project) {
    private val boxes = prepared.request.files.associateWith { file ->
        JBCheckBox().apply {
            putClientProperty("html.disable", true)
            text = AiReviewRequests.displayPath(file.change.path)
            isSelected = true
        }
    }
    private val total = JBLabel()

    init {
        title = "Review with AI"
        setOKButtonText("Send")
        boxes.values.forEach { box -> box.addActionListener { updateTotal() } }
        init()
        updateTotal()
    }

    /** Paths of the files the user unticked. */
    fun leftOut(): Set<String> = boxes.filterValues { !it.isSelected }.keys.map { it.change.path }.toSet()

    private fun updateTotal() {
        val sent = boxes.filterValues { it.isSelected }.keys
        total.text = "Total: ${StringUtil.formatFileSize(sent.sumOf { it.bytes }.toLong())} in ${sent.size} of ${boxes.size} files"
        isOKActionEnabled = sent.isNotEmpty()
    }

    override fun createCenterPanel(): JComponent {
        val content = Column(8)
        content.add(JBLabel("Send these diffs to ${reviewer.name}?").apply { font = JBFont.label().asBold() })
        content.add(MarkdownPane(
            "The diffs below, with the pull request's title and description, are sent to **Anthropic** by " +
                "${reviewer.name}, under your own Claude account. Nothing is posted until you choose findings in the preview.",
        ))
        content.add(JBLabel("Tool: ${availability.version}, ${availability.location}").plainText())
        content.add(JBTextArea(reviewer.commandLine()).apply {
            isEditable = false
            lineWrap = true
            font = JBFont.small()
            foreground = UIUtil.getContextHelpForeground()
            isOpaque = false
        })
        content.add(JBLabel("Files to send").apply { font = JBFont.label().asBold() })
        if (boxes.isEmpty()) content.add(JBLabel("None: every changed file is excluded").apply { foreground = UIUtil.getErrorForeground() })
        boxes.forEach { (file, box) ->
            content.add(JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
                add(box, BorderLayout.CENTER)
                add(JBLabel(StringUtil.formatFileSize(file.bytes.toLong())).apply { foreground = UIUtil.getContextHelpForeground() }, BorderLayout.EAST)
            })
        }
        content.add(total)
        if (prepared.excluded.isNotEmpty()) {
            content.add(JBLabel("Not sent").apply { font = JBFont.label().asBold() })
            prepared.excluded.forEach { excluded ->
                content.add(JBLabel("${AiReviewRequests.displayPath(excluded.path)}: ${excluded.reason}").plainText().apply {
                    foreground = UIUtil.getContextHelpForeground()
                })
            }
        }
        return scrolling(content)
    }
}

/** The findings, each with a checkbox and an editable title and body; "Add as Drafts" (default) or "Post Now". */
private class PreviewDialog(project: Project, reviewerName: String, private val result: ValidatedFindings) : DialogWrapper(project) {
    private val rows = result.findings.map { FindingRow(it) }
    private val postNow = object : DialogWrapperAction("Post Now") {
        override fun doAction(e: ActionEvent?) = close(POST_NOW_EXIT_CODE)
    }
    private val header = "${result.findings.size} finding${if (result.findings.size == 1) "" else "s"} from $reviewerName. " +
        "Info findings start unticked."

    init {
        title = "AI Review Findings"
        setOKButtonText("Add as Drafts")
        rows.forEach { row -> row.checkBox.addActionListener { updateActions() } }
        init()
        updateActions()
    }

    /** The ticked findings with the user's edits. */
    fun selected(): List<ReviewFinding> = rows.filter { it.checkBox.isSelected }.map { it.edited() }

    private fun updateActions() {
        val any = rows.any { it.checkBox.isSelected }
        isOKActionEnabled = any
        postNow.isEnabled = any
    }

    override fun createActions(): Array<Action> = arrayOf(okAction, postNow, cancelAction)

    override fun createCenterPanel(): JComponent {
        val content = Column(12)
        content.add(JBLabel(header))
        AiReviewFlow.droppedText(result)?.let { content.add(JBLabel(it).apply { foreground = UIUtil.getContextHelpForeground() }) }
        rows.forEach { content.add(it) }
        return scrolling(content)
    }

    companion object {
        const val POST_NOW_EXIT_CODE = NEXT_USER_EXIT_CODE
    }
}

/** One finding: AI text is shown only through the sanitised Markdown pane or as plain text. */
private class FindingRow(private val finding: ReviewFinding) : JPanel(VerticalLayout(JBUI.scale(4))) {
    val checkBox = JBCheckBox().apply { isSelected = finding.severity != Severity.INFO }
    private val title = JBTextField(finding.title)
    private val body = JBTextArea(finding.body, 5, 60).apply { lineWrap = true; wrapStyleWord = true }
    private val bodyHolder = JPanel(BorderLayout())
    private var editing = false

    init {
        border = JBUI.Borders.compound(JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0), JBUI.Borders.emptyTop(8))
        val lines = finding.anchor.lines
        val location = finding.anchor.path.substringAfterLast('/') + ":" + lines?.let { if (it.start == it.end) "${it.start}" else "${it.start}–${it.end}" } +
            if (lines?.side == Side.LEFT) " (base)" else ""
        add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            add(checkBox)
            add(JBLabel(severityText(finding.severity), severityIcon(finding.severity), JBLabel.LEADING))
            add(JBLabel(location).plainText().apply { toolTipText = finding.anchor.path; foreground = UIUtil.getContextHelpForeground() })
            add(ActionLink("Edit") { toggleEditing(it.source as AbstractButton) })
        })
        add(title)
        add(bodyHolder)
        showBody()
    }

    fun edited() = finding.copy(title = title.text.trim(), body = body.text.trim())

    private fun toggleEditing(link: AbstractButton) {
        editing = !editing
        link.text = if (editing) "Preview" else "Edit"
        showBody()
    }

    private fun showBody() {
        bodyHolder.removeAll()
        bodyHolder.add(if (editing) ScrollPaneFactory.createScrollPane(body) else MarkdownPane(body.text), BorderLayout.CENTER)
        bodyHolder.revalidate()
        bodyHolder.repaint()
    }

    private fun severityText(severity: Severity) = when (severity) {
        Severity.ERROR -> "Error"
        Severity.WARNING -> "Warning"
        Severity.INFO -> "Info"
    }

    private fun severityIcon(severity: Severity) = when (severity) {
        Severity.ERROR -> AllIcons.General.Error
        Severity.WARNING -> AllIcons.General.Warning
        Severity.INFO -> AllIcons.General.Information
    }
}

/** A vertical stack that follows the viewport width, so Markdown wraps instead of scrolling sideways. */
private class Column(gap: Int) : JPanel(VerticalLayout(JBUI.scale(gap))), Scrollable {
    init {
        border = JBUI.Borders.empty(4)
    }

    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
    override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = JBUI.scale(16)
    override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) =
        if (orientation == SwingConstants.VERTICAL) visibleRect.height else visibleRect.width
    override fun getScrollableTracksViewportWidth() = true
    override fun getScrollableTracksViewportHeight() = false
}

private fun scrolling(content: JComponent): JComponent {
    return ScrollPaneFactory.createScrollPane(content, true).apply {
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        preferredSize = Dimension(JBUI.scale(640), JBUI.scale(480))
    }
}

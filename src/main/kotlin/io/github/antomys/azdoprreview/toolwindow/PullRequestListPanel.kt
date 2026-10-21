package io.github.antomys.azdoprreview.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.CollectionListModel
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.JBColor
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.text.DateFormatUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.antomys.azdoprreview.review.ReviewMark
import io.github.antomys.azdoprreview.review.ReviewState
import io.github.antomys.azdoprreview.review.ReviewStatus
import io.github.antomys.azdoprreview.review.ReviewSummary
import io.github.antomys.azdoprreview.review.ReviewUser
import io.github.antomys.azdoprreview.toolwindow.PullRequestListViewModel.State
import io.github.antomys.azdoprreview.ui.Avatars
import io.github.antomys.azdoprreview.ui.Badge
import io.github.antomys.azdoprreview.ui.VoteIcons
import io.github.antomys.azdoprreview.ui.plainText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/** Search, filter chips and the pull request list; double-click or Enter opens a pull request. */
class PullRequestListPanel(
    private val vm: PullRequestListViewModel,
    private val scope: CoroutineScope,
    private val openSettings: () -> Unit,
    private val openReview: (ReviewSummary) -> Unit,
) : SimpleToolWindowPanel(true, true) {
    private val model = CollectionListModel<ReviewSummary>()
    private val list = JBList(model).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = ReviewRenderer()
    }
    private val search = SearchTextField(true, "AzureDevOpsPrReview.search").apply {
        textEditor.emptyText.text = "Search by title, number, author or branch"
    }
    private val filterBar = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(10), 0)).apply { isOpaque = false }
    private val notice = JBLabel(AllIcons.General.Warning).apply {
        foreground = UIUtil.getContextHelpForeground()
        isVisible = false
    }

    init {
        search.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = searchChanged()
            override fun removeUpdate(e: DocumentEvent) = searchChanged()
            override fun changedUpdate(e: DocumentEvent) = searchChanged()
        })
        search.textEditor.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) search.addCurrentTextToHistory()
            }
        })
        toolbar = JPanel(VerticalLayout(JBUI.scale(6))).apply {
            border = JBUI.Borders.empty(6, 8, 4, 8)
            add(search)
            add(filterBar)
            add(notice)
        }
        setContent(ScrollPaneFactory.createScrollPane(list, true))

        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean = openSelected()
        }.installOn(list)
        list.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER && openSelected()) e.consume()
            }
        })

        scope.launch(Dispatchers.EDT) { vm.state.collect(::render) }
        scope.launch(Dispatchers.EDT) { vm.filters.collect { renderFilters(it) } }
        scope.launch(Dispatchers.EDT) { vm.commentCounts.collect { list.repaint() } }
        scope.launch(Dispatchers.EDT) {
            vm.markChanges.collect {
                vm.marksChanged()
                list.repaint()
            }
        }
        scope.launch(Dispatchers.EDT) {
            vm.notice.collect {
                notice.text = it.orEmpty()
                notice.isVisible = it != null
            }
        }
        // The selection popup only opens on a selected row, so a right click selects its row first (added before it).
        list.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (!SwingUtilities.isRightMouseButton(e) && !e.isPopupTrigger) return
                val index = list.locationToIndex(e.point)
                if (index >= 0 && list.getCellBounds(index, index)?.contains(e.point) == true) list.selectedIndex = index
            }
        })
        PopupHandler.installSelectionListPopup(list, DefaultActionGroup(markAction(reviewed = true), markAction(reviewed = false)), "AzureDevOpsPrList")
    }

    /** "Mark as Reviewed" or "Mark as Not Reviewed" for the selected pull request; only the one that applies is shown. */
    private fun markAction(reviewed: Boolean) = object : DumbAwareAction(if (reviewed) "Mark as Reviewed" else "Mark as Not Reviewed") {
        override fun update(e: AnActionEvent) {
            val review = list.selectedValue
            e.presentation.isEnabledAndVisible = review != null && (vm.status(review) == ReviewStatus.REVIEWED) != reviewed
        }

        override fun actionPerformed(e: AnActionEvent) {
            val review = list.selectedValue ?: return
            if (reviewed) {
                MarkReviewed.run(scope, list, { force -> vm.markReviewed(review, force) }) { openReview(review) }
            } else {
                vm.markNotReviewed(review)
            }
        }

        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    private fun searchChanged() {
        if (search.text != vm.filters.value.search) vm.setFilters(vm.filters.value.copy(search = search.text))
    }

    private fun openSelected(): Boolean {
        openReview(list.selectedValue ?: return false)
        return true
    }

    private fun render(state: State) {
        list.setPaintBusy(state is State.Loading)
        val empty = list.emptyText.clear()
        when (state) {
            State.Loading -> {
                model.removeAll()
                empty.appendText("Loading pull requests…")
            }
            State.NotConfigured -> {
                model.removeAll()
                empty.appendText("Azure DevOps isn't configured")
                    .appendSecondaryText("Open settings", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) { openSettings() }
            }
            is State.Failed -> {
                model.removeAll()
                empty.appendText(state.message, SimpleTextAttributes.ERROR_ATTRIBUTES)
                    .appendSecondaryText("Retry", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) { vm.refresh() }
            }
            is State.Loaded -> {
                model.replaceAll(state.visible)
                val hidden = vm.hiddenReviewed()
                when {
                    state.reviews.isEmpty() -> empty.appendText("No pull requests")
                    // Everything that matches is marked reviewed: offer to show those rather than to reset filters.
                    hidden > 0 && state.visible.isEmpty() -> empty.appendText("Pull requests marked reviewed are hidden")
                        .appendSecondaryText("Show reviewed", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) {
                            setFilters(vm.filters.value.copy(showReviewed = true))
                        }
                    else -> empty.appendText("No pull requests match the search")
                }
                if (!vm.filters.value.isDefault && !(hidden > 0 && state.visible.isEmpty())) {
                    empty.appendSecondaryText("Reset filters", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) { setFilters(ListFilters()) }
                }
            }
        }
        // Labels come from the loaded list.
        renderFilters(vm.filters.value)
    }

    private fun setFilters(filters: ListFilters) {
        if (search.text != filters.search) search.text = filters.search
        vm.setFilters(filters)
    }

    private fun renderFilters(filters: ListFilters) {
        filterBar.removeAll()
        filterBar.add(JBLabel(FilterIcon(!filters.isDefault)).apply {
            toolTipText = "Quick filters"
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            onClick { showQuickFilters(this) }
        })
        filterBar.add(chip("State", filters.state?.let(::stateLabel), clear = { setFilters(filters.copy(state = null)) }) { chip ->
            choose(chip, listOf(ReviewState.OPEN, ReviewState.MERGED, ReviewState.CLOSED), ::stateLabel) { setFilters(filters.copy(state = it)) }
        })
        filterBar.add(chip("Author", filters.author?.let(::personLabel), clear = { setFilters(filters.copy(author = null)) }) { chip ->
            choosePerson(chip) { setFilters(vm.filters.value.copy(author = it)) }
        })
        val reviewer = if (filters.assignedToMe) "Me and my teams" else filters.reviewer?.let(::personLabel)
        filterBar.add(chip("Reviewer", reviewer, clear = { setFilters(filters.copy(reviewer = null).notAssigned()) }) { chip ->
            choosePerson(chip) { setFilters(vm.filters.value.copy(reviewer = it).notAssigned()) }
        })
        val labels = ((vm.state.value as? State.Loaded)?.reviews.orEmpty().flatMap { it.labels } + listOfNotNull(filters.label)).distinct().sorted()
        filterBar.add(chip("Label", filters.label, clear = { setFilters(filters.copy(label = null)) }) { chip ->
            if (labels.isEmpty()) {
                JBPopupFactory.getInstance().createMessage("No labels on the loaded pull requests").showUnderneathOf(chip)
            } else {
                choose(chip, labels, { it }) { setFilters(vm.filters.value.copy(label = it)) }
            }
        })
        filterBar.revalidate()
        filterBar.repaint()
    }

    private fun ListFilters.notAssigned() = copy(assignedToMe = false, showDeclined = false)

    /** Presets, then the toggles for reviewed and (in "Assigned to me") declined pull requests. */
    private fun showQuickFilters(anchor: JComponent) {
        val me = vm.me
        val current = vm.filters.value
        val presets = listOfNotNull(
            "Open" to ListFilters(),
            me?.let { "Created by me" to ListFilters(author = it) },
            me?.let { "Waiting for my review" to ListFilters(reviewer = it) },
            me?.let { "Assigned to me" to ListFilters(assignedToMe = true) },
            "All states" to ListFilters(state = null),
        )
        val group = DefaultActionGroup()
        presets.forEach { (name, preset) ->
            group.add(object : DumbAwareAction(name) {
                override fun actionPerformed(e: AnActionEvent) = setFilters(
                    preset.copy(
                        search = vm.filters.value.search,
                        showReviewed = current.showReviewed,
                        showDeclined = current.showDeclined && preset.assignedToMe,
                    ),
                )

                override fun getActionUpdateThread() = ActionUpdateThread.BGT
            })
        }
        group.addSeparator()
        group.add(toggle("Show reviewed", current.showReviewed) { setFilters(vm.filters.value.copy(showReviewed = it)) })
        if (current.assignedToMe) group.add(toggle("Show declined", current.showDeclined) { setFilters(vm.filters.value.copy(showDeclined = it)) })
        JBPopupFactory.getInstance()
            .createActionGroupPopup(null, group, DataContext.EMPTY_CONTEXT, JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, true)
            .showUnderneathOf(anchor)
    }

    private fun toggle(name: String, selected: Boolean, set: (Boolean) -> Unit) = object : ToggleAction(name), DumbAware {
        override fun isSelected(e: AnActionEvent) = selected
        override fun setSelected(e: AnActionEvent, state: Boolean) = set(state)
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    private fun choosePerson(anchor: JComponent, chosen: (ReviewUser) -> Unit) {
        scope.launch(Dispatchers.EDT) {
            val people = vm.people("")
            choose(anchor, people, ::personLabel, chosen)
        }
    }

    private fun personLabel(user: ReviewUser) = if (user.id == vm.me?.id) "Me (${user.displayName})" else user.displayName

    private fun <T : Any> choose(anchor: JComponent, items: List<T>, text: (T) -> String, chosen: (T) -> Unit) {
        JBPopupFactory.getInstance().createPopupChooserBuilder(items)
            .setRenderer(com.intellij.ui.dsl.listCellRenderer.textListCellRenderer { it?.let(text) })
            .setNamerForFiltering(text)
            .setItemChosenCallback(chosen)
            .createPopup()
            .showUnderneathOf(anchor)
    }

    /** "Author ▾" when unset; "Value ×" when set, where × clears it. Clicking the text opens the choices. */
    private fun chip(name: String, value: String?, clear: () -> Unit, open: (JComponent) -> Unit): JComponent {
        val panel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(2), 0)).apply { isOpaque = false }
        val text = JBLabel(value ?: name).plainText().apply {
            foreground = if (value == null) UIUtil.getContextHelpForeground() else UIUtil.getLabelForeground()
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = if (value == null) "Filter by ${name.lowercase()}" else "$name: $value"
        }
        val icon = JBLabel(if (value == null) AllIcons.General.ChevronDown else AllIcons.Actions.Close).apply {
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = if (value == null) null else "Clear the ${name.lowercase()} filter"
        }
        text.onClick { open(panel) }
        icon.onClick { if (value == null) open(panel) else clear() }
        panel.add(text)
        panel.add(icon)
        return panel
    }

    private fun JComponent.onClick(action: () -> Unit) = addMouseListener(object : MouseAdapter() {
        override fun mouseClicked(e: MouseEvent) = action()
    })

    /** The filter funnel, with a green dot while filters other than the default are on. */
    private class FilterIcon(private val active: Boolean) : Icon {
        override fun getIconWidth() = AllIcons.General.Filter.iconWidth
        override fun getIconHeight() = AllIcons.General.Filter.iconHeight

        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            AllIcons.General.Filter.paintIcon(c, g, x, y)
            if (!active) return
            val d = JBUI.scale(6)
            g.color = JBColor(0x3FA86A, 0x5FB865)
            g.fillOval(x + iconWidth - d, y, d, d)
        }
    }

    /** Title, then "#id · created <date>, by <author>"; on the right a draft badge, labels, reviewers and the comment count. */
    private inner class ReviewRenderer : ListCellRenderer<ReviewSummary> {
        private val title = JBLabel().plainText()
        private val details = JBLabel()
        private val right = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0)).apply { isOpaque = false }
        private val panel = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            border = JBUI.Borders.empty(5, 8)
            // No preferred width of its own: long text is cut with "…" instead of widening the row past the list.
            add(object : JPanel(VerticalLayout(JBUI.scale(2))) {
                override fun getPreferredSize() = super.getPreferredSize().apply { width = 0 }
            }.apply {
                isOpaque = false
                add(title)
                add(details)
            }, BorderLayout.CENTER)
            add(right, BorderLayout.EAST)
        }

        override fun getListCellRendererComponent(
            list: JList<out ReviewSummary>, value: ReviewSummary, index: Int, selected: Boolean, hasFocus: Boolean,
        ): Component {
            panel.background = UIUtil.getListBackground(selected, list.hasFocus())
            val foreground = UIUtil.getListForeground(selected, list.hasFocus())
            title.text = value.title
            title.foreground = if (value.isDraft) UIUtil.getContextHelpForeground() else foreground
            val created = DateFormatUtil.formatPrettyDate(value.created.toEpochMilli())
            val status = vm.status(value)
            details.text = "#${value.ref.id} · created $created, by ${value.author.displayName}" +
                (if (value.state != ReviewState.OPEN) " · ${stateLabel(value.state)}" else "") +
                // "Assigned to me" adds the user's role, vote and review status.
                vm.assignments.value[value.ref.id]?.let { a ->
                    listOfNotNull(a.label, VoteIcons.NAMES[a.vote], "Declined".takeIf { a.declined }, "Needs review".takeIf { status == ReviewStatus.NEEDS_REVIEW })
                        .joinToString(" · ", prefix = " · ")
                }.orEmpty()
            details.foreground = UIUtil.getContextHelpForeground()

            right.removeAll()
            if (value.isDraft) right.add(Badge("DRAFT"))
            when (status) {
                ReviewStatus.REVIEWED -> right.add(Badge("Reviewed"))
                ReviewStatus.UPDATED -> vm.mark(value)?.let { right.add(Badge(updatedText(it))) }
                ReviewStatus.NEEDS_REVIEW -> {}
            }
            if (value.labels.isNotEmpty()) right.add(JBLabel(AllIcons.Nodes.Tag).apply { toolTipText = "<html>" + StringUtil.escapeXmlEntities(value.labels.joinToString()) })
            val avatars = Avatars.of(vm.target?.provider ?: return panel)
            value.reviewers.take(MAX_AVATARS).forEach { reviewer ->
                VoteIcons.of(reviewer.vote)?.let { right.add(JBLabel(it)) }
                right.add(JBLabel(avatars.icon(reviewer.user, AVATAR_SIZE, list)))
            }
            val comments = vm.commentCounts.value[value.ref.id]
            right.add(JBLabel(comments?.toString() ?: "", AllIcons.General.Balloon, JBLabel.LEADING).apply { this.foreground = foreground })
            return panel
        }
    }

    private companion object {
        const val MAX_AVATARS = 3
        const val AVATAR_SIZE = 22

        /** "Updated: iteration 1 → 2 (force-push)": the reviewed iteration, the latest one, and why when known. */
        fun updatedText(mark: ReviewMark): String {
            val reasons = mark.updateReasons.sortedBy { it.ordinal }.joinToString { it.label }
            return "Updated: iteration ${mark.updatedFrom} → ${mark.updatedTo}" + if (reasons.isEmpty()) "" else " ($reasons)"
        }

        fun stateLabel(state: ReviewState?) = when (state) {
            ReviewState.OPEN -> "Open"
            ReviewState.MERGED -> "Completed"
            ReviewState.CLOSED -> "Abandoned"
            null -> "All"
        }
    }
}

package io.github.antomys.azdoprreview.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.EDT
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.CollectionListModel
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.JBColor
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.text.DateFormatUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.antomys.azdoprreview.review.ReviewState
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
                empty.appendText(if (state.reviews.isEmpty()) "No pull requests" else "No pull requests match the search")
                if (!vm.filters.value.isDefault) {
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
        filterBar.add(chip("Reviewer", filters.reviewer?.let(::personLabel), clear = { setFilters(filters.copy(reviewer = null)) }) { chip ->
            choosePerson(chip) { setFilters(vm.filters.value.copy(reviewer = it)) }
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

    private fun showQuickFilters(anchor: JComponent) {
        val me = vm.me
        val presets = listOfNotNull(
            "Open" to ListFilters(),
            me?.let { "Created by me" to ListFilters(author = it) },
            me?.let { "Waiting for my review" to ListFilters(reviewer = it) },
            "All states" to ListFilters(state = null),
        )
        choose(anchor, presets, { it.first }) { setFilters(it.second.copy(search = vm.filters.value.search)) }
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
        val text = JBLabel(value ?: name).apply {
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
            add(JPanel(VerticalLayout(JBUI.scale(2))).apply {
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
            details.text = "#${value.ref.id} · created $created, by ${value.author.displayName}" +
                if (value.state != ReviewState.OPEN) " · ${stateLabel(value.state)}" else ""
            details.foreground = UIUtil.getContextHelpForeground()

            right.removeAll()
            if (value.isDraft) right.add(Badge("DRAFT"))
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

        fun stateLabel(state: ReviewState?) = when (state) {
            ReviewState.OPEN -> "Open"
            ReviewState.MERGED -> "Completed"
            ReviewState.CLOSED -> "Abandoned"
            null -> "All"
        }
    }
}

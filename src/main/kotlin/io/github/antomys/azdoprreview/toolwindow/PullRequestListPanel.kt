package io.github.antomys.azdoprreview.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import io.github.antomys.azdoprreview.review.ReviewState
import io.github.antomys.azdoprreview.review.ReviewSummary
import io.github.antomys.azdoprreview.toolwindow.PullRequestListViewModel.State
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.awt.FlowLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel

class PullRequestListPanel(
    private val vm: PullRequestListViewModel,
    scope: CoroutineScope,
    private val openSettings: () -> Unit,
) : SimpleToolWindowPanel(true, true) {
    private val model = CollectionListModel<ReviewSummary>()
    private val list = JBList(model).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = ReviewRenderer()
    }

    init {
        val stateFilter = ComboBox(arrayOf(ReviewState.OPEN, ReviewState.MERGED, ReviewState.CLOSED, null)).apply {
            renderer = textListCellRenderer { stateLabel(it) }
            selectedItem = vm.reviewState
            addActionListener {
                vm.reviewState = selectedItem as ReviewState?
                vm.refresh()
            }
        }
        val quickFilter = ComboBox(QuickFilter.entries.toTypedArray()).apply {
            renderer = textListCellRenderer { it?.label }
            selectedItem = vm.quickFilter
            addActionListener {
                vm.quickFilter = selectedItem as QuickFilter
                vm.refresh()
            }
        }
        val refresh = object : DumbAwareAction("Refresh", "Reload pull requests", AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) {
                vm.refresh()
            }

            override fun getActionUpdateThread() = ActionUpdateThread.BGT
        }
        val actions = ActionManager.getInstance().createActionToolbar("AzureDevOpsPrList", DefaultActionGroup(refresh), true)
        actions.targetComponent = list

        toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            add(stateFilter)
            add(quickFilter)
            add(actions.component)
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
    }

    /** Opens the selected pull request in the browser until the in-IDE details view exists. */
    private fun openSelected(): Boolean {
        val url = list.selectedValue?.let(vm::webUrl) ?: return false
        BrowserUtil.browse(url)
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
                model.replaceAll(state.reviews)
                empty.appendText("No pull requests")
            }
        }
    }

    private class ReviewRenderer : ColoredListCellRenderer<ReviewSummary>() {
        override fun customizeCellRenderer(list: JList<out ReviewSummary>, value: ReviewSummary, index: Int, selected: Boolean, hasFocus: Boolean) {
            append("#${value.ref.id} ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            append(value.title)
            if (value.isDraft) append("  Draft", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
            if (value.state != ReviewState.OPEN) append("  ${stateLabel(value.state)}", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
            append(
                "  ${value.author.displayName} · ${value.sourceBranch} → ${value.targetBranch}",
                SimpleTextAttributes.GRAYED_ATTRIBUTES,
            )
        }
    }

    private companion object {
        fun stateLabel(state: ReviewState?) = when (state) {
            ReviewState.OPEN -> "Active"
            ReviewState.MERGED -> "Completed"
            ReviewState.CLOSED -> "Abandoned"
            null -> "All"
        }
    }
}

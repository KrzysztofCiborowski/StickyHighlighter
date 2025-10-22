package net.historynoob.stickyhighlighter

import com.intellij.icons.AllIcons
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.ColorChooser
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.event.ListSelectionEvent
import javax.swing.event.ListSelectionListener
import kotlin.math.max

class StickyHighlightsPanel(private val project: Project) {
    val component: JPanel = JPanel(BorderLayout())

    private val list = JBList<Item>()
    private val splitter = OnePixelSplitter(true, 0.38f)

    private val rightPanel = JPanel(BorderLayout()).apply {
        isOpaque = true
        background = UIUtil.getEditorPaneBackground()
    }
    private val emptyLabel = JBLabel("Nothing to show", SwingConstants.CENTER)

    private var previewEditor: Editor? = null
    private var generation = 0 // prevents races (ignore stale showPreview calls)

    // UI constants for close icon hitbox
    private val closeIcon = AllIcons.Actions.Close
    private val iconPad = JBUI.scale(6)
    private val iconW = closeIcon.iconWidth
    private val iconH = closeIcon.iconHeight

    init {
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = ItemRenderer()

        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val idx = indexAtPoint(e)
                if (e.clickCount == 2 && idx >= 0) {
                    list.selectedIndex = idx
                    list.selectedValue?.let { jumpTo(it) }
                    return
                }
                if (e.button == MouseEvent.BUTTON1) {
                    if (idx >= 0 && clickOnClose(e, idx)) {
                        removeSelected(idx)
                        return
                    }
                    if (idx < 0) list.clearSelection()
                }
            }

            override fun mousePressed(e: MouseEvent) = maybeShowPopup(e)
            override fun mouseReleased(e: MouseEvent) = maybeShowPopup(e)

            private fun maybeShowPopup(e: MouseEvent) {
                if (!e.isPopupTrigger) return
                val idx = indexAtPoint(e)
                if (idx < 0) return
                list.selectedIndex = idx
                showContextMenu(e.x, e.y)
            }
        })

        list.addListSelectionListener(object : ListSelectionListener {
            override fun valueChanged(e: ListSelectionEvent) {
                if (e.valueIsAdjusting) return
                val item = list.selectedValue
                if (item == null) {
                    showEmptyRight()
                } else {
                    showPreview(item)
                }
            }
        })

        splitter.firstComponent = ScrollPaneFactory.createScrollPane(list)
        splitter.secondComponent = rightPanel
        component.add(splitter, BorderLayout.CENTER)

        // Auto-refresh sidebar when highlights change
        project.messageBus.connect(project).subscribe(
            StickyHighlighterService.TOPIC,
            object : StickyHighlighterService.Companion.Listener {
                override fun changed() = SwingUtilities.invokeLater { refresh() }
            }
        )

        refresh()
    }

    fun refresh() {
        project.service<StickyHighlighterService>().pruneInvalid()
        val fdm = FileDocumentManager.getInstance()
        val items = mutableListOf<Item>()

        project.service<StickyHighlighterService>().snapshot().forEach { (doc, highs) ->
            val vf = fdm.getFile(doc) ?: return@forEach
            highs.forEach { hl ->
                val line = doc.getLineNumber(hl.startOffset) + 1
                items += Item(vf, doc, hl.startOffset, hl.endOffset, "${vf.name}:$line")
            }
        }

        generation++ // invalidate any in-flight preview builds
        list.setListData(items.toTypedArray())

        if (items.isEmpty()) {
            list.clearSelection()
            showEmptyRight()
        } else {
            // Keep selection if possible; otherwise select first
            if (list.selectedIndex !in items.indices) list.selectedIndex = 0
        }
    }

    // ===== Context menu =====

    private fun showContextMenu(x: Int, y: Int) {
        val item = list.selectedValue ?: return
        val svc = project.service<StickyHighlighterService>()
        val menu = JPopupMenu()

        JMenuItem("Set Fill Color…").apply {
            addActionListener {
                val current = svc.findHighlight(item.doc, item.start, item.end)?.getUserData(StickyHighlighterService.KEY_FILL)
                val initial = Color(current ?: StickySettings.instance().state.fillColorArgb, true)
                chooseColor("Highlight Fill", initial)?.let { c ->
                    svc.recolorOne(item.doc, item.start, item.end, c.rgb, null)
                }
            }
            menu.add(this)
        }

        JMenuItem("Set Stripe Color…").apply {
            addActionListener {
                val current = svc.findHighlight(item.doc, item.start, item.end)?.getUserData(StickyHighlighterService.KEY_STRIPE)
                val initial = Color(current ?: StickySettings.instance().state.stripeColorArgb, true)
                chooseColor("Highlight Stripe", initial)?.let { c ->
                    svc.recolorOne(item.doc, item.start, item.end, null, c.rgb)
                }
            }
            menu.add(this)
        }

        JMenuItem("Reset Colors to Defaults").apply {
            addActionListener { svc.resetColorsOne(item.doc, item.start, item.end) }
            menu.add(this)
        }

        menu.addSeparator()

        JMenuItem("Remove Highlight", AllIcons.Actions.Close).apply {
            addActionListener { svc.removeByRange(item.doc, item.start, item.end) }
            menu.add(this)
        }

        menu.show(list, x, y)
    }

    private fun chooseColor(title: String, initial: Color): Color? {
        return try {
            val picker = Class.forName("com.intellij.ui.colorpicker.ColorPicker")
            val m = picker.getMethod(
                "showDialog",
                java.awt.Component::class.java, String::class.java, Color::class.java, Boolean::class.javaPrimitiveType
            )
            m.invoke(null, component, title, initial, true) as? Color
        } catch (_: Throwable) {
            ColorChooser.chooseColor(component, title, initial, true)
        }
    }

    private fun removeSelected(index: Int) {
        val item = list.model.getElementAt(index)
        project.service<StickyHighlighterService>().removeByRange(item.doc, item.start, item.end)
    }

    // ===== Preview pane =====

    private fun jumpTo(item: Item) {
        OpenFileDescriptor(project, item.vf, item.start).navigate(true)
    }

    private fun showPreview(item: Item) {
        val myGen = ++generation   // mint a generation for this build
        disposePreviewOnly()

        val (snippet, relStart, relEnd) = buildSnippet(item.doc, item.start, item.end, 6)
        if (snippet.isEmpty()) { showEmptyRight(); return }

        val snippetDoc = EditorFactory.getInstance().createDocument(snippet)
        val editor = EditorFactory.getInstance().createViewer(snippetDoc, project)
        (editor as? EditorEx)?.let { ex ->
            ex.highlighter = EditorHighlighterFactory.getInstance().createEditorHighlighter(project, item.vf)
            ex.setCaretEnabled(false)
            ex.settings.isLineNumbersShown = true
            ex.settings.isFoldingOutlineShown = false
            ex.settings.isUseSoftWraps = true
        }

        // Effective colors (respect per-highlight overrides)
        val svc = project.service<StickyHighlighterService>()
        val hl = svc.findHighlight(item.doc, item.start, item.end)
        val (fill, stripe, attrs) = if (hl != null) svc.attrsFor(hl) else run {
            val st = StickySettings.instance().state
            val fill = Color(st.fillColorArgb, true)
            val stripe = Color(st.stripeColorArgb, true)
            val fg = EditorColorsManager.getInstance().globalScheme.defaultForeground
            Triple(fill, stripe, com.intellij.openapi.editor.markup.TextAttributes(fg, fill, stripe, com.intellij.openapi.editor.markup.EffectType.BOXED, 0))
        }

        // Clamp to snippet to avoid painting outside
        val startClamped = relStart.coerceIn(0, max(0, snippet.length - 1))
        val endClamped = relEnd.coerceIn(startClamped + 1, snippet.length)

        editor.markupModel.addRangeHighlighter(
            startClamped, endClamped,
            com.intellij.openapi.editor.markup.HighlighterLayer.SELECTION - 1,
            attrs,
            com.intellij.openapi.editor.markup.HighlighterTargetArea.EXACT_RANGE
        ).apply {
            setErrorStripeMarkColor(stripe)
            setThinErrorStripeMark(true)
            setErrorStripeTooltip(item.label)
        }

        // If data changed while we were building, abort showing this preview
        if (myGen != generation) {
            EditorFactory.getInstance().releaseEditor(editor)
            return
        }

        rightPanel.removeAll()
        rightPanel.add(editor.component, BorderLayout.CENTER)
        rightPanel.revalidate()
        rightPanel.repaint()
        previewEditor = editor
    }

    private fun showEmptyRight() {
        disposePreviewOnly()
        rightPanel.removeAll()
        rightPanel.add(emptyLabel, BorderLayout.CENTER)
        rightPanel.revalidate()
        rightPanel.repaint()
    }

    private fun disposePreviewOnly() {
        previewEditor?.let { EditorFactory.getInstance().releaseEditor(it) }
        previewEditor = null
    }

    private fun buildSnippet(doc: Document, start: Int, end: Int, ctx: Int): Triple<String, Int, Int> {
        val sLine = doc.getLineNumber(start)
        val eLine = doc.getLineNumber(end)
        val from = (sLine - ctx).coerceAtLeast(0)
        val to = (eLine + ctx).coerceAtMost(doc.lineCount - 1)
        val snippetStart = doc.getLineStartOffset(from)
        val snippetEnd = doc.getLineEndOffset(to)
        val text = doc.charsSequence.subSequence(snippetStart, snippetEnd).toString()
        if (text.isEmpty()) return Triple("", 0, 0)
        val relStart = (start - snippetStart).coerceIn(0, max(0, text.length - 1))
        val relEnd = (end - snippetStart).coerceIn(relStart + 1, text.length)
        return Triple(text, relStart, relEnd)
    }

    // ===== List helpers =====

    private fun indexAtPoint(e: MouseEvent): Int {
        val idx = list.locationToIndex(e.point)
        if (idx == -1) return -1
        val bounds = list.getCellBounds(idx, idx) ?: return -1
        return if (bounds.contains(e.point)) idx else -1
    }

    private fun clickOnClose(e: MouseEvent, index: Int): Boolean {
        val bounds = list.getCellBounds(index, index) ?: return false
        val iconRight = bounds.x + bounds.width - iconPad
        val iconLeft = iconRight - iconW
        val iconTop = bounds.y + (bounds.height - iconH) / 2
        val iconBottom = iconTop + iconH
        return e.x in iconLeft..iconRight && e.y in iconTop..iconBottom
    }

    // ===== Model + renderer =====

    private data class Item(
        val vf: VirtualFile,
        val doc: Document,
        val start: Int,
        val end: Int,
        val label: String
    ) {
        override fun toString() = label
    }

    private inner class ItemRenderer : JPanel(BorderLayout()), ListCellRenderer<Item> {
        private val text = JLabel()
        private val close = JLabel(closeIcon)

        init {
            border = JBUI.Borders.empty(4, 8)
            add(text, BorderLayout.CENTER)
            add(close, BorderLayout.EAST)
        }

        override fun getListCellRendererComponent(
            list: JList<out Item>?,
            value: Item?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            text.text = value?.label ?: ""
            background = if (isSelected) UIManager.getColor("List.selectionBackground") else UIManager.getColor("List.background")
            foreground = if (isSelected) UIManager.getColor("List.selectionForeground") else UIManager.getColor("List.foreground")
            text.foreground = foreground
            isOpaque = true
            return this
        }
    }
}
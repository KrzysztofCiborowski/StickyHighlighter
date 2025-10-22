package net.historynoob.stickyhighlighter

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.DumbAware
import java.awt.Color

class ToggleHighlightAction : AnAction(), DumbAware {

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.getData(CommonDataKeys.EDITOR) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val project = e.project ?: return
        val document = editor.document
        val selection = editor.selectionModel

        if (!selection.hasSelection()) {
            selection.selectWordAtCaret(true)
            if (!selection.hasSelection()) return
        }

        val start = selection.selectionStart
        val end = selection.selectionEnd
        if (start >= end) return

        val store = project.service<StickyHighlighterService>()
        store.pruneInvalid(document)

        // Toggle off if an existing highlight fully covers the range
        val existing = store.allFor(document).firstOrNull { covers(it, start, end) }
        if (existing != null) {
            existing.dispose()
            store.remove(document, existing)
            project.service<StickyHighlightsPanelHolder>().panel?.refresh()
            return
        }

        // Build attributes from current settings
        val st = StickySettings.instance().state
        val fill = withOpacity(Color(st.fillColorArgb, true), st.opacity)
        val stripe = Color(st.stripeColorArgb, true)
        val attrs = TextAttributes(null, fill, stripe, EffectType.BOXED, 0)

        // Use Document markup so it survives editor close/open
        val mm = DocumentMarkupModel.forDocument(document, project, true)
        val hl = mm.addRangeHighlighter(
            start, end,
            HighlighterLayer.SELECTION - 1,
            attrs,
            HighlighterTargetArea.EXACT_RANGE
        ).apply {
            isGreedyToLeft = true
            isGreedyToRight = true
            setErrorStripeMarkColor(stripe)
            setThinErrorStripeMark(false)
            setErrorStripeTooltip("Sticky highlight")
        }

        store.add(document, hl)
        project.service<StickyHighlightsPanelHolder>().panel?.refresh()
        selection.removeSelection()
    }

    private fun withOpacity(base: Color, percent: Int): Color {
        val a = (percent.coerceIn(0, 100) * 255) / 100
        return Color(base.red, base.green, base.blue, a)
    }

    private fun covers(hl: RangeMarker, start: Int, end: Int): Boolean {
        val hs = hl.startOffset; val he = hl.endOffset
        return hs <= start && he >= end
    }
}
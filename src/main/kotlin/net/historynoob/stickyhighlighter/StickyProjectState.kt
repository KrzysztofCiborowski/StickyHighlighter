package net.historynoob.stickyhighlighter

import com.intellij.openapi.components.*
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import java.awt.Color
import kotlin.math.max

@State(
    name = "StickyHighlighterProjectState",
    storages = [Storage(StoragePathMacros.WORKSPACE_FILE)]
)
@Service(Service.Level.PROJECT)
class StickyProjectState(private val project: Project) : PersistentStateComponent<StickyProjectState.State> {

    data class Entry(
        var fileUrl: String = "",
        var start: Int = 0,
        var end: Int = 0,
        var fillArgb: Int? = null,
        var stripeArgb: Int? = null
    )

    data class State(var entries: MutableList<Entry> = mutableListOf())
    private var loaded: State = State()

    override fun getState(): State {
        val service = project.service<StickyHighlighterService>()
        val fdm = FileDocumentManager.getInstance()
        val entries = mutableListOf<Entry>()
        service.snapshot().forEach { (doc, highs) ->
            val vf = fdm.getFile(doc) ?: return@forEach
            highs.filter { it.isValid }.forEach { hl ->
                entries.add(
                    Entry(
                        vf.url,
                        hl.startOffset,
                        hl.endOffset,
                        hl.getUserData(StickyHighlighterService.KEY_FILL),
                        hl.getUserData(StickyHighlighterService.KEY_STRIPE)
                    )
                )
            }
        }
        return State(entries)
    }

    override fun loadState(state: State) {
        loaded = state
    }

    /** Recreate highlighters from last saved state. Call at project startup. */
    fun restoreFromState() {
        if (loaded.entries.isEmpty()) return
        val fdm = FileDocumentManager.getInstance()
        val st = StickySettings.instance().state
        val service = project.service<StickyHighlighterService>()

        for (e in loaded.entries) {
            val vf: VirtualFile = VirtualFileManager.getInstance().findFileByUrl(e.fileUrl) ?: continue
            val doc: Document = fdm.getDocument(vf) ?: continue

            val start = e.start.coerceIn(0, max(0, doc.textLength - 1))
            val end = e.end.coerceIn(start + 1, doc.textLength)

            val fillArgb = e.fillArgb ?: st.fillColorArgb
            val stripeArgb = e.stripeArgb ?: st.stripeColorArgb
            val fill = Color(fillArgb, true).let { withOpacity(it, st.opacity) }
            val stripe = Color(stripeArgb, true)
            val fg = EditorColorsManager.getInstance().globalScheme.defaultForeground

            val attrs = TextAttributes(fg, fill, stripe, EffectType.BOXED, 0)
            val mm = DocumentMarkupModel.forDocument(doc, project, true)
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
                e.fillArgb?.let { putUserData(StickyHighlighterService.KEY_FILL, it) }
                e.stripeArgb?.let { putUserData(StickyHighlighterService.KEY_STRIPE, it) }
            }

            service.add(doc, hl)
        }
    }

    private fun withOpacity(base: Color, percent: Int): Color {
        val a = (percent.coerceIn(0, 100) * 255) / 100
        return Color(base.red, base.green, base.blue, a)
    }
}
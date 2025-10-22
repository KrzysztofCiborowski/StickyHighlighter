package net.historynoob.stickyhighlighter

import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.editor.markup.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.util.messages.Topic
import java.awt.Color
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.PROJECT)
class StickyHighlighterService(private val project: Project) {

    companion object {
        interface Listener { fun changed() }
        val TOPIC: Topic<Listener> = Topic.create("StickyHighlightsChanged", Listener::class.java)

        // Per-highlight color overrides (ARGB)
        val KEY_FILL: Key<Int> = Key.create("sticky.fillArgb")
        val KEY_STRIPE: Key<Int> = Key.create("sticky.stripeArgb")
    }

    private val store = ConcurrentHashMap<Document, MutableList<RangeHighlighter>>()

    private fun notifyChanged() {
        project.messageBus.syncPublisher(TOPIC).changed()
    }

    fun add(document: Document, hl: RangeHighlighter) {
        store.computeIfAbsent(document) { mutableListOf() }.add(hl)
        notifyChanged()
    }

    fun allFor(document: Document): MutableList<RangeHighlighter> =
        store.getOrPut(document) { mutableListOf() }

    fun remove(document: Document, hl: RangeHighlighter) {
        store[document]?.remove(hl)
        if (store[document]?.none { it.isValid } == true) store.remove(document)
        notifyChanged()
    }

    fun removeByRange(document: Document, start: Int, end: Int) {
        val hl = findHighlight(document, start, end) ?: return
        hl.dispose()
        remove(document, hl)
    }

    fun clear(document: Document) {
        store.remove(document)?.forEach { it.dispose() }
        notifyChanged()
    }

    fun pruneInvalid(document: Document? = null) {
        var changed = false
        if (document != null) {
            val list = store[document] ?: return
            if (list.removeIf { !it.isValid }) changed = true
            if (list.isEmpty()) { store.remove(document); changed = true }
        } else {
            val it = store.entries.iterator()
            while (it.hasNext()) {
                val entry = it.next()
                entry.value.removeIf { !it.isValid }
                if (entry.value.isEmpty()) { it.remove(); changed = true }
            }
        }
        if (changed) notifyChanged()
    }

    fun snapshot(): Map<Document, List<RangeHighlighter>> {
        pruneInvalid()
        return store.mapValues { it.value.toList() }.toMap()
    }

    /** Rebuild all highlighters using current global/per-item colors (live recolor). */
    fun recolorAll(project: Project) {
        pruneInvalid()
        store.keys.toList().forEach { doc ->
            val mm = DocumentMarkupModel.forDocument(doc, project, true)
            val old = store[doc] ?: return@forEach
            val rebuilt = mutableListOf<RangeHighlighter>()
            old.forEach { h ->
                if (!h.isValid) {
                    h.dispose()
                } else {
                    val (fill, stripe, attrs) = attrsFor(h)
                    val fresh = mm.addRangeHighlighter(
                        h.startOffset, h.endOffset,
                        HighlighterLayer.SELECTION - 1,
                        attrs,
                        HighlighterTargetArea.EXACT_RANGE
                    ).apply {
                        isGreedyToLeft = true
                        isGreedyToRight = true
                        setErrorStripeMarkColor(stripe)
                        setThinErrorStripeMark(false)
                        setErrorStripeTooltip("Sticky highlight")
                        h.getUserData(KEY_FILL)?.let { putUserData(KEY_FILL, it) }
                        h.getUserData(KEY_STRIPE)?.let { putUserData(KEY_STRIPE, it) }
                    }
                    h.dispose()
                    rebuilt.add(fresh)
                }
            }
            store[doc] = rebuilt
        }
        notifyChanged()
    }

    /** Set ONE channel (fill or stripe). Pass non-null to set. */
    fun recolorOne(document: Document, start: Int, end: Int, fillArgb: Int?, stripeArgb: Int?) {
        val target = findHighlight(document, start, end) ?: return
        val mm = DocumentMarkupModel.forDocument(document, project, true)

        // Keep existing overrides unless the caller provided a new value
        val oldFill = target.getUserData(KEY_FILL)
        val oldStripe = target.getUserData(KEY_STRIPE)
        val effFillOverride = fillArgb ?: oldFill
        val effStripeOverride = stripeArgb ?: oldStripe

        // Build attrs using the effective overrides
        val (_, stripe, attrs) = attrsFor(
            hl = target,
            overrideFillArgb = effFillOverride,
            overrideStripeArgb = effStripeOverride
        )

        val fresh = mm.addRangeHighlighter(
            target.startOffset, target.endOffset,
            HighlighterLayer.SELECTION - 1,
            attrs,
            HighlighterTargetArea.EXACT_RANGE
        ).apply {
            isGreedyToLeft = true
            isGreedyToRight = true
            setErrorStripeMarkColor(stripe)
            setThinErrorStripeMark(false)
            setErrorStripeTooltip("Sticky highlight")

            // Write BOTH keys so we preserve/clear correctly.
            // putUserData(key, null) = clear override.
            putUserData(KEY_FILL, effFillOverride)
            putUserData(KEY_STRIPE, effStripeOverride)
        }

        // swap + dispose old
        store[document]?.let { list ->
            val idx = list.indexOf(target)
            if (idx >= 0) list[idx] = fresh
        }
        target.dispose()
        notifyChanged()
    }

    /** Full reset to global defaults for one highlight (single click). */
    fun resetColorsOne(document: Document, start: Int, end: Int) {
        val target = findHighlight(document, start, end) ?: return
        val mm = DocumentMarkupModel.forDocument(document, project, true)
        val st = StickySettings.instance().state
        val (fill, stripe, attrs) = attrsFor(target, overrideFillArgb = st.fillColorArgb, overrideStripeArgb = st.stripeColorArgb)

        val fresh = mm.addRangeHighlighter(
            target.startOffset, target.endOffset,
            HighlighterLayer.SELECTION - 1,
            attrs,
            HighlighterTargetArea.EXACT_RANGE
        ).apply {
            isGreedyToLeft = true
            isGreedyToRight = true
            setErrorStripeMarkColor(stripe)
            setThinErrorStripeMark(false)
            setErrorStripeTooltip("Sticky highlight")
            putUserData(KEY_FILL, null)
            putUserData(KEY_STRIPE, null)
        }

        store[document]?.let { list ->
            val idx = list.indexOf(target)
            if (idx >= 0) list[idx] = fresh
        }
        target.dispose()
        notifyChanged()
    }

    fun findHighlight(document: Document, start: Int, end: Int): RangeHighlighter? =
        store[document]?.firstOrNull { it.startOffset == start && it.endOffset == end }

    /** Resolve effective attributes for a highlighter (custom overrides or global). */
    fun attrsFor(
        hl: RangeHighlighter,
        overrideFillArgb: Int? = null,
        overrideStripeArgb: Int? = null
    ): Triple<Color, Color, TextAttributes> {
        val st = StickySettings.instance().state
        val fillArgb = overrideFillArgb ?: hl.getUserData(KEY_FILL) ?: st.fillColorArgb
        val stripeArgb = overrideStripeArgb ?: hl.getUserData(KEY_STRIPE) ?: st.stripeColorArgb

        val fill = withOpacity(Color(fillArgb, true), st.opacity)
        val stripe = Color(stripeArgb, true)

        val attrs = TextAttributes(null, fill, stripe, EffectType.BOXED, 0)
        return Triple(fill, stripe, attrs)
    }

    private fun withOpacity(base: Color, percent: Int): Color {
        val a = (percent.coerceIn(0, 100) * 255) / 100
        return Color(base.red, base.green, base.blue, a)
    }
}
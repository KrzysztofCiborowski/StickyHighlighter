package net.historynoob.stickyhighlighter

import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.ui.ColorPanel
import com.intellij.util.ui.FormBuilder
import java.awt.Color
import javax.swing.JComponent
import javax.swing.JSlider

class StickySettingsConfigurable : Configurable {

    private val fillColor = ColorPanel()
    private val stripeColor = ColorPanel()
    private val opacity = JSlider(0, 100)

    private var root: JComponent? = null
    private val state get() = StickySettings.instance().state

    override fun getDisplayName(): String = "Sticky Highlighter"

    override fun createComponent(): JComponent {
        if (root == null) {
            fillColor.selectedColor = Color(state.fillColorArgb, true)
            stripeColor.selectedColor = Color(state.stripeColorArgb, true)
            opacity.value = state.opacity
            opacity.majorTickSpacing = 20
            opacity.minorTickSpacing = 5
            opacity.paintTicks = true
            opacity.paintLabels = true

            root = FormBuilder.createFormBuilder()
                .addLabeledComponent("Highlight color:", fillColor)
                .addLabeledComponent("Stripe color:", stripeColor)
                .addLabeledComponent("Opacity (%):", opacity)
                .panel
        }
        return root!!
    }

    override fun isModified(): Boolean {
        val s = state
        val uiFill = (fillColor.selectedColor ?: Color(s.fillColorArgb, true)).rgb
        val uiStripe = (stripeColor.selectedColor ?: Color(s.stripeColorArgb, true)).rgb
        val uiOpacity = opacity.value
        return uiFill != s.fillColorArgb || uiStripe != s.stripeColorArgb || uiOpacity != s.opacity
    }

    override fun apply() {
        val s = state
        s.fillColorArgb = (fillColor.selectedColor ?: Color(s.fillColorArgb, true)).rgb
        s.stripeColorArgb = (stripeColor.selectedColor ?: Color(s.stripeColorArgb, true)).rgb
        s.opacity = opacity.value

        ProjectManager.getInstance().openProjects.forEach { p ->
            p.service<StickyHighlighterService>().recolorAll(p)
            p.service<StickyHighlightsPanelHolder>().panel?.refresh()
        }
    }

    override fun reset() {
        val s = state
        fillColor.selectedColor = Color(s.fillColorArgb, true)
        stripeColor.selectedColor = Color(s.stripeColorArgb, true)
        opacity.value = s.opacity
    }
}
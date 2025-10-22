package net.historynoob.stickyhighlighter

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
class StickyHighlightsPanelHolder(val project: Project) {
    var panel: StickyHighlightsPanel? = null
}
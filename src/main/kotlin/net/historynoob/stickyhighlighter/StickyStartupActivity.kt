package net.historynoob.stickyhighlighter

import com.intellij.openapi.components.service
import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class StickyStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        readAction {
            project.service<StickyProjectState>().restoreFromState()
        }
    }
}
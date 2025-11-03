package com.pagewiser.idea.sentry.startup

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.pagewiser.idea.sentry.SentryEditorListener

class MyProjectActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        val connection = project.messageBus.connect(project)
        connection.subscribe(com.intellij.openapi.fileEditor.FileEditorManagerListener.FILE_EDITOR_MANAGER, SentryEditorListener(project))
    }
}
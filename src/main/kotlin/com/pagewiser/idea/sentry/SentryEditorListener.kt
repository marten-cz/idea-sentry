package com.pagewiser.idea.sentry

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.*
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import javax.swing.SwingUtilities

class SentryEditorListener(private val project: Project) : FileEditorManagerListener {
    companion object {
        private val SENTRY_HIGHLIGHT_KEY = Key.create<Boolean>("SENTRY_HIGHLIGHT")
    }

    override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
        annotate(source, file)
    }

    override fun selectionChanged(event: FileEditorManagerEvent) {
        val manager = FileEditorManager.getInstance(project)
        val file = event.newFile ?: manager.selectedFiles.firstOrNull() ?: return
        annotate(manager, file)
    }

    private fun annotate(manager: FileEditorManager, file: VirtualFile) {
        if (!SentrySettingsState.getInstance().state.enabled) return
        val token = SentrySettingsState.getInstance().state.apiToken
        val projectSlug = SentrySettingsState.getInstance().state.selectedProject
        if (token.isBlank() || projectSlug.isBlank()) return
        manager.getAllEditors(file).forEach { editor -> maybeAnnotateEditor(editor, file, token, projectSlug) }
    }

    private fun maybeAnnotateEditor(fileEditor: FileEditor, file: VirtualFile, token: String, projectSlug: String) {
        val textEditor = fileEditor as? com.intellij.openapi.fileEditor.TextEditor ?: return
        val editor = textEditor.editor

        Thread {
            val findings = SentryApi.findFindingsForFile(token, projectSlug, file.path)
            if (findings.isEmpty()) return@Thread
            SwingUtilities.invokeLater {
                if (project.isDisposed) return@invokeLater
                applyGutterIcons(editor, findings)
            }
        }.start()
    }

    private fun applyGutterIcons(editor: Editor, findings: List<SentryFinding>) {
        val markup = editor.markupModel
        markup.allHighlighters.filter { it.getUserData(SENTRY_HIGHLIGHT_KEY) == true }.forEach(markup::removeHighlighter)

        val doc = editor.document
        findings.forEach { finding ->
            if (finding.lineNumber <= 0) return@forEach
            val lineIdx = (finding.lineNumber - 1).coerceIn(0, doc.lineCount - 1)
            val highlighter = markup.addLineHighlighter(lineIdx, HighlighterLayer.ERROR, null)
            highlighter.gutterIconRenderer = object : GutterIconRenderer() {
                override fun getIcon() = AllIcons.General.BalloonError
                override fun getTooltipText(): String = buildString {
                    append(finding.title)
                    finding.issueUrl?.let { append("\n").append(it) }
                }
                override fun getClickAction(): AnAction? = object : AnAction("Open in Sentry", "Open issue in Sentry", AllIcons.General.BalloonInformation) {
                    override fun actionPerformed(e: AnActionEvent) {
                        finding.issueUrl?.let { BrowserUtil.browse(it) }
                    }
                }
                override fun equals(other: Any?) = other === this
                override fun hashCode(): Int = finding.issueId.hashCode() * 31 + lineIdx
            }
            highlighter.putUserData(SENTRY_HIGHLIGHT_KEY, true)
        }
    }
}

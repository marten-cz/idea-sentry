package com.pagewiser.idea.sentry.toolWindow

import com.intellij.ide.BrowserUtil
import com.intellij.icons.AllIcons
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.util.ScalableIcon
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.pagewiser.idea.sentry.*
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

class MyToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        toolWindow.setStripeTitle("Sentry Issues")
        val baseIcon = AllIcons.Toolwindows.ToolWindowProblems
        toolWindow.setIcon(baseIcon)

        val mainPanel = JPanel()
        mainPanel.layout = BoxLayout(mainPanel, BoxLayout.Y_AXIS)

        val headerPanel = Box.createHorizontalBox()
        val titleLabel = JLabel("Sentry Issues")
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD)
        headerPanel.add(titleLabel)
        headerPanel.add(Box.createHorizontalGlue())
        val debugBtn = JButton("\uD83D\uDC1B").apply { // bug emoji
            toolTipText = "Show debug log"
            preferredSize = Dimension(34, 28)
        }
        headerPanel.add(debugBtn)
        mainPanel.add(headerPanel)
        mainPanel.add(Box.createRigidArea(Dimension(0, 5)))

        val findingListModel = DefaultListModel<SentryFinding>()
        val findingList = JList(findingListModel)
        findingList.fixedCellHeight = -1
        findingList.cellRenderer = SentryFindingRenderer()

        var currentFilePath: String? = null

        findingList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val idx = findingList.locationToIndex(e.point)
                if (idx < 0) return
                val f = findingListModel.getElementAt(idx)
                if (e.clickCount == 2) {
                    f.issueUrl?.let { BrowserUtil.browse(it) }
                } else if (e.clickCount == 1) {
                    val path = currentFilePath ?: return
                    val vf = LocalFileSystem.getInstance().findFileByPath(path) ?: return
                    val line = if (f.lineNumber > 0) f.lineNumber - 1 else 0
                    OpenFileDescriptor(project, vf, line, 0).navigate(true)
                }
            }
        })
        val scrollPane = JBScrollPane(findingList)
        scrollPane.preferredSize = Dimension(460, 220)
        mainPanel.add(scrollPane)
        mainPanel.add(Box.createRigidArea(Dimension(0, 8)))

        val debugLogArea = JTextArea(10, 36).apply {
            isEditable = false
            font = Font(Font.MONOSPACED, Font.PLAIN, 12)
            isVisible = false
        }
        val debugScroll = JBScrollPane(debugLogArea)
        debugScroll.isVisible = false
        mainPanel.add(debugScroll)

        debugBtn.addActionListener {
            debugLogArea.isVisible = !debugLogArea.isVisible
            debugScroll.isVisible = debugLogArea.isVisible
            mainPanel.revalidate()
            mainPanel.repaint()
        }

        val updateStripe = { count: Int ->
            if (count > 0) {
                toolWindow.setStripeTitle("Sentry Issues ($count)")
                toolWindow.setIcon(BadgedIcon(baseIcon, count))
            } else {
                toolWindow.setStripeTitle("Sentry Issues")
                toolWindow.setIcon(baseIcon)
            }
        }

        val updateFindingsFor = fun(filePath: String?) {
            currentFilePath = filePath
            findingListModel.clear()
            val token = SentrySettingsState.getInstance().state.apiToken
            val projectSlug = SentrySettingsState.getInstance().state.selectedProject
            if (filePath == null || token.isBlank() || projectSlug.isBlank()) {
                updateStripe(0)
                return
            }

            findingListModel.addElement(SentryFinding("", "Loading ...", 0, null))
            Thread {
                val findings = SentryApi.findFindingsForFile(token, projectSlug, filePath)
                SentryDebugLog.log("Findings query for $filePath => ${findings.size} results")
                SwingUtilities.invokeLater {
                    findingListModel.clear()
                    if (findings.isEmpty()) {
                        findingListModel.addElement(SentryFinding("", "No production exceptions found for this file.", 0, null))
                    } else {
                        findings.forEach { findingListModel.addElement(it) }
                    }
                    updateStripe(findings.size)
                    debugLogArea.text = SentryDebugLog.dumpLog()
                }
            }.start()
        }

        val editorManager = FileEditorManager.getInstance(project)
        val updateCurrentFile: () -> Unit = {
            val file = editorManager.selectedFiles.firstOrNull()
            updateFindingsFor(file?.path)
        }
        project.messageBus.connect().subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    updateCurrentFile()
                }
            }
        )
        updateCurrentFile()
        
        val content = ContentFactory.getInstance().createContent(mainPanel, null, false)
        toolWindow.contentManager.addContent(content)
    }
}

private class BadgedIcon(private val base: Icon, private val count: Int) : Icon, ScalableIcon {
    override fun getIconWidth(): Int = base.iconWidth
    override fun getIconHeight(): Int = base.iconHeight

    override fun paintIcon(c: Component?, g: Graphics?, x: Int, y: Int) {
        base.paintIcon(c, g, x, y)
        if (count <= 0) return
        val g2 = (g as? Graphics2D) ?: return
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val d = (iconWidth * 0.5).toInt().coerceAtLeast(10)
        val bx = x + iconWidth - d + 1
        val by = y + iconHeight - d + 1
        g2.color = Color(0xD3, 0x2F, 0x2F)
        g2.fillOval(bx, by, d, d)
        g2.color = Color.WHITE
        val text = if (count > 9) "9+" else count.toString()
        val fm = g2.fontMetrics
        val tx = bx + (d - fm.stringWidth(text)) / 2
        val ty = by + (d + fm.ascent - fm.descent) / 2 - 1
        g2.drawString(text, tx, ty)
    }

    override fun scale(scale: Float): Icon {
        val scaledBase = if (base is ScalableIcon) base.scale(scale) else base
        return BadgedIcon(scaledBase, count)
    }
}

private class SentryFindingRenderer : JPanel(), ListCellRenderer<SentryFinding> {
    private val titleLabel = JLabel()
    private val descLabel = JLabel()
    private val footerLabel = JLabel()
    private var severityColor: Color = Color(0x90, 0xA4, 0xAE) // default gray

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        // Increase left padding so text does not overlap the severity circle
        border = BorderFactory.createEmptyBorder(6, 24, 6, 8)
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD)
        descLabel.foreground = Color.GRAY
        footerLabel.foreground = Color(DARK_GRAY)
        add(titleLabel)
        add(Box.createRigidArea(Dimension(0, 2)))
        add(descLabel)
        add(Box.createRigidArea(Dimension(0, 2)))
        add(footerLabel)
    }

    override fun getListCellRendererComponent(list: JList<out SentryFinding>, value: SentryFinding, index: Int, isSelected: Boolean, cellHasFocus: Boolean): Component {
        background = if (isSelected) list.selectionBackground else list.background
        foreground = if (isSelected) list.selectionForeground else list.foreground

        severityColor = when (value.level.lowercase()) {
            "error", "fatal" -> Color(0xD3, 0x2F, 0x2F)
            "warning", "warn" -> Color(0xF5, 0x7C, 0x00)
            "info" -> Color(0x19, 0x76, 0xD2)
            else -> Color(0x90, 0xA4, 0xAE)
        }

        // Split title at first ':' to derive title and description
        val rawTitle = value.title
        val splitIdx = rawTitle.indexOf(':')
        val (titleText, descFromTitle) = if (splitIdx > 0) {
            rawTitle.substring(0, splitIdx).trim() to rawTitle.substring(splitIdx + 1).trim()
        } else rawTitle to ""

        val line = if (value.lineNumber > 0) "[Line ${value.lineNumber}] " else ""
        titleLabel.text = "$line$titleText"
        val displayDesc = when {
            descFromTitle.isNotBlank() -> descFromTitle
            value.description.isNotBlank() -> value.description
            else -> ""
        }
        descLabel.text = displayDesc

        val unhandled = if (value.unhandled) "<html><font color='#D32F2F'><b>Unhandled</b></font></html>" else ""
        val version = value.latestRelease?.let { "ver: $it" } ?: ""
        val last = value.lastSeen?.let { "⏰ last: $it" } ?: ""
        val first = value.firstSeen?.let { "⏳ first: $it" } ?: ""
        val count = if (value.occurrences > 0) "⬤ ${value.occurrences}" else ""
        footerLabel.text = listOf(version, unhandled, last, first, count).filter { it.isNotBlank() }.joinToString("   ")

        return this
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g as Graphics2D
        val size = 10
        // Keep the circle inside the left padding area
        val x = 8
        val y = 10
        g2.color = severityColor
        g2.fillOval(x, y, size, size)
    }

    companion object {
        private const val DARK_GRAY = 0x6E6E6E
    }
}

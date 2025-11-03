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
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.openapi.util.ScalableIcon
import com.intellij.util.ui.JBScalableIcon
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

        val tabs = JTabbedPane()

        // Active File tab content
        val activePanel = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
        val activeHeader = Box.createHorizontalBox()
        val activeTitle = JLabel("Sentry Issues (Active File)")
        activeTitle.font = activeTitle.font.deriveFont(Font.BOLD)
        activeHeader.add(activeTitle)
        activeHeader.add(Box.createHorizontalGlue())
        val debugBtn = JButton("\uD83D\uDC1B").apply { toolTipText = "Show debug log"; preferredSize = Dimension(34, 28) }
        activeHeader.add(debugBtn)
        activePanel.add(activeHeader)
        activePanel.add(Box.createRigidArea(Dimension(0, 5)))

        val activeListModel = DefaultListModel<SentryFinding>()
        val activeList = JList(activeListModel).apply {
            fixedCellHeight = -1
            cellRenderer = SentryFindingRenderer(showFilenameInFooter = false)
        }
        var currentFilePath: String? = null
        activeList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val idx = activeList.locationToIndex(e.point); if (idx < 0) return
                val f = activeListModel.getElementAt(idx)
                if (e.clickCount == 2) f.issueUrl?.let { BrowserUtil.browse(it) } else if (e.clickCount == 1) {
                    val path = currentFilePath ?: return
                    val vf = LocalFileSystem.getInstance().findFileByPath(path) ?: return
                    val line = if (f.lineNumber > 0) f.lineNumber - 1 else 0
                    OpenFileDescriptor(project, vf, line, 0).navigate(true)
                }
            }
        })
        val activeScroll = JBScrollPane(activeList).apply { preferredSize = Dimension(460, 220) }
        activePanel.add(activeScroll)
        activePanel.add(Box.createRigidArea(Dimension(0, 8)))

        val debugLogArea = JTextArea(10, 36).apply { isEditable = false; font = Font(Font.MONOSPACED, Font.PLAIN, 12); isVisible = false }
        val debugScroll = JBScrollPane(debugLogArea).apply { isVisible = false }
        activePanel.add(debugScroll)
        debugBtn.addActionListener { debugLogArea.isVisible = !debugLogArea.isVisible; debugScroll.isVisible = debugLogArea.isVisible; activePanel.revalidate(); activePanel.repaint() }

        // All Issues tab content
        val allPanel = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
        val allHeader = Box.createHorizontalBox()
        val allTitle = JLabel("Sentry Issues (All)")
        allTitle.font = allTitle.font.deriveFont(Font.BOLD)
        allHeader.add(allTitle)
        allHeader.add(Box.createHorizontalGlue())
        allPanel.add(allHeader)
        allPanel.add(Box.createRigidArea(Dimension(0, 5)))
        val allListModel = DefaultListModel<SentryFinding>()
        val allList = JList(allListModel).apply {
            fixedCellHeight = -1
            cellRenderer = SentryFindingRenderer(showFilenameInFooter = true)
        }
        allList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val idx = allList.locationToIndex(e.point); if (idx < 0) return
                val f = allListModel.getElementAt(idx)
                if (e.clickCount == 2) f.issueUrl?.let { BrowserUtil.browse(it) }
            }
        })
        val allScroll = JBScrollPane(allList).apply { preferredSize = Dimension(460, 220) }
        allPanel.add(allScroll)

        tabs.addTab("Active File", activePanel)
        tabs.addTab("All Issues", allPanel)

        val updateStripe = { count: Int ->
            if (count > 0) { toolWindow.setStripeTitle("Sentry Issues ($count)"); toolWindow.setIcon(BadgedIcon(baseIcon, count)) }
            else { toolWindow.setStripeTitle("Sentry Issues"); toolWindow.setIcon(baseIcon) }
        }

        val refreshActive = fun(filePath: String?) {
            currentFilePath = filePath
            activeListModel.clear()
            val token = SentrySettingsState.getInstance().state.apiToken
            val projectSlug = SentrySettingsState.getInstance().state.selectedProject
            if (filePath == null || token.isBlank() || projectSlug.isBlank()) { updateStripe(0); return }
            activeListModel.addElement(SentryFinding("", "Loading ...", 0, null))
            Thread {
                val findings = SentryApi.findFindingsForFile(token, projectSlug, filePath)
                SentryDebugLog.log("Findings query for $filePath => ${findings.size} results")
                SwingUtilities.invokeLater {
                    activeListModel.clear()
                    if (findings.isEmpty()) activeListModel.addElement(SentryFinding("", "No production exceptions found for this file.", 0, null))
                    else findings.forEach { activeListModel.addElement(it) }
                    updateStripe(findings.size)
                    debugLogArea.text = SentryDebugLog.dumpLog()
                }
            }.start()
        }

        val refreshAll = fun() {
            allListModel.clear()
            val token = SentrySettingsState.getInstance().state.apiToken
            val projectSlug = SentrySettingsState.getInstance().state.selectedProject
            if (token.isBlank() || projectSlug.isBlank()) return
            allListModel.addElement(SentryFinding("", "Loading ...", 0, null))
            Thread {
                val findings = SentryApi.findAllIssues(token, projectSlug)
                SentryDebugLog.log("All issues query => ${findings.size} results")
                SwingUtilities.invokeLater {
                    allListModel.clear()
                    if (findings.isEmpty()) allListModel.addElement(SentryFinding("", "No issues found.", 0, null))
                    else findings.forEach { allListModel.addElement(it) }
                }
            }.start()
        }

        tabs.addChangeListener { if (tabs.selectedIndex == 1) refreshAll() }

        val editorManager = FileEditorManager.getInstance(project)
        val updateCurrentFile: () -> Unit = { val file = editorManager.selectedFiles.firstOrNull(); refreshActive(file?.path) }
        project.messageBus.connect().subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) { updateCurrentFile() }
        })
        updateCurrentFile()

        val content = ContentFactory.getInstance().createContent(tabs, null, false)
        toolWindow.contentManager.addContent(content)
    }
}

class BadgedIcon private constructor(
    private val base: Icon,
    private val count: Int,
    private val myScale: Float
) : JBScalableIcon(), ScalableIcon {

    constructor(base: Icon, count: Int) : this(
        base = base,
        count = count,
        myScale = (base as? ScalableIcon)?.scale ?: 1f
    )

    override fun getIconWidth(): Int = base.iconWidth
    override fun getIconHeight(): Int = base.iconHeight

    // Return our backing field (avoid Kotlin's synthetic `scale` property recursion).
    override fun getScale(): Float = myScale

    // Produce a scaled copy and forward scaling to the base if possible.
    override fun scale(scale: Float): Icon {
        if (scale == myScale) return this
        val scaledBase = (base as? ScalableIcon)?.scale(scale) ?: base
        return BadgedIcon(scaledBase, count, scale)
    }

    override fun paintIcon(c: Component?, g: Graphics?, x: Int, y: Int) {
        base.paintIcon(c, g, x, y)
        if (count <= 0) return

        val g2 = g as? Graphics2D ?: return
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
}

private class SentryFindingRenderer(private val showFilenameInFooter: Boolean = false) : JPanel(), ListCellRenderer<SentryFinding> {
    private val titleLabel = JLabel()
    private val descLabel = JLabel()
    private val footerLabel = JLabel()
    private var severityColor: Color = Color(0x90, 0xA4, 0xAE)
    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = BorderFactory.createEmptyBorder(6, 24, 6, 8)
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD)
        descLabel.foreground = Color.GRAY
        footerLabel.foreground = Color(0x6E, 0x6E, 0x6E)
        add(titleLabel); add(Box.createRigidArea(Dimension(0, 2))); add(descLabel); add(Box.createRigidArea(Dimension(0, 2))); add(footerLabel)
    }
    override fun getListCellRendererComponent(list: JList<out SentryFinding>, value: SentryFinding, index: Int, isSelected: Boolean, cellHasFocus: Boolean): Component {
        background = if (isSelected) list.selectionBackground else list.background
        foreground = if (isSelected) list.selectionForeground else list.foreground
        severityColor = when (value.level.lowercase()) { "error", "fatal" -> Color(0xD3, 0x2F, 0x2F); "warning", "warn" -> Color(0xF5, 0x7C, 0x00); "info" -> Color(0x19, 0x76, 0xD2); else -> Color(0x90, 0xA4, 0xAE) }
        val rawTitle = value.title
        val splitIdx = rawTitle.indexOf(':')
        val (titleText, descFromTitle) = if (splitIdx > 0) rawTitle.substring(0, splitIdx).trim() to rawTitle.substring(splitIdx + 1).trim() else rawTitle to ""
        val line = if (value.lineNumber > 0) "[Line ${value.lineNumber}] " else ""
        titleLabel.text = "$line$titleText"
        descLabel.text = if (descFromTitle.isNotBlank()) descFromTitle else value.description
        val unhandled = if (value.unhandled) "<html><font color='#D32F2F'><b>Unhandled</b></font></html>" else ""
        val version = value.latestRelease?.let { "ver: $it" } ?: ""
        val last = value.lastSeen?.let { "⏰ last: $it" } ?: ""
        val first = value.firstSeen?.let { "⏳ first: $it" } ?: ""
        val count = if (value.occurrences > 0) "⬤ ${value.occurrences}" else ""
        val filenamePart = if (showFilenameInFooter && value.description.isNotBlank()) value.description else ""
        footerLabel.text = listOf(version, unhandled, last, first, count, filenamePart).filter { it.isNotBlank() }.joinToString("   ")
        return this
    }
    override fun paintComponent(g: Graphics) { super.paintComponent(g); val g2 = g as Graphics2D; val size = 10; val x = 8; val y = 10; g2.color = severityColor; g2.fillOval(x, y, size, size) }
}

package com.pagewiser.idea.sentry

import com.intellij.openapi.options.Configurable
import javax.swing.*
import java.awt.Component
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class SentrySettingsConfigurable : Configurable {
    private val enableIntegration = JCheckBox("Enable Sentry integration")
    private val tokenField = JTextField(30).apply {
        toolTipText = "Enter your Sentry API token"
        maximumSize = java.awt.Dimension(Short.MAX_VALUE.toInt(), 28)
        alignmentX = Component.LEFT_ALIGNMENT
    }
    private val projectCombo = JComboBox<String>().apply {
        setPrototypeDisplayValue("A-very-long-project-name-sample-here")
        alignmentX = Component.LEFT_ALIGNMENT
        maximumSize = java.awt.Dimension(Short.MAX_VALUE.toInt(), 28)
    }
    private val refreshBtn = JButton("\u21BB").apply {
        toolTipText = "Refresh project list from Sentry"
        preferredSize = java.awt.Dimension(45, 28)
        alignmentY = Component.CENTER_ALIGNMENT
    }
    private val sentryPrefixField = JTextField(30).apply {
        toolTipText = "Optional: prefix to match Sentry relative paths (e.g. ../../repo/subdir)"
        maximumSize = java.awt.Dimension(Short.MAX_VALUE.toInt(), 28)
        alignmentX = Component.LEFT_ALIGNMENT
    }
    private var settingsPanel: JPanel? = null

    private fun updateProjectCombo(projects: List<String>, selected: String?) {
        projectCombo.removeAllItems()
        projects.forEach { projectCombo.addItem(it) }
        if (selected != null && projects.contains(selected)) {
            projectCombo.selectedItem = selected
        } else if (projects.isNotEmpty()) {
            projectCombo.selectedIndex = 0
        }
    }

    override fun getDisplayName(): String = "Sentry Integration"

    override fun createComponent(): JPanel {
        if (settingsPanel == null) {
            val settings = SentrySettingsState.getInstance().state
            val panel = JPanel()
            panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
            panel.border = BorderFactory.createEmptyBorder(15, 10, 15, 10)

            enableIntegration.text = "Enable Sentry integration"
            enableIntegration.alignmentX = Component.LEFT_ALIGNMENT
            panel.add(enableIntegration)
            panel.add(Box.createRigidArea(java.awt.Dimension(0, 8)))

            val tokenLabel = JLabel("API Token:").apply { alignmentX = Component.LEFT_ALIGNMENT }
            panel.add(tokenLabel)
            panel.add(tokenField)
            panel.add(Box.createRigidArea(java.awt.Dimension(0, 8)))

            val projectLabel = JLabel("Project:").apply { alignmentX = Component.LEFT_ALIGNMENT }
            panel.add(projectLabel)

            val projectRow = Box.createHorizontalBox()
            projectRow.add(projectCombo)
            projectRow.add(Box.createRigidArea(java.awt.Dimension(6, 0)))
            projectRow.add(refreshBtn)
            projectRow.alignmentX = Component.LEFT_ALIGNMENT
            panel.add(projectRow)
            panel.add(Box.createRigidArea(java.awt.Dimension(0, 8)))

            val prefixLabel = JLabel("Sentry relative path prefix:").apply { alignmentX = Component.LEFT_ALIGNMENT }
            panel.add(prefixLabel)
            panel.add(sentryPrefixField)

            settingsPanel = panel

            // Initial state
            enableIntegration.isSelected = settings.enabled
            tokenField.text = settings.apiToken
            updateProjectCombo(settings.knownProjects, settings.selectedProject)
            sentryPrefixField.text = settings.sentryPathPrefix
            setControlsEnabled(settings.enabled)

            enableIntegration.addActionListener {
                setControlsEnabled(enableIntegration.isSelected)
                settingsPanel?.revalidate(); settingsPanel?.repaint()
            }
            val dirtyDocListener = object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent?) { settingsPanel?.revalidate(); settingsPanel?.repaint() }
                override fun removeUpdate(e: DocumentEvent?) { settingsPanel?.revalidate(); settingsPanel?.repaint() }
                override fun changedUpdate(e: DocumentEvent?) { settingsPanel?.revalidate(); settingsPanel?.repaint() }
            }
            tokenField.document.addDocumentListener(dirtyDocListener)
            sentryPrefixField.document.addDocumentListener(dirtyDocListener)
            projectCombo.addActionListener { settingsPanel?.revalidate(); settingsPanel?.repaint() }

            refreshBtn.addActionListener {
                val token = tokenField.text.trim()
                if (token.isNotEmpty()) {
                    projectCombo.removeAllItems()
                    projectCombo.addItem("Loading ...")
                    projectCombo.isEnabled = false
                    refreshBtn.isEnabled = false
                    Thread {
                        val projects = SentryApi.listProjects(token)
                        SwingUtilities.invokeLater {
                            if (projects.isNotEmpty()) {
                                updateProjectCombo(projects, null)
                                SentrySettingsState.getInstance().state.knownProjects = projects
                            } else {
                                JOptionPane.showMessageDialog(settingsPanel,
                                    "Unable to fetch projects from Sentry.\nCheck your token.",
                                    "Sentry Error", JOptionPane.ERROR_MESSAGE)
                                updateProjectCombo(settings.knownProjects, settings.selectedProject)
                            }
                            projectCombo.isEnabled = true
                            refreshBtn.isEnabled = true
                            settingsPanel?.revalidate(); settingsPanel?.repaint()
                        }
                    }.start()
                } else {
                    JOptionPane.showMessageDialog(settingsPanel,
                        "Set your Sentry API token first.",
                        "No Token", JOptionPane.WARNING_MESSAGE)
                }
            }
        }
        return settingsPanel!!
    }

    private fun setControlsEnabled(enabled: Boolean) {
        tokenField.isEnabled = enabled
        projectCombo.isEnabled = enabled
        refreshBtn.isEnabled = enabled
        sentryPrefixField.isEnabled = enabled
    }

    override fun isModified(): Boolean {
        val settings = SentrySettingsState.getInstance().state
        return settings.enabled != enableIntegration.isSelected
                || settings.apiToken != tokenField.text.trim()
                || settings.selectedProject != (projectCombo.selectedItem as? String ?: "")
                || settings.knownProjects != (0 until projectCombo.itemCount).map { i -> projectCombo.getItemAt(i) }
                || settings.sentryPathPrefix != sentryPrefixField.text.trim()
    }

    override fun apply() {
        val settings = SentrySettingsState.getInstance().state
        settings.enabled = enableIntegration.isSelected
        settings.apiToken = tokenField.text.trim()
        settings.selectedProject = projectCombo.selectedItem as? String ?: ""
        settings.knownProjects = (0 until projectCombo.itemCount).map { i -> projectCombo.getItemAt(i) }
        settings.sentryPathPrefix = sentryPrefixField.text.trim()
    }

    override fun reset() {
        val settings = SentrySettingsState.getInstance().state
        enableIntegration.isSelected = settings.enabled
        tokenField.text = settings.apiToken
        updateProjectCombo(settings.knownProjects, settings.selectedProject)
        sentryPrefixField.text = settings.sentryPathPrefix
        setControlsEnabled(settings.enabled)
    }

    override fun disposeUIResources() { settingsPanel = null }
}

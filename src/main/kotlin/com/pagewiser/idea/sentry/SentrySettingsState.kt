package com.pagewiser.idea.sentry

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@State(name = "SentrySettings", storages = [Storage("SentrySettings.xml")])
class SentrySettingsState : PersistentStateComponent<SentrySettings> {
    private var settings = SentrySettings()

    override fun getState(): SentrySettings = settings

    override fun loadState(state: SentrySettings) {
        settings = state
    }

    companion object {
        fun getInstance(): SentrySettingsState = com.intellij.openapi.application.ApplicationManager.getApplication().getService(SentrySettingsState::class.java)
    }
}

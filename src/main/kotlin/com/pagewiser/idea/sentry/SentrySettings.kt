package com.pagewiser.idea.sentry

// Persistent state class for Sentry config

data class SentrySettings(
    var enabled: Boolean = true,
    var apiToken: String = "",
    var selectedProject: String = "",
    var knownProjects: List<String> = listOf(),
    var sentryPathPrefix: String = ""
)

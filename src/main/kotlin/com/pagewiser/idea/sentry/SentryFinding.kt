package com.pagewiser.idea.sentry

data class SentryFinding(
    val issueId: String,
    val title: String,
    val lineNumber: Int, // 1-based line number, 0 if unknown
    val issueUrl: String? = null,
    val level: String = "error", // error, warning, info, etc.
    val description: String = "",
    val latestRelease: String? = null,
    val unhandled: Boolean = false,
    val lastSeen: String? = null,
    val firstSeen: String? = null,
    val occurrences: Int = 0
)

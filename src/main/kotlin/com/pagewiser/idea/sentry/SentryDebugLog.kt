package com.pagewiser.idea.sentry

object SentryDebugLog {
    private val log = mutableListOf<String>()
    @Synchronized
    fun log(msg: String) {
        log.add("[${java.time.LocalTime.now().toString().substring(0,8)}] $msg")
        if (log.size > 200) log.removeAt(0)
    }
    @Synchronized
    fun dumpLog(): String = log.joinToString("\n")
}


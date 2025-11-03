package com.pagewiser.idea.sentry

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

object SentryApi {
    private fun makeRequest(token: String, urlString: String): String? {
        val url = URL(urlString)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.connectTimeout = 5000
        conn.readTimeout = 10000
        conn.useCaches = false
        conn.doInput = true
        return try {
            val status = conn.responseCode
            if (status != 200) {
                SentryDebugLog.log("API Error for $urlString: status $status")
                null
            } else {
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                val body = reader.readText()
                reader.close()
                SentryDebugLog.log("Request to $urlString succeeded (${body.length} bytes)")
                return body
            }
        } catch (e: Exception) {
            SentryDebugLog.log("Request failed for $urlString: ${e.message}")
            null
        } finally {
            conn.disconnect()
        }
    }

    fun listProjects(token: String): List<String> {
        if (token.isBlank()) return emptyList()
        val body = makeRequest(token, "https://sentry.io/api/0/projects/") ?: return emptyList()
        return try {
            val arr = JSONArray(body)
            List(arr.length()) { i -> arr.getJSONObject(i).getString("slug") }
        } catch (e: Exception) {
            SentryDebugLog.log("Failed to parse projects: ${e.message}")
            emptyList()
        }
    }

    private fun getOrgSlugForProject(token: String, projectSlug: String): String? {
        val body = makeRequest(token, "https://sentry.io/api/0/projects/") ?: return null
        return try {
            val arr = JSONArray(body)
            for (i in 0 until arr.length()) {
                val proj = arr.getJSONObject(i)
                if (proj.getString("slug") == projectSlug) {
                    return proj.getJSONObject("organization").getString("slug")
                }
            }
            null
        } catch (e: Exception) {
            SentryDebugLog.log("Failed to find org for project $projectSlug: ${e.message}")
            null
        }
    }

    private fun getProjectIdForSlug(token: String, projectSlug: String): String? {
        val body = makeRequest(token, "https://sentry.io/api/0/projects/") ?: return null
        return try {
            val arr = JSONArray(body)
            for (i in 0 until arr.length()) {
                val proj = arr.getJSONObject(i)
                if (proj.getString("slug") == projectSlug) {
                    return proj.getString("id")
                }
            }
            null
        } catch (e: Exception) {
            SentryDebugLog.log("Failed to find project id for $projectSlug: ${e.message}")
            null
        }
    }

    private fun deriveProjectRelativePath(filePath: String): String {
        val normalized = filePath.replace('\\', '/').trim('/')
        val srcIdx = normalized.indexOf("/src/")
        if (srcIdx >= 0) {
            return normalized.substring(srcIdx + 1) // keep from 'src/...'
        }
        val parts = normalized.split('/')
        val tail = parts.takeLast(3).joinToString("/")
        return tail
    }

    fun findFindingsForFile(token: String, projectSlug: String, filePath: String): List<SentryFinding> {
        if (token.isBlank() || projectSlug.isBlank() || filePath.isBlank()) {
            SentryDebugLog.log("findFindingsForFile: missing token, projectSlug, or filePath")
            return emptyList()
        }

        val orgSlug = getOrgSlugForProject(token, projectSlug)
        val projectId = getProjectIdForSlug(token, projectSlug)
        if (orgSlug == null || projectId == null) {
            SentryDebugLog.log("Could not resolve org or project id for $projectSlug")
            return emptyList()
        }

        val rel = deriveProjectRelativePath(filePath)
        val prefix = SentrySettingsState.getInstance().state.sentryPathPrefix.trim('/').let { if (it.isNotEmpty()) "$it/" else "" }
        val localFilename = prefix + rel
        val filenameQuery = "stack.filename:*/$rel OR stack.filename:*$localFilename"
        val encodedQuery = URLEncoder.encode(filenameQuery, Charsets.UTF_8.name())
        val fields = listOf(
            "issue.id",
            "title",
            "stack.filename",
            "stack.lineno",
            "level",
            "error.handled",
            "timestamp",
            "count()",
            "release"
        ).joinToString("&") { f -> "field=" + URLEncoder.encode(f, Charsets.UTF_8.name()) }

        val url = "https://sentry.io/api/0/organizations/$orgSlug/events/?project=$projectId&$fields&query=$encodedQuery&per_page=50&referrer=ide-plugin"
        SentryDebugLog.log("Discover query: $filenameQuery (project=$projectId)")
        val body = makeRequest(token, url) ?: return emptyList()

        val byIssue = linkedMapOf<String, SentryFinding>()
        try {
            val obj = JSONObject(body)
            val data = obj.optJSONArray("data") ?: JSONArray()
            SentryDebugLog.log("response length: ${data.length()}")
            for (i in 0 until data.length()) {
                val row = data.getJSONObject(i)
                val issueId = row.optString("issue.id", "")
                if (issueId.isEmpty()) continue

                // stack.filename can be an array; we only want the primary (top) frame
                val fileField = row.opt("stack.filename")
                val rowPrimaryFile = when (fileField) {
                    is JSONArray -> if (fileField.length() > 0) fileField.optString(0, "") else ""
                    is String -> fileField.substringBefore(',')
                    else -> ""
                }.replace('\\', '/').trim()

                if (!rowPrimaryFile.endsWith(localFilename) && !rowPrimaryFile.endsWith(rel)) {
                    SentryDebugLog.log("skip row: filename mismatch rowPrimary='$rowPrimaryFile' local='$localFilename' rel='$rel'")
                    continue
                }

                val title = row.optString("title", "Unknown error")

                // stack.lineno can be an array too; pick first element
                val linenoField = row.opt("stack.lineno")
                val line = when (linenoField) {
                    is JSONArray -> linenoField.optInt(0, 0)
                    is Number -> linenoField.toInt()
                    is String -> linenoField.toIntOrNull() ?: 0
                    else -> 0
                }
                if (line <= 0) {
                    SentryDebugLog.log("skip row: no lineno for rowPrimary='$rowPrimaryFile'")
                    continue
                }

                val level = row.optString("level", "error")
                val handled = row.opt("error.handled")
                val unhandled = when (handled) {
                    is Boolean -> !handled
                    is Number -> handled.toInt() == 0
                    is String -> handled.equals("false", true) || handled == "0"
                    else -> false
                }
                val lastSeen = row.optString("timestamp", null)
                val firstSeen: String? = null
                val count = when (val c = row.opt("count")) {
                    is Number -> c.toInt()
                    is String -> c.toIntOrNull() ?: 0
                    else -> when (val c2 = row.opt("count()")) {
                        is Number -> c2.toInt()
                        is String -> c2.toIntOrNull() ?: 0
                        else -> 0
                    }
                }
                val release = row.optString("release", null)
                val urlIssue = "https://sentry.io/organizations/$orgSlug/issues/$issueId/"
                val desc = rowPrimaryFile

                val existing = byIssue[issueId]
                if (existing == null) {
                    byIssue[issueId] = SentryFinding(
                        issueId = issueId,
                        title = title,
                        lineNumber = line,
                        issueUrl = urlIssue,
                        level = level,
                        description = desc,
                        latestRelease = release,
                        unhandled = unhandled,
                        lastSeen = lastSeen,
                        firstSeen = firstSeen,
                        occurrences = count
                    )
                } else {
                    val betterLine = if (existing.lineNumber > 0) existing.lineNumber else line
                    val betterCount = maxOf(existing.occurrences, count)
                    val betterLast = existing.lastSeen ?: lastSeen
                    byIssue[issueId] = existing.copy(
                        lineNumber = betterLine,
                        occurrences = betterCount,
                        lastSeen = betterLast
                    )
                }
            }
        } catch (e: Exception) {
            SentryDebugLog.log("Failed to parse Discover response: ${e.message}")
        }

        // Enrich with issue details (firstSeen/lastSeen/total count when available)
        for ((issueId, existing) in byIssue.toMap()) {
            val issueUrl = "https://sentry.io/api/0/issues/$issueId/"
            val issueBody = makeRequest(token, issueUrl) ?: continue
            try {
                val obj = JSONObject(issueBody)
                val firstSeen = obj.optString("firstSeen", existing.firstSeen)
                val lastSeen = obj.optString("lastSeen", existing.lastSeen)
                val count = obj.optInt("count", existing.occurrences)
                byIssue[issueId] = existing.copy(firstSeen = firstSeen, lastSeen = lastSeen, occurrences = count)
            } catch (e: Exception) {
                SentryDebugLog.log("Failed to parse issue details for $issueId: ${e.message}")
            }
        }

        val findings = byIssue.values.toList()
        SentryDebugLog.log("findFindingsForFile (Discover+issue) returned ${findings.size} unique findings")
        return findings
    }
}

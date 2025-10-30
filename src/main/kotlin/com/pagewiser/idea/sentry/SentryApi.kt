package com.pagewiser.idea.sentry

import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object SentryApi {
    fun listProjects(token: String): List<String> {
        if (token.isBlank()) return emptyList()
        val url = URL("https://sentry.io/api/0/projects/")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.connectTimeout = 5000
        conn.readTimeout = 10000
        conn.useCaches = false
        conn.doInput = true
        return try {
            val status = conn.responseCode
            if (status != 200) throw Exception("API Error: $status")
            val reader = BufferedReader(InputStreamReader(conn.inputStream))
            val body = reader.readText()
            reader.close()
            // Parse as JSON
            val arr = JSONArray(body)
            List(arr.length()) { i -> arr.getJSONObject(i).getString("slug") }
        } catch (e: Exception) {
            emptyList()
        } finally {
            conn.disconnect()
        }
    }
}

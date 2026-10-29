package io.github.aviato28.scopecreep.update

import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

/** Queries public GitHub Releases. Unauthenticated: no token ever ships in the app. */
object UpdateChecker {
    private const val API = "https://api.github.com/repos/aviato28/Scope-Creep/releases"
    class UpdateException(message: String) : Exception(message)

    /** Newest release for the channel, or null when none exists. Blocking; call off the main thread. */
    fun fetchLatest(beta: Boolean): ReleaseInfo? {
        val url = if (beta) "$API?per_page=20" else "$API/latest"
        val body = try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000; c.readTimeout = 15_000
            c.setRequestProperty("User-Agent", "ScopeCreep-Updater")
            c.setRequestProperty("Accept", "application/vnd.github+json")
            when (val code = c.responseCode) {
                200 -> c.inputStream.bufferedReader().use { it.readText() }
                404 -> return null
                403, 429 -> throw UpdateException("GitHub rate limit reached. Try again later.")
                else -> throw UpdateException("Update server error ($code).")
            }
        } catch (e: UpdateException) { throw e } catch (e: java.io.IOException) {
            throw UpdateException("Could not reach the update server. Check your connection.")
        }
        return try {
            if (beta) {
                val arr = JSONArray(body)
                (0 until arr.length()).map { arr.getJSONObject(it) }.filter { !it.optBoolean("draft") }
                    .mapNotNull { parseRelease(it) }
                    .maxWithOrNull(compareBy { Semver.parse(it.version) })
            } else parseRelease(JSONObject(body))
        } catch (e: org.json.JSONException) { throw UpdateException("Unexpected response from the update server.") }
    }

    private fun parseRelease(o: JSONObject): ReleaseInfo? {
        val tag = o.optString("tag_name")
        if (Semver.parse(tag) == null) return null
        val assets = o.optJSONArray("assets") ?: return null
        var apk: String? = null; var sums: String? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val name = a.optString("name"); val dl = a.optString("browser_download_url")
            if (name.endsWith(".apk")) apk = dl else if (name == "checksums.txt") sums = dl
        }
        return apk?.let { ReleaseInfo(tag.removePrefix("v"), o.optString("body"), it, sums) }
    }
}

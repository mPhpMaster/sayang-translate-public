package com.sayang.translate

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Shared slang dictionary: dictionary.json in the public repo, which only its owner can edit.
 * Matching words go to Gemini as hints, so slang like "amicuuu" comes out as "I miss you".
 */
object Dictionary {
    private const val URL_RAW = "https://raw.githubusercontent.com/${Updater.REPO}/main/dictionary.json"
    private const val REFRESH_MS = 6 * 60 * 60 * 1000L
    private const val TIMEOUT_MS = 15_000
    private val WORD = Regex("""[\p{L}\p{M}']+""")

    @Volatile
    private var entries: Map<String, String>? = null

    suspend fun hintsFor(context: Context, lines: List<String>): Map<String, String> {
        val dictionary = load(context)
        if (dictionary.isEmpty()) return emptyMap()
        return lines.flatMap { WORD.findAll(it).map { match -> match.value } }
            .mapNotNull { word -> dictionary[normalize(word)]?.let { word to it } }
            .toMap()
    }

    /** Lowercase with repeated letters squeezed, so "Amicuuuu" and "amicu" are the same word. */
    fun normalize(word: String): String {
        val lower = word.lowercase()
        val out = StringBuilder()
        for (c in lower) if (out.isEmpty() || out.last() != c) out.append(c)
        return out.toString()
    }

    fun parse(json: String): Map<String, String> {
        val words = JSONObject(json).optJSONObject("words") ?: return emptyMap()
        return words.keys().asSequence().associate { normalize(it) to words.getString(it) }
    }

    private suspend fun load(context: Context): Map<String, String> = withContext(Dispatchers.IO) {
        val cache = File(context.filesDir, "dictionary.json")
        val stale = System.currentTimeMillis() - cache.lastModified() > REFRESH_MS
        if (!cache.exists() || stale) {
            try {
                cache.writeText(download())
                entries = null
            } catch (_: IOException) {
                // Keep using the last copy until the next try.
            }
        }
        entries ?: try {
            parse(cache.readText()).also { entries = it }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun download(): String {
        val connection = URL(URL_RAW).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("Dictionary returned ${connection.responseCode}")
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}

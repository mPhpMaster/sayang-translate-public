package com.sayang.translate

import android.content.Context
import com.google.mlkit.common.MlKitException
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONException
import java.io.IOException

data class Outcome(val items: List<Translation>, val offline: Boolean)

object ChatTranslator {
    private const val OFFLINE_TIMEOUT_MS = 120_000L

    /** Gemini first, then the offline packs. Null means neither could run. */
    suspend fun translate(context: Context, lines: List<String>, skip: Set<String>, target: String): Outcome? {
        val apiKey = Prefs(context).apiKey
        if (apiKey.isNotBlank()) {
            try {
                val hints = Dictionary.hintsFor(context, lines)
                return Outcome(GeminiClient(apiKey).translateLines(lines, skip, target, hints), offline = false)
            } catch (_: IOException) {
                // No internet or daily limit reached: the offline packs take over.
            } catch (_: JSONException) {
            }
        }
        // Fetching a missing pack with no internet can wait forever.
        return withTimeoutOrNull(OFFLINE_TIMEOUT_MS) {
            try {
                Outcome(OfflineTranslator.translate(lines, skip, target), offline = true)
            } catch (_: MlKitException) {
                null
            }
        }
    }
}

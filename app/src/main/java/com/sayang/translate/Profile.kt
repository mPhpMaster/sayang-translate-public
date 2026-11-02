package com.sayang.translate

import android.content.Context
import com.google.mlkit.nl.translate.TranslateLanguage
import java.util.Locale

/** Languages are BCP-47 codes, the ones ML Kit can translate offline. */
object Languages {
    val all: List<String> by lazy { TranslateLanguage.getAllLanguages().sortedBy(::label) }

    fun label(code: String): String = Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH)

    // The language detector often reports Indonesian as Malay.
    fun normalize(code: String): String = if (code == "ms") "id" else code

    fun deviceDefault(): String =
        normalize(Locale.getDefault().language).takeIf { it in all } ?: "en"
}

class Prefs(context: Context) {
    private val store = context.getSharedPreferences("sayang", Context.MODE_PRIVATE)

    init {
        migrateFromPersonalVersion()
    }

    /** The person's own language: the first button mode and the first notification line. */
    var myLanguage: String?
        get() = store.getString("my_language", null)
        set(value) = store.edit().putString("my_language", value).apply()

    /** The second button mode and the second notification line. */
    var secondLanguage: String
        get() = store.getString("second_language", "en")!!
        set(value) = store.edit().putString("second_language", value).apply()

    /** When on, text in the person's own language is never translated, even into the second language. */
    var keepMyLanguage: Boolean
        get() = store.getBoolean("keep_my_language", true)
        set(value) = store.edit().putBoolean("keep_my_language", value).apply()

    var apiKey: String
        get() = store.getString("api_key", "").orEmpty()
        set(value) = store.edit().putString("api_key", value.trim()).apply()

    var buttonHidden: Boolean
        get() = store.getBoolean("button_hidden", false)
        set(value) = store.edit().putBoolean("button_hidden", value).apply()

    /** Languages the chat button leaves alone when translating into [target]. */
    fun buttonSkips(target: String): Set<String> {
        val mine = myLanguage ?: return setOf(target)
        return if (keepMyLanguage) setOf(target, mine) else setOf(target)
    }

    /** Notifications only translate languages the person reads in neither mode. */
    fun notificationSkips(): Set<String> = setOfNotNull(myLanguage, secondLanguage)

    // Version 9 and older stored a fixed person instead of languages.
    private fun migrateFromPersonalVersion() {
        val person = store.getString("person", null) ?: return
        store.edit()
            .putString("my_language", if (person == "LAYLA") "id" else "ar")
            .putString("second_language", "en")
            .putBoolean("keep_my_language", person != "LAYLA")
            .remove("person")
            .apply()
    }
}

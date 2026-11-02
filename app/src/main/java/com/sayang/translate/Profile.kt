package com.sayang.translate

import android.content.Context
import com.google.mlkit.nl.translate.TranslateLanguage
import java.util.Locale

/** Languages are BCP-47 codes, the ones ML Kit can translate offline. */
object Languages {
    val all: List<String> by lazy { TranslateLanguage.getAllLanguages().sortedBy(::label) }

    /** English names go to Gemini; the screens pass the app's own locale. */
    fun label(code: String, locale: Locale = Locale.ENGLISH): String =
        Locale.forLanguageTag(code).getDisplayLanguage(locale).replaceFirstChar { it.titlecase(locale) }

    fun uiLocale(context: Context): Locale = context.resources.configuration.locales[0]

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

    var privacyAccepted: Boolean
        get() = store.getBoolean("privacy_accepted", false)
        set(value) = store.edit().putBoolean("privacy_accepted", value).apply()

    /** Free text such as "Gulf" or "Egyptian", applied when translating into [myLanguage]. */
    var dialect: String
        get() = store.getString("dialect", "").orEmpty()
        set(value) = store.edit().putString("dialect", value.trim()).apply()

    var notificationApps: Set<String>
        get() = store.getStringSet("notification_apps", null)?.toSet() ?: AppLists.DEFAULT_NOTIFICATION_APPS
        set(value) = store.edit().putStringSet("notification_apps", value).apply()

    // Only choices that differ from the default are stored, so new money apps stay blocked.
    private var buttonBlocked: Set<String>
        get() = store.getStringSet("button_blocked", emptySet())!!.toSet()
        set(value) = store.edit().putStringSet("button_blocked", value).apply()

    private var buttonAllowed: Set<String>
        get() = store.getStringSet("button_allowed", emptySet())!!.toSet()
        set(value) = store.edit().putStringSet("button_allowed", value).apply()

    fun isButtonBlocked(packageName: String, label: String): Boolean =
        packageName in buttonBlocked ||
            (AppLists.looksSensitive(packageName, label) && packageName !in buttonAllowed)

    fun setButtonAllowed(packageName: String, label: String, allowed: Boolean) {
        val sensitive = AppLists.looksSensitive(packageName, label)
        buttonAllowed = if (allowed && sensitive) buttonAllowed + packageName else buttonAllowed - packageName
        buttonBlocked = if (!allowed && !sensitive) buttonBlocked + packageName else buttonBlocked - packageName
    }

    /** How Gemini should name the language it writes in, e.g. "Arabic (Gulf dialect)". */
    fun targetName(target: String): String {
        val name = Languages.label(target)
        return if (target == myLanguage && dialect.isNotBlank()) "$name ($dialect dialect)" else name
    }

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
            .putBoolean("privacy_accepted", true)
            .putString("dialect", if (person == "LAYLA") "" else "Gulf")
            .putString("my_language", if (person == "LAYLA") "id" else "ar")
            .putString("second_language", "en")
            .putBoolean("keep_my_language", person != "LAYLA")
            .remove("person")
            .apply()
    }
}

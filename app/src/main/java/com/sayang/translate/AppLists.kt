package com.sayang.translate

import android.content.Context
import android.content.Intent

data class InstalledApp(val packageName: String, val label: String)

/** Which apps get the round button and which apps' notifications get translated. */
object AppLists {
    val DEFAULT_NOTIFICATION_APPS = setOf(
        "com.whatsapp",
        "com.whatsapp.w4b",
        "org.telegram.messenger",
        "org.telegram.messenger.web",
    )

    // Money and password apps are left out by default, so their screens never leave the phone.
    private val SENSITIVE = listOf(
        "bank", "banque", "pay", "wallet", "finance", "money", "card", "invest", "trade", "crypto",
        "rajhi", "alinma", "albilad", "riyad", "snb", "ncb", "sabb", "anb", "mada", "stc",
        "mandiri", "bca", "bni", "bri", "jenius", "ovo", "gopay", "dana",
        "password", "pass", "vault", "keepass", "bitwarden", "authenticator", "2fa", "otp",
    )

    fun looksSensitive(packageName: String, label: String): Boolean {
        val text = "$packageName $label".lowercase()
        return SENSITIVE.any { it in text }
    }

    fun installed(context: Context): List<InstalledApp> {
        val pm = context.packageManager
        return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { InstalledApp(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    fun label(context: Context, packageName: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (_: Exception) {
        packageName
    }
}

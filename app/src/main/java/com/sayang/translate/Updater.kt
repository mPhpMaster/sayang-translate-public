package com.sayang.translate

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Checks the public GitHub repo's latest release and installs it when it's newer. No token needed. */
object Updater {
    const val REPO = "mPhpMaster/sayang-translate-public"
    private const val CHANNEL = "updates"
    private const val NOTIFICATION_ID = 7001
    private const val CHECK_EVERY_MS = 6 * 60 * 60 * 1000L
    private const val TIMEOUT_MS = 30_000

    data class Release(val version: String, val downloadUrl: String)

    /** Background check, at most every few hours. Errors are ignored until the next try. */
    suspend fun checkQuietly(context: Context) {
        val store = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - store.getLong("last_check", 0) < CHECK_EVERY_MS) return
        store.edit().putLong("last_check", now).apply()
        try {
            checkAndInstall(context)
        } catch (_: IOException) {
        } catch (_: org.json.JSONException) {
        }
    }

    /** Returns a short status line for the settings screen. */
    suspend fun checkAndInstall(context: Context): String {
        val release = latest() ?: return "No release found."
        if (!isNewer(release.version, BuildConfig.VERSION_NAME)) return "You have the latest version."
        if (!context.packageManager.canRequestPackageInstalls()) {
            notify(
                context,
                "Allow updates",
                "Version ${release.version} is ready. Tap to allow Sayang Translate to install updates.",
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")),
            )
            return "Allow updates first, then check again."
        }
        install(context, download(context, release))
        return "Installing version ${release.version}…"
    }

    fun isNewer(remote: String, local: String): Boolean {
        val a = remote.split('.').map { it.toIntOrNull() ?: 0 }
        val b = local.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val diff = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
            if (diff != 0) return diff > 0
        }
        return false
    }

    private suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        val connection = open("https://api.github.com/repos/$REPO/releases/latest", "application/vnd.github+json")
        try {
            when (connection.responseCode) {
                HttpURLConnection.HTTP_OK -> Unit
                HttpURLConnection.HTTP_NOT_FOUND -> return@withContext null
                else -> throw IOException("GitHub returned ${connection.responseCode}")
            }
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val assets = json.getJSONArray("assets")
            val apk = (0 until assets.length()).map(assets::getJSONObject)
                .firstOrNull { it.getString("name").endsWith(".apk") } ?: return@withContext null
            Release(json.getString("tag_name").removePrefix("v"), apk.getString("browser_download_url"))
        } finally {
            connection.disconnect()
        }
    }

    // Public release files redirect to GitHub's storage, which HttpURLConnection follows.
    private suspend fun download(context: Context, release: Release): File = withContext(Dispatchers.IO) {
        val file = File(context.cacheDir, "update.apk")
        val connection = open(release.downloadUrl, "application/octet-stream")
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("Download returned ${connection.responseCode}")
            }
            connection.inputStream.use { input -> file.outputStream().use(input::copyTo) }
        } finally {
            connection.disconnect()
        }
        file
    }

    private fun open(url: String, accept: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = TIMEOUT_MS
        readTimeout = TIMEOUT_MS
        setRequestProperty("Accept", accept)
        setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
    }

    private suspend fun install(context: Context, apk: File) = withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        // Updates install without asking once this app is the one that installed itself.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("update.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val result = PendingIntent.getBroadcast(
                context,
                sessionId,
                Intent(context, UpdateReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(result.intentSender)
        }
    }

    fun notify(context: Context, title: String, text: String, open: Intent?) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Updates", NotificationManager.IMPORTANCE_HIGH),
        )
        val builder = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
        open?.let {
            builder.setContentIntent(
                PendingIntent.getActivity(
                    context,
                    0,
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }
        manager.notify(NOTIFICATION_ID, builder.build())
    }
}

/** Hears back from the installer. A tap is needed when Android wants the person to confirm. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                Updater.notify(context, "Update ready", "Tap to install the new version.", confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> Updater.notify(
                context,
                "Update failed",
                intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Try again later.",
                null,
            )
        }
    }
}

/** Some phones switch accessibility services off when an app updates; this asks to switch it back on. */
class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (Prefs(context).myLanguage == null) return
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty()
            .contains("${context.packageName}/")
        if (!enabled) {
            Updater.notify(
                context,
                "Turn the chat button back on",
                "The update switched off the round translate button. Tap to turn it on again.",
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
            )
        }
    }
}

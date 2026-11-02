package com.sayang.translate

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.IOException

class MainActivity : Activity() {

    private val scope = MainScope()
    private lateinit var prefs: Prefs
    private var editing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        render()
    }

    private fun render() {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        val first = prefs.myLanguage
        if (first == null || editing) renderSetup(page) else renderSettings(page, first)
        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            addView(page)
        })
    }

    private fun renderSetup(page: LinearLayout) {
        page.addView(text("Set up Sayang Translate", 28f))

        page.addView(heading("Your language"))
        val first = languagePicker(prefs.myLanguage ?: Languages.deviceDefault())
        page.addView(first)

        page.addView(heading("Second language"))
        page.addView(text("The round button switches between your language, this one, and Off.", 15f))
        val second = languagePicker(prefs.secondLanguage)
        page.addView(second)

        val keep = CheckBox(this).apply {
            text = "Never translate text that's already in my language"
            isChecked = prefs.keepMyLanguage
        }
        page.addView(keep)

        page.addView(heading("Gemini key"))
        page.addView(text(
            "Free from Google. It gives the best translations and understands slang. " +
                "Without one, translation runs on the phone and is weaker. Your text goes to Google.",
            15f,
        ))
        page.addView(button("Get a free key") {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey")))
        })
        val key = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "Paste your key"
            setText(prefs.apiKey)
        }
        page.addView(key)

        page.addView(button("Save") {
            prefs.myLanguage = Languages.all[first.selectedItemPosition]
            prefs.secondLanguage = Languages.all[second.selectedItemPosition]
            prefs.keepMyLanguage = keep.isChecked
            prefs.apiKey = key.text.toString()
            editing = false
            render()
            downloadPacks(prefs.myLanguage!!, prefs.secondLanguage)
        })
    }

    private fun languagePicker(selected: String) = Spinner(this).apply {
        adapter = ArrayAdapter(
            this@MainActivity,
            android.R.layout.simple_spinner_dropdown_item,
            Languages.all.map(Languages::label),
        )
        setSelection(Languages.all.indexOf(selected).coerceAtLeast(0))
    }

    private fun renderSettings(page: LinearLayout, first: String) {
        val second = prefs.secondLanguage
        page.addView(text("Sayang Translate", 28f))
        page.addView(text(
            "New messages in WhatsApp and Telegram arrive as a notification in " +
                "${Languages.label(first)} and ${Languages.label(second)}.",
            15f,
        ))
        if (prefs.apiKey.isBlank()) {
            page.addView(text("No Gemini key yet, so translation runs on the phone and is weaker.", 15f))
        }
        page.addView(button("Change languages or key") {
            editing = true
            render()
        })

        if (needsNotificationPermission()) {
            page.addView(heading("Step 1: Show translations"))
            page.addView(button("Allow notifications") {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
            })
        }

        page.addView(heading(if (hasNotificationAccess()) "Ready" else "Step 2: Read chat notifications"))
        if (hasNotificationAccess()) {
            page.addView(text("You're all set. Nothing else is needed.", 15f))
        } else {
            page.addView(text(
                "Turn on Sayang Translate. If it's greyed out, open App info, tap ⋮ at the top, " +
                    "choose Allow restricted settings, then try again.",
                15f,
            ))
            page.addView(button("Turn on notification access") {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            })
            page.addView(appInfoButton())
        }

        page.addView(heading("Translate anything on screen"))
        if (TranslateService.instance != null) {
            page.addView(text(
                "Tap the small round button in any app. Each tap switches between " +
                    "${Languages.label(first)}, ${Languages.label(second)} and Off. " +
                    "It goes back to Off when you change apps.",
                15f,
            ))
        } else {
            page.addView(text(
                "Turn on Sayang Translate here, and a small button appears in every app.",
                15f,
            ))
            page.addView(button("Turn on chat button") {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            })
        }

        page.addView(heading("Hide the button"))
        page.addView(text(
            "Hold the round button to hide it. Tap Translate in the notification panel to show or hide it.",
            15f,
        ))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            page.addView(button("Add Translate to the panel") { requestTile() })
        } else {
            page.addView(text("Pull down the panel, tap the pencil icon, and drag Translate up.", 15f))
        }

        page.addView(heading("Updates"))
        val status = text("Version ${BuildConfig.VERSION_NAME}. New versions install by themselves.", 15f)
        page.addView(status)
        if (!packageManager.canRequestPackageInstalls()) {
            page.addView(button("Allow updates") {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")),
                )
            })
        }
        page.addView(button("Check for updates") {
            status.text = "Checking and downloading. This can take a minute…"
            scope.launch {
                status.text = try {
                    Updater.checkAndInstall(this@MainActivity)
                } catch (e: IOException) {
                    "Couldn't check. Check your internet."
                } catch (e: org.json.JSONException) {
                    "Couldn't read the update information."
                }
            }
        })
    }

    private fun requestTile() {
        getSystemService(StatusBarManager::class.java).requestAddTileService(
            ComponentName(this, TranslateTileService::class.java),
            getString(R.string.tile_label),
            Icon.createWithResource(this, R.drawable.ic_tile),
            mainExecutor,
        ) { }
    }

    private fun needsNotificationPermission() =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

    private fun hasNotificationAccess() = getSystemService(NotificationManager::class.java)
        .isNotificationListenerAccessGranted(ComponentName(this, ChatListener::class.java))

    private fun appInfoButton() = button("Open App info") {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:$packageName")),
        )
    }

    private fun downloadPacks(first: String, second: String) {
        scope.launch {
            try {
                OfflineTranslator.downloadPacks(first, second)
            } catch (e: IOException) {
                Toast.makeText(this@MainActivity, DOWNLOAD_FAILED, Toast.LENGTH_LONG).show()
            } catch (e: com.google.mlkit.common.MlKitException) {
                Toast.makeText(this@MainActivity, DOWNLOAD_FAILED, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun text(value: String, size: Float) = TextView(this).apply {
        text = value
        textSize = size
        setPadding(0, 0, 0, dp(8))
    }

    private fun heading(value: String) = text(value, 19f).apply { setPadding(0, dp(24), 0, dp(8)) }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val DOWNLOAD_FAILED = "Language pack download failed. Check your internet."
    }
}

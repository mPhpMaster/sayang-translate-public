package com.sayang.translate

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
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

private const val DONATE_URL = "https://paypal.me/mfsafadi"

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
        when {
            !prefs.privacyAccepted -> renderPrivacy(page)
            first == null || editing -> renderSetup(page)
            else -> renderSettings(page, first)
        }
        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            addView(page)
        })
    }

    private fun renderPrivacy(page: LinearLayout) {
        page.addView(text(getString(R.string.privacy_title), 28f))
        page.addView(text(getString(R.string.privacy_body), 16f))
        page.addView(button(getString(R.string.privacy_accept)) {
            prefs.privacyAccepted = true
            render()
        })
    }

    private fun renderSetup(page: LinearLayout) {
        page.addView(text(getString(R.string.setup_title), 28f))

        page.addView(heading(getString(R.string.my_language)))
        val first = languagePicker(prefs.myLanguage ?: Languages.deviceDefault())
        page.addView(first)

        page.addView(heading(getString(R.string.second_language)))
        page.addView(text(getString(R.string.second_language_hint), 15f))
        val second = languagePicker(prefs.secondLanguage)
        page.addView(second)

        val keep = CheckBox(this).apply {
            text = getString(R.string.keep_my_language)
            isChecked = prefs.keepMyLanguage
        }
        page.addView(keep)

        page.addView(heading(getString(R.string.dialect)))
        val dialect = EditText(this).apply {
            hint = getString(R.string.dialect_hint)
            setText(prefs.dialect)
        }
        page.addView(dialect)

        page.addView(heading(getString(R.string.gemini_key)))
        page.addView(text(getString(R.string.gemini_key_about), 15f))
        page.addView(button(getString(R.string.get_key)) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey")))
        })
        val key = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = getString(R.string.paste_key)
            setText(prefs.apiKey)
        }
        page.addView(key)

        page.addView(button(getString(R.string.save)) {
            prefs.myLanguage = Languages.all[first.selectedItemPosition]
            prefs.secondLanguage = Languages.all[second.selectedItemPosition]
            prefs.keepMyLanguage = keep.isChecked
            prefs.dialect = dialect.text.toString()
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
            Languages.all.map { Languages.label(it, Languages.uiLocale(this@MainActivity)) },
        )
        setSelection(Languages.all.indexOf(selected).coerceAtLeast(0))
    }

    private fun renderSettings(page: LinearLayout, first: String) {
        val locale = Languages.uiLocale(this)
        val firstLabel = Languages.label(first, locale)
        val secondLabel = Languages.label(prefs.secondLanguage, locale)

        page.addView(text(getString(R.string.app_name), 28f))
        page.addView(text(getString(R.string.summary, firstLabel, secondLabel), 15f))
        if (prefs.apiKey.isBlank()) page.addView(text(getString(R.string.no_key), 15f))
        page.addView(button(getString(R.string.change_settings)) {
            editing = true
            render()
        })

        if (needsNotificationPermission()) {
            page.addView(heading(getString(R.string.step_notifications)))
            page.addView(button(getString(R.string.allow_notifications)) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
            })
        }

        page.addView(heading(getString(if (hasNotificationAccess()) R.string.ready else R.string.step_access)))
        if (hasNotificationAccess()) {
            page.addView(text(getString(R.string.ready_body), 15f))
        } else {
            page.addView(text(getString(R.string.access_body), 15f))
            page.addView(button(getString(R.string.turn_on_access)) {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            })
            page.addView(appInfoButton())
        }

        page.addView(heading(getString(R.string.screen_title)))
        if (TranslateService.instance != null) {
            page.addView(text(getString(R.string.screen_on, firstLabel, secondLabel), 15f))
        } else {
            page.addView(text(getString(R.string.screen_off), 15f))
            page.addView(button(getString(R.string.turn_on_button)) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            })
        }

        page.addView(heading(getString(R.string.apps_title)))
        page.addView(text(getString(R.string.button_apps_hint), 15f))
        page.addView(button(getString(R.string.button_apps)) { pickButtonApps() })
        page.addView(button(getString(R.string.notification_apps)) { pickNotificationApps() })

        page.addView(heading(getString(R.string.hide_title)))
        page.addView(text(getString(R.string.hide_body), 15f))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            page.addView(button(getString(R.string.add_tile)) { requestTile() })
        } else {
            page.addView(text(getString(R.string.add_tile_manually), 15f))
        }

        page.addView(heading(getString(R.string.updates_title)))
        val status = text(getString(R.string.version, BuildConfig.VERSION_NAME), 15f)
        page.addView(status)
        if (!packageManager.canRequestPackageInstalls()) {
            page.addView(button(getString(R.string.allow_updates)) {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")),
                )
            })
        }
        page.addView(button(getString(R.string.check_updates)) {
            status.text = getString(R.string.checking)
            scope.launch {
                status.text = try {
                    Updater.checkAndInstall(this@MainActivity)
                } catch (e: IOException) {
                    getString(R.string.check_failed)
                } catch (e: org.json.JSONException) {
                    getString(R.string.check_unreadable)
                }
            }
        })

        page.addView(heading(getString(R.string.support_title)))
        page.addView(text(getString(R.string.support_body), 15f))
        page.addView(button(getString(R.string.donate)) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DONATE_URL)))
        })
    }

    private fun pickButtonApps() {
        val apps = AppLists.installed(this)
        val checked = apps.map { !prefs.isButtonBlocked(it.packageName, it.label) }.toBooleanArray()
        showAppPicker(R.string.button_apps, apps, checked) {
            apps.forEachIndexed { i, app -> prefs.setButtonAllowed(app.packageName, app.label, checked[i]) }
        }
    }

    private fun pickNotificationApps() {
        val apps = AppLists.installed(this)
        val chosen = prefs.notificationApps
        val checked = apps.map { it.packageName in chosen }.toBooleanArray()
        showAppPicker(R.string.notification_apps, apps, checked) {
            // Apps missing from the list (not installed now) keep their earlier choice.
            val listed = apps.map { it.packageName }.toSet()
            prefs.notificationApps = (chosen - listed) +
                apps.filterIndexed { i, _ -> checked[i] }.map { it.packageName }
        }
    }

    private fun showAppPicker(title: Int, apps: List<InstalledApp>, checked: BooleanArray, onSave: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMultiChoiceItems(apps.map { it.label }.toTypedArray(), checked) { _, i, on -> checked[i] = on }
            .setPositiveButton(R.string.save) { _, _ -> onSave() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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

    private fun appInfoButton() = button(getString(R.string.open_app_info)) {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:$packageName")),
        )
    }

    private fun downloadPacks(first: String, second: String) {
        scope.launch {
            try {
                OfflineTranslator.downloadPacks(first, second)
            } catch (e: IOException) {
                Toast.makeText(this@MainActivity, R.string.download_failed, Toast.LENGTH_LONG).show()
            } catch (e: com.google.mlkit.common.MlKitException) {
                Toast.makeText(this@MainActivity, R.string.download_failed, Toast.LENGTH_LONG).show()
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
}

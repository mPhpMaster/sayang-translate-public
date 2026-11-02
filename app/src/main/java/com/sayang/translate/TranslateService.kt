package com.sayang.translate

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Shows the mode button in any app and keeps translations on the visible text. */
class TranslateService : AccessibilityService() {

    private val scope: CoroutineScope = MainScope()
    private lateinit var overlay: LiveOverlay

    /** Null means translation is off. */
    private var target: String? = null
    private var foregroundCheck: Job? = null
    private var debounce: Job? = null
    private var running: Job? = null
    private var dirty = false
    private var failed = false
    private var lastScreen: List<ScreenText>? = null

    /** The app the button is showing over. */
    private var currentApp: String? = null
    private val launchers by lazy {
        packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .map { it.activityInfo.packageName }
            .toSet()
    }

    // Lines already answered, so scrolling never pays for the same message twice.
    private val cache = HashMap<Pair<String, String>, String>()
    private val skipped = HashSet<Pair<String, String>>()

    override fun onServiceConnected() {
        overlay = LiveOverlay(this, ::nextMode, ::toggleButton)
        instance = this
        // This service stays running, so it doubles as the update clock.
        scope.launch {
            while (true) {
                Updater.checkQuietly(this@TranslateService)
                delay(UPDATE_POLL_MS)
            }
        }
    }

    override fun onDestroy() {
        instance = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg == packageName) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> checkForeground()
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> if (target != null && pkg == currentApp) {
                overlay.clearLabels()
                lastScreen = null
                requestRefresh()
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ->
                if (target != null && pkg == currentApp) requestRefresh()
        }
    }

    /** Hides or brings back the button. Runs from the panel button or a long press. */
    fun toggleButton() {
        val prefs = Prefs(this)
        prefs.buttonHidden = !prefs.buttonHidden
        if (prefs.buttonHidden) {
            leaveApp()
        } else {
            // Close the panel, then the button appears over whatever app is underneath.
            performGlobalAction(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE
                } else {
                    GLOBAL_ACTION_BACK
                },
            )
            checkForeground()
        }
        TileService.requestListeningState(this, ComponentName(this, TranslateTileService::class.java))
    }

    /** Off, then the person's language, then their second language, then off again. */
    private fun nextMode() {
        val prefs = Prefs(this)
        val first = prefs.myLanguage ?: return
        val second = prefs.secondLanguage
        target = when (target) {
            null -> first
            first -> second.takeIf { it != first }
            else -> null
        }
        failed = false
        lastScreen = null
        overlay.clearLabels()
        updateButton()
        if (target != null) requestRefresh()
    }

    private fun updateButton(busy: Boolean = false) {
        overlay.showButton(
            when {
                busy -> "…"
                failed -> "!"
                else -> target?.uppercase() ?: "Off"
            },
        )
    }

    private fun checkForeground() {
        foregroundCheck?.cancel()
        foregroundCheck = scope.launch {
            delay(FOREGROUND_DELAY_MS)
            val pkg = rootInActiveWindow?.packageName?.toString() ?: return@launch
            when {
                pkg == SYSTEM_UI -> Unit
                Prefs(this@TranslateService).buttonHidden -> leaveApp()
                pkg == packageName || pkg in launchers -> leaveApp()
                else -> {
                    // A new app starts at Off, so nothing on screen leaves the phone until asked.
                    if (pkg != currentApp) {
                        leaveApp()
                        currentApp = pkg
                    }
                    if (Prefs(this@TranslateService).myLanguage != null) updateButton()
                    if (target != null) requestRefresh()
                }
            }
        }
    }

    private fun leaveApp() {
        currentApp = null
        target = null
        failed = false
        lastScreen = null
        overlay.hideButton()
        overlay.clearLabels()
    }

    private fun requestRefresh() {
        debounce?.cancel()
        debounce = scope.launch {
            delay(REFRESH_DELAY_MS)
            if (running?.isActive == true) {
                dirty = true
            } else {
                running = scope.launch { refresh() }
            }
        }
    }

    private suspend fun refresh() {
        val lang = target ?: return
        val screen = readChat() ?: return
        if (screen != lastScreen) {
            translateMissing(screen, lang)
            // The chat may have moved while waiting for the answer.
            val current = readChat().orEmpty()
            if (lang == target) {
                overlay.showLabels(
                    current.mapNotNull { item -> cache[lang to item.text]?.let { item.bounds to it } },
                )
                lastScreen = current
            }
        }
        if (dirty) {
            dirty = false
            requestRefresh()
        }
    }

    private fun readChat(): List<ScreenText>? {
        val root = rootInActiveWindow ?: return null
        if (root.packageName?.toString() != currentApp) return null
        return ScreenReader.read(root)
    }

    private suspend fun translateMissing(screen: List<ScreenText>, lang: String) {
        val prefs = Prefs(this)
        val skip = prefs.buttonSkips(lang)
        val unknown = screen.map { it.text }.distinct()
            .filter { (lang to it) !in cache && (lang to it) !in skipped }
        // Text that needs no translation never leaves the phone.
        val missing = unknown.filter { line ->
            OfflineTranslator.mightNeedTranslation(line, skip).also { if (!it) skipped += lang to line }
        }
        if (missing.isEmpty()) return

        // Long pages go in batches; the next refresh picks up the rest.
        val batch = missing.take(MAX_LINES_PER_REQUEST)
        if (missing.size > batch.size) dirty = true
        updateButton(busy = true)
        val outcome = ChatTranslator.translate(this, batch, skip, lang)
        failed = outcome == null
        if (outcome != null) {
            outcome.items.forEach { cache[lang to it.original] = it.translated }
            skipped += batch.filter { (lang to it) !in cache }.map { lang to it }
        }
        if (cache.size > MAX_CACHE) cache.clear()
        if (skipped.size > MAX_CACHE) skipped.clear()
        updateButton()
    }

    companion object {
        private const val SYSTEM_UI = "com.android.systemui"
        private const val FOREGROUND_DELAY_MS = 300L
        private const val REFRESH_DELAY_MS = 500L
        private const val MAX_CACHE = 1000
        private const val MAX_LINES_PER_REQUEST = 60
        private const val UPDATE_POLL_MS = 30 * 60 * 1000L

        var instance: TranslateService? = null
            private set
    }
}

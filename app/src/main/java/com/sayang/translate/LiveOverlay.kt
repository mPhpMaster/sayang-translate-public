package com.sayang.translate

import android.accessibilityservice.AccessibilityService
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.abs

/** Draws translations on top of their chat bubbles, plus the small mode button. */
class LiveOverlay(
    private val service: AccessibilityService,
    private val onButtonTap: () -> Unit,
    private val onButtonHold: () -> Unit,
) {
    private val windows = service.getSystemService(WindowManager::class.java)
    private var labels: FrameLayout? = null
    private var button: TextView? = null

    private val dark: Boolean
        get() = service.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES

    fun showButton(text: String) {
        val view = button ?: TextView(service).apply {
            gravity = Gravity.CENTER
            textSize = 14f
            setTextColor(Color.WHITE)
            minWidth = dp(42)
            minHeight = dp(42)
            alpha = 0.9f
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ACCENT)
            }
            elevation = dp(4).toFloat()
            val params = buttonParams()
            setOnTouchListener(dragOrTap(params))
            windows.addView(this, params)
            button = this
        }
        view.text = text
    }

    // Dragging moves the button up or down, so it never has to sit on a message. Holding hides it.
    private fun dragOrTap(params: WindowManager.LayoutParams): View.OnTouchListener {
        var startY = 0
        var startTouch = 0f
        var startTime = 0L
        var dragged = false
        return View.OnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startY = params.y
                    startTouch = event.rawY
                    startTime = event.eventTime
                    dragged = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val moved = (event.rawY - startTouch).toInt()
                    if (abs(moved) > dp(8)) dragged = true
                    if (dragged) {
                        params.y = startY + moved
                        windows.updateViewLayout(view, params)
                    }
                }
                MotionEvent.ACTION_UP -> when {
                    dragged -> Unit
                    event.eventTime - startTime >= HOLD_MS -> onButtonHold()
                    else -> onButtonTap()
                }
            }
            true
        }
    }

    fun hideButton() {
        button?.let(windows::removeView)
        button = null
    }

    fun showLabels(items: List<Pair<Rect, String>>) {
        val root = labels ?: FrameLayout(service).also {
            windows.addView(it, labelsParams())
            labels = it
            // The newest window sits on top, so the button has to be added after the labels.
            button?.let { view ->
                val params = view.layoutParams
                windows.removeView(view)
                windows.addView(view, params)
            }
        }
        root.removeAllViews()
        items.forEach { (bounds, text) -> root.addView(label(text, bounds), position(bounds)) }
    }

    fun clearLabels() {
        labels?.let(windows::removeView)
        labels = null
    }

    // Never smaller than the original, so no half-hidden words show around it.
    private fun label(text: String, bounds: Rect) = TextView(service).apply {
        this.text = text
        minWidth = bounds.width()
        minHeight = bounds.height()
        textSize = 15f
        setTextColor(if (dark) Color.WHITE else Color.rgb(32, 33, 36))
        textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        setPadding(dp(8), dp(4), dp(8), dp(4))
        maxWidth = screenWidth * 85 / 100
        background = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(if (dark) Color.rgb(74, 40, 56) else Color.rgb(255, 228, 238))
        }
    }

    // Stays anchored to the bubble's nearer screen edge, so long text grows into the chat.
    private fun position(bounds: Rect) = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.WRAP_CONTENT,
        FrameLayout.LayoutParams.WRAP_CONTENT,
    ).apply {
        topMargin = bounds.top
        if (bounds.centerX() > screenWidth / 2) {
            gravity = Gravity.TOP or Gravity.END
            rightMargin = (screenWidth - bounds.right).coerceAtLeast(0)
        } else {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = bounds.left.coerceAtLeast(0)
        }
    }

    // Labels ignore touches, so the chat underneath still scrolls and responds.
    private fun labelsParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }

    private fun buttonParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.END
        x = dp(8)
        y = service.resources.displayMetrics.heightPixels * 15 / 100
    }

    private val screenWidth get() = service.resources.displayMetrics.widthPixels

    private fun dp(value: Int) = (value * service.resources.displayMetrics.density).toInt()

    private companion object {
        val ACCENT = Color.rgb(214, 51, 108)
        const val HOLD_MS = 600L
    }
}

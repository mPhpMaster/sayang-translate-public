package com.sayang.translate

import android.accessibilityservice.AccessibilityService
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.text.TextPaint
import android.util.TypedValue
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
        // A message scrolled almost out of sight would only leave a thin strip.
        items.filter { (bounds, _) -> bounds.height() >= dp(MIN_HEIGHT_DP) }.forEachIndexed { index, (bounds, text) ->
            root.addView(label(text, index), position(bounds, labelWidth(text, bounds)))
        }
    }

    fun clearLabels() {
        labels?.let(windows::removeView)
        labels = null
    }

    // Neighbouring labels take turns between two colours, so it's clear where one ends.
    private fun label(text: String, index: Int) = TextView(service).apply {
        this.text = text
        setTextColor(if (dark) Color.WHITE else Color.rgb(32, 33, 36))
        textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), dp(2), dp(8), dp(2))
        // A longer translation shrinks to fit its message instead of spilling onto the next one.
        setAutoSizeTextTypeUniformWithConfiguration(MIN_TEXT_SP, TEXT_SP, 1, TypedValue.COMPLEX_UNIT_SP)
        val colors = if (dark) DARK_COLORS else LIGHT_COLORS
        background = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(colors[index % colors.size].first)
            setStroke(dp(1), colors[index % colors.size].second)
        }
    }

    // As wide as the text wants at full size, never narrower than the original, at most 85% of the screen.
    private fun labelWidth(text: String, bounds: Rect): Int {
        val paint = TextPaint().apply {
            textSize = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                TEXT_SP.toFloat(),
                service.resources.displayMetrics,
            )
        }
        val wanted = paint.measureText(text).toInt() + dp(16) + 1
        return wanted.coerceAtMost(screenWidth * 85 / 100).coerceAtLeast(bounds.width())
    }

    // Exactly as tall as the original, anchored to the bubble's nearer screen edge.
    private fun position(bounds: Rect, width: Int) = FrameLayout.LayoutParams(width, bounds.height()).apply {
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
        const val TEXT_SP = 15
        const val MIN_TEXT_SP = 8
        const val MIN_HEIGHT_DP = 14

        // Fill and outline pairs: rose and blue.
        val DARK_COLORS = listOf(
            Color.rgb(74, 40, 56) to Color.rgb(140, 70, 100),
            Color.rgb(30, 52, 78) to Color.rgb(70, 110, 160),
        )
        val LIGHT_COLORS = listOf(
            Color.rgb(255, 228, 238) to Color.rgb(230, 150, 180),
            Color.rgb(222, 236, 255) to Color.rgb(140, 175, 225),
        )
    }
}

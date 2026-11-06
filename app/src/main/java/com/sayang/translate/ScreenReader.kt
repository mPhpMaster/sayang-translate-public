package com.sayang.translate

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

data class ScreenText(val text: String, val bounds: Rect)

object ScreenReader {
    private const val MAX_DEPTH = 40
    private val TIME = Regex("""^\d{1,2}[:.]\d{2}(\s?[AaPp]\.?[Mm]\.?)?$""")

    /** Visible text in the window with its position, top to bottom. Input boxes are skipped. */
    fun read(root: AccessibilityNodeInfo): List<ScreenText> {
        val found = mutableListOf<ScreenText>()
        walk(root, found, 0)
        return found.filter { isMessage(it.text) }.distinct().sortedBy { it.bounds.top }
    }

    private fun walk(node: AccessibilityNodeInfo, found: MutableList<ScreenText>, depth: Int) {
        if (depth > MAX_DEPTH || !node.isVisibleToUser || node.isPassword) return
        if (!node.isEditable) {
            val text = node.text?.toString().orEmpty().ifBlank {
                // Telegram draws its bubbles itself and only describes them. Elsewhere descriptions
                // belong to icons such as ticks, Voice call or Camera, which aren't messages.
                if (node.childCount == 0 && describesMessages(node)) node.contentDescription?.toString().orEmpty() else ""
            }
            if (text.isNotBlank()) found += ScreenText(text.trim(), Rect().also(node::getBoundsInScreen))
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { walk(it, found, depth + 1) }
        }
    }

    private fun describesMessages(node: AccessibilityNodeInfo): Boolean {
        val pkg = node.packageName?.toString().orEmpty()
        return TELEGRAM_APPS.any(pkg::startsWith)
    }

    private val TELEGRAM_APPS = listOf("org.telegram.", "org.thunderdog.challegram")

    fun isMessage(line: String) = line.count(Char::isLetter) >= 2 && !TIME.matches(line)

    fun clean(lines: List<String>): List<String> = lines.map(String::trim).filter(::isMessage).distinct()
}

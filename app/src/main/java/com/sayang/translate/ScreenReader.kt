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
                // Telegram draws its bubbles itself and only describes them.
                if (node.childCount == 0) node.contentDescription?.toString().orEmpty() else ""
            }
            if (text.isNotBlank()) found += ScreenText(text.trim(), Rect().also(node::getBoundsInScreen))
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { walk(it, found, depth + 1) }
        }
    }

    fun isMessage(line: String) = line.count(Char::isLetter) >= 2 && !TIME.matches(line)

    fun clean(lines: List<String>): List<String> = lines.map(String::trim).filter(::isMessage).distinct()
}

package com.sayang.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogicTest {

    @Test
    fun cleanDropsTimesNoiseAndDuplicates() {
        val lines = listOf("  Aku sayang kamu ", "10:32 PM", "22.15", "ok", "!", "Aku sayang kamu", "😀")
        assertEquals(listOf("Aku sayang kamu", "ok"), ScreenReader.clean(lines))
    }

    @Test
    fun arabicDetection() {
        assertTrue(OfflineTranslator.hasArabic("كيف حالك"))
        assertFalse(OfflineTranslator.hasArabic("Apa kabar"))
    }

    @Test
    fun malayCountsAsIndonesian() {
        assertEquals("id", Languages.normalize("ms"))
        assertEquals("en", Languages.normalize("en"))
    }

    @Test
    fun dictionaryIgnoresCaseAndStretchedLetters() {
        assertEquals("amicu", Dictionary.normalize("Amicuuuuuu"))
        assertEquals("احبك", Dictionary.normalize("احبكك"))
        val words = Dictionary.parse("""{"words":{"Amicu":"I miss you"}}""")
        assertEquals("I miss you", words[Dictionary.normalize("AMICUUU")])
    }

    @Test
    fun comparesVersions() {
        assertTrue(Updater.isNewer("10.0", "9.2"))
        assertTrue(Updater.isNewer("10.1", "10.0"))
        assertFalse(Updater.isNewer("10.0", "10.0"))
        assertFalse(Updater.isNewer("9.9", "10.0"))
    }

    @Test
    fun parsesGeminiAnswer() {
        val answer = """{"candidates":[{"content":{"parts":[{"text":"[{\"index\":2,\"translated\":\"كيف حالك\"}]"}]}}]}"""
        assertEquals(listOf(2 to "كيف حالك"), GeminiClient.parseResponse(answer))
    }
}

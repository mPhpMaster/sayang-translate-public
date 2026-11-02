package com.sayang.translate

import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation as MlKitTranslation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await

object OfflineTranslator {
    private const val UNDETERMINED = "und"

    // A strict threshold, so slang isn't mistaken for a language that gets skipped.
    private const val CONFIDENCE = 0.8f

    private val detector by lazy {
        LanguageIdentification.getClient(
            LanguageIdentificationOptions.Builder().setConfidenceThreshold(CONFIDENCE).build(),
        )
    }

    suspend fun translate(lines: List<String>, skip: Set<String>, target: String): List<Translation> {
        val bySource = lines.groupBy { Languages.normalize(detect(it)) }
            .filterKeys { it !in skip && TranslateLanguage.fromLanguageTag(it) != null }
        return bySource.flatMap { (source, group) ->
            val translator = translator(source, target)
            try {
                translator.downloadModelIfNeeded().await()
                group.map { Translation(it, translator.translate(it).await()) }
            } finally {
                translator.close()
            }
        }
    }

    /** Fetches both chosen languages, so the backup works later with no internet. */
    suspend fun downloadPacks(first: String, second: String) {
        val translator = translator(if (first == second) "en" else second, first)
        try {
            translator.downloadModelIfNeeded().await()
        } finally {
            translator.close()
        }
    }

    /** Looser check for what may go to Gemini: slang and short messages often can't be detected here. */
    suspend fun mightNeedTranslation(line: String, skip: Set<String>): Boolean {
        val code = Languages.normalize(detect(line))
        return code == UNDETERMINED || code !in skip
    }

    private fun translator(source: String, target: String): Translator = MlKitTranslation.getClient(
        TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage(target).build(),
    )

    private suspend fun detect(line: String): String =
        if (hasArabic(line)) "ar" else detector.identifyLanguage(line).await()

    fun hasArabic(line: String) = line.any { it in '؀'..'ۿ' }
}

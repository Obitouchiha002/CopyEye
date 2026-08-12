package com.copyeye.app.ocr

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Hindi ⇄ English, on the device.
 *
 * One caveat worth stating plainly, because the rest of this app is offline and this is not:
 * ML Kit ships translation *models* separately from the library, so the first translation in
 * each direction downloads about 30 MB. After that it runs with the network off, like everything
 * else. [TranslationState.NeedsDownload] exists so the UI can say that out loud rather than
 * appearing to hang on a phone with no signal.
 *
 * Recognition is untouched by any of this — scanning still never needs a connection.
 */
sealed interface TranslationState {
    /** Translated text is ready. */
    data class Done(val text: String, val from: String, val to: String) : TranslationState

    /** The model for this direction is not on the device yet. */
    data object NeedsDownload : TranslationState

    /** Downloading the model now. */
    data object Downloading : TranslationState

    data class Failed(val message: String) : TranslationState
}

class TextTranslator {

    private val cache = mutableMapOf<String, Translator>()

    /**
     * Guesses which way to translate from the text itself.
     *
     * A single Devanagari character is enough to call it Hindi: mixed text is the normal case
     * here — a Hindi caption with an English brand name in it — and the direction that helps is
     * the one that turns the Devanagari into something the reader can read.
     */
    fun directionFor(text: String): Pair<String, String> =
        if (text.any { it in DEVANAGARI }) {
            TranslateLanguage.HINDI to TranslateLanguage.ENGLISH
        } else {
            TranslateLanguage.ENGLISH to TranslateLanguage.HINDI
        }

    /** True when the model for this direction is already on the device. */
    suspend fun isReady(from: String, to: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val translator = translator(from, to)
            // downloadModelIfNeeded with requireWifi and a zero-tolerance check is not offered by
            // ML Kit, so readiness is probed by translating an empty string: it completes
            // instantly when the model is present and fails when it is not.
            await<String> { translator.translate("").addOnSuccessListener(it::resume).addOnFailureListener(it::resumeWithException) }
            true
        }.getOrDefault(false)
    }

    /** Downloads the model for this direction. Only call it after the user has agreed to it. */
    suspend fun download(from: String, to: String, wifiOnly: Boolean): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val conditions = DownloadConditions.Builder()
                    .apply { if (wifiOnly) requireWifi() }
                    .build()
                await<Void> { c ->
                    translator(from, to).downloadModelIfNeeded(conditions)
                        .addOnSuccessListener { c.resume(null) }
                        .addOnFailureListener(c::resumeWithException)
                }
                Unit
            }
        }

    suspend fun translate(text: String, from: String, to: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                await<String> { c ->
                    translator(from, to).translate(text)
                        .addOnSuccessListener(c::resume)
                        .addOnFailureListener(c::resumeWithException)
                }.orEmpty()
            }
        }

    /** Frees the native translators. The scan screen owns these for one scan at a time. */
    fun close() {
        cache.values.forEach { it.close() }
        cache.clear()
    }

    private fun translator(from: String, to: String): Translator =
        cache.getOrPut("$from>$to") {
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(from)
                    .setTargetLanguage(to)
                    .build(),
            )
        }

    private suspend inline fun <T> await(
        crossinline block: (kotlin.coroutines.Continuation<T?>) -> Unit,
    ): T? = suspendCoroutine { c -> block(c) }

    private companion object {
        val DEVANAGARI = 'ऀ'..'ॿ'
    }
}

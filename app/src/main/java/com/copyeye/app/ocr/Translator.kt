package com.copyeye.app.ocr

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
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

/**
 * Translation with two engines and a clear order of preference.
 *
 * ML Kit is on the device, free and offline, and writes textbook Hindi. The server route uses a
 * model large enough to sound like a person, but needs a connection and costs the owner money —
 * and its key can never be in this app, which is why it is behind an endpoint rather than called
 * directly. The good one is tried first and the offline one always catches.
 */
class TextTranslator {

    /** Where the server-side translator lives. Nothing secret is in this URL. */
    private val endpoint = "https://copyeye.lzworth.in/api/translate"

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

    /**
     * The better translation, if it is reachable.
     *
     * Returns null rather than failing for every reason a phone has: no signal, a flat battery
     * saver, the endpoint unconfigured. The caller falls through to ML Kit and the user never
     * learns any of this happened.
     */
    private suspend fun translateRemote(text: String, to: String): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = JSONObject().apply {
                    put("text", text)
                    put("to", if (to == TranslateLanguage.ENGLISH) "en" else "hi")
                }
                val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = REMOTE_TIMEOUT_MS
                    readTimeout = REMOTE_TIMEOUT_MS
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
                val ok = conn.responseCode in 200..299
                val payload = (if (ok) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() }
                conn.disconnect()
                if (!ok) return@runCatching null
                JSONObject(payload.orEmpty()).optString("text").takeIf { it.isNotBlank() }
            }.getOrNull()
        }

    suspend fun translate(text: String, from: String, to: String): Result<String> =
        withContext(Dispatchers.IO) {
            // The good one first. It is the only reason someone would notice this feature at all.
            translateRemote(text, to)?.let { return@withContext Result.success(it) }
            runCatching {
                await<String> { c ->
                    translator(from, to).translate(text)
                        .addOnSuccessListener(c::resume)
                        .addOnFailureListener(c::resumeWithException)
                }.orEmpty().let(::colloquialise)
            }
        }

    /**
     * Softens ML Kit's Hindi into the Hindi people actually speak.
     *
     * The model is trained on formal written text, so it reaches for the Sanskritised register:
     * *अत्यंत* where anyone would say *बहुत*, *परन्तु* for *लेकिन*, *एवं* for *और*. Read aloud it
     * sounds like a school textbook, which is not what someone wants back from a caption on a
     * Reel.
     *
     * This cannot fix the translation — the model is the model, and a wrong sentence stays wrong.
     * It only swaps the handful of words that make a correct sentence sound stiff. Word-boundary
     * matched, so *एवं* inside a longer word is left alone.
     */
    private fun colloquialise(text: String): String {
        var out = text
        EVERYDAY.forEach { (formal, plain) ->
            out = out.replace(Regex("(?<![\\p{L}])" + Regex.escape(formal) + "(?![\\p{L}])"), plain)
        }
        return out
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
        const val REMOTE_TIMEOUT_MS = 12_000

        val DEVANAGARI = 'ऀ'..'ॿ'

        /**
         * Formal → everyday. Only words where the plain form is unambiguously the same meaning —
         * nothing here changes what a sentence says, just how stilted it sounds.
         */
        val EVERYDAY = listOf(
            "अत्यंत" to "बहुत",
            "अत्यन्त" to "बहुत",
            "अतीव" to "बहुत",
            "परन्तु" to "लेकिन",
            "परंतु" to "लेकिन",
            "किन्तु" to "लेकिन",
            "किंतु" to "लेकिन",
            "एवं" to "और",
            "तथा" to "और",
            "अथवा" to "या",
            "अतः" to "तो",
            "इसलिये" to "इसलिए",
            "क्योंकि" to "क्योंकि",
            "प्रातः" to "सुबह",
            "सायं" to "शाम",
            "धन्यवाद" to "शुक्रिया",
            "कृपया" to "प्लीज़",
            "समीप" to "पास",
            "शीघ्र" to "जल्दी",
            "अधिक" to "ज़्यादा",
            "न्यून" to "कम",
            "सम्पूर्ण" to "पूरा",
            "संपूर्ण" to "पूरा",
            "प्रारंभ" to "शुरू",
            "प्रारम्भ" to "शुरू",
            "समाप्त" to "खत्म",
            "उपरांत" to "बाद",
            "उपरान्त" to "बाद",
            "व्यय" to "खर्च",
            "क्रय" to "खरीद",
            "विक्रय" to "बिक्री",
            "आवश्यक" to "ज़रूरी",
            "आवश्यकता" to "ज़रूरत",
            "प्रयोग" to "इस्तेमाल",
            "उपयोग" to "इस्तेमाल",
            "सहायता" to "मदद",
            "प्रश्न" to "सवाल",
            "उत्तर" to "जवाब",
            "समय" to "टाइम",
            "मूल्य" to "कीमत",
            "प्रतीक्षा" to "इंतज़ार",
            "सूचना" to "जानकारी",
            "स्थान" to "जगह",
            "मार्ग" to "रास्ता",
            "निःशुल्क" to "फ्री",
            "पुनः" to "फिर से",
        )
    }
}

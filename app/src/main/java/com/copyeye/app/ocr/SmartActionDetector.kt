package com.copyeye.app.ocr

/** A follow-up the copied text makes obvious. Always secondary to copying. */
sealed interface SmartAction {
    val value: String

    data class OpenUrl(override val value: String) : SmartAction
    data class Call(override val value: String) : SmartAction
    data class Email(override val value: String) : SmartAction
    data class Map(override val value: String) : SmartAction
}

/**
 * Spots the handful of patterns where the user almost certainly wants to do something with the
 * text as well as copy it.
 *
 * Deliberately conservative. A false positive puts a wrong button in front of someone who only
 * wanted to copy, which is worse than a missed suggestion — copying already worked.
 */
object SmartActionDetector {

    private val URL = Regex(
        """\b(?:https?://|www\.)[\w\-@:%.+~#=]{1,256}\.[a-zA-Z]{2,63}\b(?:[\-\w()@:%+.~#?&/=]*)""",
        RegexOption.IGNORE_CASE,
    )

    private val EMAIL = Regex(
        """\b[\w.+\-]+@[\w\-]+(?:\.[\w\-]+)+\b""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Phone numbers, biased toward Indian formats: an optional +country prefix, then 8-13 digits
     * with spaces or hyphens allowed between groups.
     */
    private val PHONE = Regex("""(?<![\w.])(?:\+\d{1,3}[ \-]?)?(?:\d[ \-]?){8,13}\d(?![\w.])""")

    /** Something that looks like a street address: a house number followed by words then a PIN. */
    private val ADDRESS = Regex(
        """\b\d{1,5}[,/\-\s][\w\s,.\-/]{6,60}\b\d{6}\b""",
    )

    fun detect(text: String): List<SmartAction> {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_SCANNED_LENGTH) return emptyList()

        val actions = LinkedHashMap<String, SmartAction>()

        EMAIL.findAll(trimmed).take(MAX_PER_KIND).forEach {
            actions.putIfAbsent("email:${it.value}", SmartAction.Email(it.value))
        }
        URL.findAll(trimmed).take(MAX_PER_KIND).forEach { match ->
            // An email address contains a domain, so it also matches the URL pattern.
            if (actions.keys.none { it.startsWith("email:") && it.contains(match.value) }) {
                actions.putIfAbsent("url:${match.value}", SmartAction.OpenUrl(normaliseUrl(match.value)))
            }
        }
        PHONE.findAll(trimmed).take(MAX_PER_KIND).forEach { match ->
            val digits = match.value.count { it.isDigit() }
            // 10-13 digits is a phone number; below that it is a price, a year or an OTP.
            if (digits in 10..13) {
                actions.putIfAbsent("tel:${match.value}", SmartAction.Call(match.value.trim()))
            }
        }
        ADDRESS.find(trimmed)?.let {
            actions.putIfAbsent("map:${it.value}", SmartAction.Map(it.value.trim()))
        }

        return actions.values.toList()
    }

    private fun normaliseUrl(raw: String): String =
        if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) raw else "https://$raw"

    private const val MAX_SCANNED_LENGTH = 4_000
    private const val MAX_PER_KIND = 3
}

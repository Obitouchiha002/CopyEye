package com.copyeye.app.remote

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.time.LocalDate

/**
 * What premium buys, and the one daily allowance free users get.
 *
 * The line is drawn at cost, deliberately. Scanning, copying, sharing in, UPI/OTP and offline
 * translation run on the phone and cost nothing to serve, so they stay free for everyone,
 * forever — that is the product. Natural translation is a model call on a server that bills by
 * the request. Premium is exactly that and nothing else, which is the only split that is easy to
 * explain and never makes a free user feel robbed.
 */
object Premium {

    /** Natural (server) translations a free install gets per calendar day. */
    const val FREE_NATURAL_PER_DAY = 5

    private const val PREFS = "copyeye_quota"
    private const val KEY_DAY = "day"
    private const val KEY_USED = "used"

    /** Where the purchase happens. The install id rides along so the payment can find this phone. */
    private const val PAGE = "https://copyeye.lzworth.in/premium"

    /**
     * True if this translation may use the server model.
     *
     * The count lives on the phone, so clearing app data resets it — this is an allowance, not a
     * lock, and it is not pretending to be one. The server model's cost is small per call; what
     * matters is that ordinary use of the free tier stays inside a predictable budget.
     */
    fun mayUseNatural(context: Context, isPremium: Boolean): Boolean {
        if (isPremium) return true
        return usedToday(context) < FREE_NATURAL_PER_DAY
    }

    /** Call once a natural translation has actually succeeded — a failed call costs the user nothing. */
    fun recordNatural(context: Context, isPremium: Boolean) {
        if (isPremium) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = LocalDate.now().toString()
        val used = if (prefs.getString(KEY_DAY, null) == today) prefs.getInt(KEY_USED, 0) else 0
        prefs.edit().putString(KEY_DAY, today).putInt(KEY_USED, used + 1).apply()
    }

    fun remainingToday(context: Context): Int =
        (FREE_NATURAL_PER_DAY - usedToday(context)).coerceAtLeast(0)

    private fun usedToday(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return if (prefs.getString(KEY_DAY, null) == LocalDate.now().toString()) {
            prefs.getInt(KEY_USED, 0)
        } else {
            0
        }
    }

    /** Opens the payment page in the browser, carrying this install's id. */
    fun purchaseIntent(context: Context): Intent =
        Intent(
            Intent.ACTION_VIEW,
            Uri.parse("$PAGE?device=${Uri.encode(RemoteAdmin.installId(context))}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

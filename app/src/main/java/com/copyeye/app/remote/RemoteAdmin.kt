package com.copyeye.app.remote

import android.content.Context
import android.provider.Settings
import com.copyeye.app.BuildConfig
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Check-in against the Supabase project shared with StreamGarden.
 *
 * This is the one place in CopyEye that touches the network, and it is worth being precise about
 * what it sends, because the app's whole pitch used to be that it sends nothing at all:
 *
 *  - the Android ID, so one phone is one row rather than a new row per launch;
 *  - the name the user typed on first launch;
 *  - the app version and the string "copyeye".
 *
 * It never sends a captured screen, a scanned word, or anything the OCR produced. Those never
 * leave the device and no code path here can reach them.
 *
 * The build carries only the *public* anon key, and since the lockdown that key can call exactly
 * two functions — `checkin` and `claim_name`. Every admin function was revoked from it, so a
 * decompiled APK yields nothing an attacker can act on.
 */
object RemoteAdmin {

    private const val SUPABASE_URL = "https://befdjbbzuyzjlyrckkxj.supabase.co"
    private const val ANON_KEY =
        "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImJlZmRqYmJ6dXl6" +
            "amx5cmNra3hqIiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODQwMDIyNjYsImV4cCI6MjA5OTU3ODI2Nn0." +
            "OLJterEVLS2zIKsFv2tAZmgU0TxwXxRbfeE_sEEkHj4"

    /** Distinguishes CopyEye's rows from StreamGarden's in the shared `devices` table. */
    const val PLATFORM = "copyeye"

    private const val TIMEOUT_MS = 10_000

    /**
     * Android's version-floor code.
     *
     * `checkin` returns a *block* — not a prompt — for any install below the floor published for
     * its platform, and a platform with no release row of its own inherits the global one, which
     * is currently 2.6. CopyEye is version 1.0, so until a `copyeye` release is published from the
     * control panel every install would come back blocked.
     *
     * So this one code is treated as an update notice rather than a lockout. A block that tells
     * you to update, on a platform where no update has been published, is a bug rather than a
     * policy — and it would have bricked the app for every user on the day it shipped. Blocks the
     * admin actually sets, and the kill switch, are honoured in full.
     */
    private const val CODE_UPDATE_REQUIRED = "426"

    data class Status(
        val blocked: Boolean = false,
        val reason: String? = null,
        val code: String? = null,
        val until: String? = null,
        val premium: Boolean = false,
        val latestVersion: String? = null,
        val updateUrl: String? = null,
        val updateNote: String? = null,
        val message: String? = null,
        /** True when the backend answered. False means we are guessing, and guessing means "fine". */
        val fromServer: Boolean = false,
    )

    @Suppress("HardwareIds") // Not an identifier for a person — see the class comment.
    fun installId(context: Context): String =
        runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "unknown"

    /**
     * Register or refresh this install and read its status.
     *
     * Never throws, and fails open. CopyEye must keep working when the backend is unreachable —
     * an offline phone is the case this app is *most* useful in, and a text copier that stops
     * because a database is down would be worse than one with no admin control at all.
     */
    suspend fun checkin(context: Context, displayName: String?): Status = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("p_id", installId(context))
            put("p_name", displayName?.takeIf { it.isNotBlank() } ?: android.os.Build.MODEL)
            put("p_version", BuildConfig.VERSION_NAME)
            put("p_platform", PLATFORM)
            // The live function's signature includes p_email. Omitting it makes PostgREST fail to
            // resolve the overload entirely, so it is sent as an explicit null.
            put("p_email", JSONObject.NULL)
        }

        val raw = post("checkin", body) ?: return@withContext Status()
        val row = when {
            raw.startsWith("[") -> JSONArray(raw).optJSONObject(0)
            raw.startsWith("{") -> JSONObject(raw)
            else -> null
        } ?: return@withContext Status()

        val code = row.optStringOrNull("code")
        val blocked = row.optBoolean("blocked", false) && code != CODE_UPDATE_REQUIRED
        Status(
            blocked = blocked,
            reason = row.optStringOrNull("reason"),
            code = code,
            until = row.optStringOrNull("until"),
            premium = row.optBoolean("premium", false),
            latestVersion = row.optStringOrNull("latest_version"),
            updateUrl = row.optStringOrNull("update_url"),
            updateNote = row.optStringOrNull("update_note"),
            message = row.optStringOrNull("message"),
            fromServer = true,
        )
    }

    /**
     * Claim a display name. The backend owns uniqueness: two people can pick the same name in the
     * same second, and only the database can settle that.
     *
     * Returns "offline" when it cannot be reached, which the caller treats as success — the name
     * gate must never be the one thing that keeps someone out of the app.
     */
    suspend fun claimName(context: Context, name: String): String = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("p_id", installId(context))
            put("p_name", name.trim())
        }
        val raw = post("claim_name", body) ?: return@withContext "offline"
        raw.trim().removeSurrounding("\"").takeIf {
            it == "ok" || it == "taken" || it == "invalid"
        } ?: "offline"
    }

    private fun post(fn: String, body: JSONObject): String? = runCatching {
        val conn = (URL("$SUPABASE_URL/rest/v1/rpc/$fn").openConnection() as HttpURLConnection)
        conn.apply {
            requestMethod = "POST"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            doOutput = true
            setRequestProperty("apikey", ANON_KEY)
            setRequestProperty("Authorization", "Bearer $ANON_KEY")
            setRequestProperty("Content-Type", "application/json")
        }
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val ok = conn.responseCode in 200..299
        val text = (if (ok) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() }
        conn.disconnect()
        if (ok) text else null
    }.getOrNull()

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
}

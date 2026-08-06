package com.copyeye.app.clipboard

import android.content.Context
import com.copyeye.app.data.preferences.HistoryRetention
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One thing the user copied through CopyEye. */
@Serializable
data class ClipItem(
    val id: Long,
    val text: String,
    val copiedAtMillis: Long,
    val pinned: Boolean = false,
) {
    /** Short form for list rows; the full text is only shown when an item is opened. */
    val preview: String
        get() = text.lineSequence().firstOrNull { it.isNotBlank() }?.take(PREVIEW_CHARS).orEmpty()

    private companion object {
        const val PREVIEW_CHARS = 120
    }
}

/**
 * Local history of text copied through CopyEye.
 *
 * ## Why a file and not Room
 *
 * The feature is off by default, capped at [MAX_ITEMS], and needs three operations: append, prune,
 * and a substring search. A relational database buys nothing at that scale, and it would add a
 * schema, a migration story and a code generator to a build that otherwise has none.
 *
 * ## What "encrypted" means here
 *
 * The file lives in the app's private storage, which on every device shipping Android 10 or later
 * is covered by file-based encryption tied to the user's lock screen. CopyEye does not add a second
 * layer of its own: doing so would need a key, and the only place to keep that key would be the
 * same private storage. What it does instead is minimise — history is opt-in, retention defaults to
 * a day, and nothing is ever copied off the device.
 */
class ClipboardHistoryRepository(
    context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val file = File(context.applicationContext.filesDir, FILE_NAME)
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    private val _items = MutableStateFlow<List<ClipItem>>(emptyList())
    val items: StateFlow<List<ClipItem>> = _items.asStateFlow()

    /** Loads from disk and drops anything past its retention window. */
    suspend fun load(retention: HistoryRetention) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val loaded = readFile()
            val kept = prune(loaded, retention, clock())
            _items.value = kept
            if (kept.size != loaded.size) writeFile(kept)
        }
    }

    suspend fun add(text: String, retention: HistoryRetention) = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext
        mutex.withLock {
            val now = clock()
            val existing = _items.value
            // Re-copying the same text moves it to the top rather than duplicating it.
            val withoutDuplicate = existing.filterNot { it.text == text && !it.pinned }
            val updated = prune(
                listOf(ClipItem(id = now, text = text, copiedAtMillis = now)) + withoutDuplicate,
                retention,
                now,
            ).take(MAX_ITEMS)
            _items.value = updated
            writeFile(updated)
        }
    }

    suspend fun setPinned(id: Long, pinned: Boolean) = mutate { items ->
        items.map { if (it.id == id) it.copy(pinned = pinned) else it }
    }

    suspend fun delete(id: Long) = mutate { items -> items.filterNot { it.id == id } }

    suspend fun clearAll() = mutate { emptyList() }

    /** Case-insensitive substring search, pinned items first. */
    fun search(query: String): List<ClipItem> {
        val all = _items.value
        val filtered = if (query.isBlank()) {
            all
        } else {
            all.filter { it.text.contains(query.trim(), ignoreCase = true) }
        }
        return filtered.sortedWith(
            compareByDescending<ClipItem> { it.pinned }.thenByDescending { it.copiedAtMillis },
        )
    }

    private suspend fun mutate(transform: (List<ClipItem>) -> List<ClipItem>) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val updated = transform(_items.value)
                _items.value = updated
                writeFile(updated)
            }
        }

    private fun readFile(): List<ClipItem> = try {
        if (!file.exists()) {
            emptyList()
        } else {
            json.decodeFromString<List<ClipItem>>(file.readText())
        }
    } catch (e: Exception) {
        // A missing, truncated or corrupt history file must never stop the app from starting.
        emptyList()
    }

    private fun writeFile(items: List<ClipItem>) {
        try {
            val temp = File(file.parentFile, "$FILE_NAME.tmp")
            temp.writeText(json.encodeToString(items))
            if (!temp.renameTo(file)) {
                file.writeText(json.encodeToString(items))
                temp.delete()
            }
        } catch (e: IOException) {
            // Losing history is acceptable; crashing over it is not.
        }
    }

    private class SerializationFailure : Exception()

    companion object {
        private const val FILE_NAME = "clipboard_history.json"
        const val MAX_ITEMS = 200

        /**
         * Drops expired items. Pinned items are exempt — pinning is the user saying they want this
         * one kept, which has to outrank a global retention setting.
         */
        fun prune(items: List<ClipItem>, retention: HistoryRetention, now: Long): List<ClipItem> {
            val window = retention.millis ?: return items
            return items.filter { it.pinned || now - it.copiedAtMillis <= window }
        }
    }
}

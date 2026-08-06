package com.copyeye.app.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import com.copyeye.app.capture.ScreenFrame
import com.copyeye.app.data.preferences.OcrScript
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * On-device recognition using ML Kit's bundled Latin and Devanagari models.
 *
 * Both models ship inside the APK, so recognition works with no network and nothing about the
 * user's screen leaves the device.
 *
 * ## Why two recognisers rather than one
 *
 * ML Kit has no single model that reads Latin and Devanagari together. On an Indian phone the
 * interesting screens are usually mixed — a Hindi caption over an English UI — so both are run
 * concurrently on the same frame and the results merged. The wall-clock cost is roughly the slower
 * of the two rather than their sum, and the merge drops whichever copy of an overlapping line the
 * weaker recogniser produced.
 */
class MlKitTextRecognitionEngine : TextRecognitionEngine {

    private val recognizers = mutableMapOf<OcrScript, TextRecognizer>()

    @Volatile
    private var warm = false

    override val isWarm: Boolean get() = warm

    /**
     * Forces model initialisation by running recognition on a throwaway image.
     *
     * Creating the `TextRecognizer` is cheap; ML Kit does not touch the models until the first
     * `process` call. So a real (if tiny) process call is the only thing that actually warms it.
     * 32x32 is large enough to be accepted and small enough to cost nothing.
     */
    override suspend fun warmUp(scripts: Set<OcrScript>) {
        if (warm) return
        withContext(Dispatchers.Default) {
            val probe = Bitmap.createBitmap(WARMUP_SIZE, WARMUP_SIZE, Bitmap.Config.ARGB_8888)
            try {
                val image = InputImage.fromBitmap(probe, 0)
                // Latin first, and `warm` is set as soon as it lands: a scan arriving mid-warm-up
                // should not be told the engine is cold just because the second script is still
                // loading.
                orderScripts(scripts).forEach { script ->
                    val started = SystemClock.elapsedRealtime()
                    try {
                        recognizerFor(script).process(image).await()
                        warm = true
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Warm-up failed for $script: ${e.message}")
                    }
                    Log.i(TAG, "warm-up $script took ${SystemClock.elapsedRealtime() - started}ms")
                }
            } finally {
                probe.recycle()
            }
        }
    }

    @Synchronized
    private fun recognizerFor(script: OcrScript): TextRecognizer = recognizers.getOrPut(script) {
        when (script) {
            OcrScript.Latin -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            OcrScript.Devanagari ->
                TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
        }
    }

    override fun recognizeProgressive(
        frame: ScreenFrame,
        scripts: Set<OcrScript>,
        perScriptTimeoutMs: Long,
    ): Flow<OcrResult> = flow {
        val bitmap = frame.bitmap
        if (bitmap.isRecycled) {
            emit(OcrResult.empty(0, 0))
            return@flow
        }
        val started = SystemClock.elapsedRealtime()
        val ordered = orderScripts(scripts)
        val collected = mutableListOf<Text>()

        ordered.forEach { script ->
            val recognizer = try {
                recognizerFor(script)
            } catch (e: Exception) {
                Log.w(TAG, "Recogniser for $script unavailable")
                return@forEach
            }
            val scriptStarted = SystemClock.elapsedRealtime()
            val text = try {
                // A hard ceiling per script.
                //
                // ML Kit's detector cost scales with how textured an image is, not with how much
                // text it contains. A clean interface screenshot finishes in a few hundred
                // milliseconds; a photograph or a video frame can take tens of seconds on the same
                // device. Without a ceiling that shows up to the user as a scan that never
                // finishes — which is indistinguishable from a broken app.
                withTimeout(perScriptTimeoutMs) {
                    // A fresh InputImage per recogniser. Sharing one across calls makes ML Kit's
                    // native side lock and unlock the same pixel buffer twice, which it reports as
                    // "Failed to unlock pixels for bitmap" and which loses the second result.
                    recognizer.process(InputImage.fromBitmap(bitmap, frame.rotationDegrees)).await()
                }
            } catch (e: TimeoutCancellationException) {
                Log.w(TAG, "$script gave up after ${perScriptTimeoutMs}ms")
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // One script failing must not lose the other script's results.
                Log.w(TAG, "Recognition failed for $script: ${e.message}")
                null
            }
            Log.i(TAG, "$script took ${SystemClock.elapsedRealtime() - scriptStarted}ms")

            if (text != null) {
                collected += text
                warm = true
                val blocks = mergeResults(collected)
                emit(
                    OcrResult(
                        blocks = blocks,
                        sourceWidth = bitmap.width,
                        sourceHeight = bitmap.height,
                        elapsedMs = SystemClock.elapsedRealtime() - started,
                    ),
                )
            }
        }

        if (collected.isEmpty()) {
            throw OcrTimedOutException("No script produced a result in time")
        }
    }.flowOn(Dispatchers.Default)

    /**
     * Latin first when it is requested.
     *
     * Since the scripts run one after another and results are shown as they arrive, the order
     * decides what the user sees first. Latin wins by default because interface chrome — buttons,
     * labels, URLs — is Latin even on a Hindi screen, so it is the pass most likely to contain
     * something worth copying.
     */
    private fun orderScripts(scripts: Set<OcrScript>): List<OcrScript> {
        val requested = scripts.ifEmpty { setOf(OcrScript.Latin) }
        return requested.sortedBy { if (it == OcrScript.Latin) 0 else 1 }
    }

    /**
     * Combines the output of several recognisers.
     *
     * Two recognisers looking at the same frame will both find, say, an English button label. The
     * de-duplication keeps whichever version has the longer text at a given position, on the
     * reasoning that a truncated read is the more common failure than a hallucinated extension.
     */
    private fun mergeResults(texts: List<Text>): List<OcrBlock> {
        val allLines = mutableListOf<OcrLine>()
        var lineId = 0
        val blockShapes = mutableListOf<Pair<TextRect, MutableList<OcrLine>>>()

        texts.forEach { text ->
            text.textBlocks.forEach { block ->
                val blockRect = block.boundingBox.toTextRect() ?: return@forEach
                val lines = block.lines.mapNotNull { line ->
                    val rect = line.boundingBox.toTextRect() ?: return@mapNotNull null
                    OcrLine(
                        id = lineId++,
                        text = line.text,
                        box = rect,
                        angleDegrees = line.angle,
                        words = line.elements.mapNotNull { element ->
                            element.boundingBox.toTextRect()?.let { box ->
                                OcrWord(element.text, box, element.confidence)
                            }
                        },
                        language = line.recognizedLanguage.takeIf { it.isNotBlank() },
                    )
                }
                if (lines.isNotEmpty()) {
                    allLines += lines
                    blockShapes += blockRect to lines.toMutableList()
                }
            }
        }

        val deduped = dedupeLines(allLines)
        val keptIds = deduped.mapTo(HashSet()) { it.id }

        return blockShapes
            .mapNotNull { (rect, lines) ->
                val kept = lines.filter { it.id in keptIds }
                if (kept.isEmpty()) null else rect to kept
            }
            // Merge blocks from different recognisers that describe the same region.
            .fold(mutableListOf<Pair<TextRect, MutableList<OcrLine>>>()) { acc, (rect, lines) ->
                val existing = acc.firstOrNull { it.first.iou(rect) > BLOCK_MERGE_IOU }
                if (existing == null) {
                    acc += rect to lines.toMutableList()
                } else {
                    existing.second += lines
                }
                acc
            }
            .sortedWith(compareBy({ it.first.top }, { it.first.left }))
            .mapIndexed { index, (rect, lines) ->
                val ordered = lines.sortedWith(compareBy({ it.box.top }, { it.box.left }))
                OcrBlock(
                    id = index,
                    text = ordered.joinToString("\n") { it.text },
                    box = ordered.fold(rect) { acc, line -> acc.union(line.box) },
                    lines = ordered,
                )
            }
    }

    private fun dedupeLines(lines: List<OcrLine>): List<OcrLine> {
        val kept = mutableListOf<OcrLine>()
        // Longest first, so the winner of any overlap is already in `kept` when a rival is tested.
        lines.sortedByDescending { it.text.length }.forEach { candidate ->
            val duplicate = kept.any { existing ->
                existing.box.iou(candidate.box) > LINE_DUPLICATE_IOU
            }
            if (!duplicate) kept += candidate
        }
        return kept
    }

    private fun Rect?.toTextRect(): TextRect? = this?.let {
        if (it.width() <= 0 || it.height() <= 0) {
            null
        } else {
            TextRect(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat())
        }
    }

    @Synchronized
    override fun close() {
        warm = false
        recognizers.values.forEach { recognizer ->
            runCatching { recognizer.close() }
        }
        recognizers.clear()
    }

    private companion object {
        const val TAG = "CopyEye/OCR"

        /** Above this overlap, two lines are the same line seen by two recognisers. */
        const val LINE_DUPLICATE_IOU = 0.55f

        /** Above this overlap, two blocks describe the same paragraph. */
        const val BLOCK_MERGE_IOU = 0.60f

        /** Big enough for ML Kit to accept, small enough to cost nothing. */
        const val WARMUP_SIZE = 32
    }
}

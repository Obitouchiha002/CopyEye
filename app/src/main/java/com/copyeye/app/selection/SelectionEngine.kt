package com.copyeye.app.selection

import com.copyeye.app.ocr.OcrLine
import com.copyeye.app.ocr.OcrResult
import com.copyeye.app.ocr.OcrResultMapper
import com.copyeye.app.ocr.TextRect

/** Points at one word by the line it belongs to and its position within that line. */
data class WordRef(val lineId: Int, val wordIndex: Int)

/** How a tap is interpreted. */
enum class SelectionMode { Word, Line, Paragraph }

/**
 * What the user currently has chosen.
 *
 * [anchor] and [focus] survive so a drag can extend an existing selection in either direction
 * without re-deriving where it started.
 */
data class Selection(
    val words: Set<WordRef> = emptySet(),
    val anchor: WordRef? = null,
    val focus: WordRef? = null,
) {
    val isEmpty: Boolean get() = words.isEmpty()
    val size: Int get() = words.size
}

/**
 * All the selection arithmetic, with no Android or Compose types in sight.
 *
 * The reading order it builds once per result is what makes drag-selection behave: a drag from the
 * middle of one paragraph to the middle of the next has to pick up everything between them, which
 * is a range over a flat ordered list, not a geometric test.
 */
class SelectionEngine(private val result: OcrResult) {

    /** Every word, in the order a person would read them. */
    private val order: List<WordRef> = buildList {
        result.blocks.forEach { block ->
            block.lines.forEach { line ->
                line.words.indices.forEach { index -> add(WordRef(line.id, index)) }
            }
        }
    }

    private val indexOf: Map<WordRef, Int> =
        order.withIndex().associate { (index, ref) -> ref to index }

    private val linesById: Map<Int, OcrLine> = result.lines.associateBy { it.id }

    val isEmpty: Boolean get() = order.isEmpty()

    // --- Hit testing ---------------------------------------------------------------------------

    fun wordRefAt(x: Float, y: Float, tolerance: Float): WordRef? {
        val line = OcrResultMapper.lineAt(result, x, y, tolerance) ?: return null
        if (line.words.isEmpty()) return null
        val exact = line.words.indexOfFirst { it.box.contains(x, y) }
        if (exact >= 0) return WordRef(line.id, exact)
        // Fall back to the horizontally nearest word on the line that was hit.
        val nearest = line.words.indices.minByOrNull { index ->
            val box = line.words[index].box
            val dx = x - box.centerX
            dx * dx
        } ?: return null
        return WordRef(line.id, nearest)
    }

    // --- Building selections -------------------------------------------------------------------

    /** Applies a tap according to [mode]. Tapping an already-selected item clears it. */
    fun selectAt(current: Selection, x: Float, y: Float, tolerance: Float, mode: SelectionMode): Selection {
        val hit = wordRefAt(x, y, tolerance) ?: return Selection()
        val target = when (mode) {
            SelectionMode.Word -> setOf(hit)
            SelectionMode.Line -> refsForLine(hit.lineId)
            SelectionMode.Paragraph -> refsForBlockContaining(hit.lineId)
        }
        // Tapping the exact same set again is how the user deselects.
        if (current.words == target) return Selection()
        return Selection(words = target, anchor = target.minByOrNull { orderIndex(it) }, focus = hit)
    }

    /** Starts a drag selection at a point. */
    fun beginDrag(x: Float, y: Float, tolerance: Float): Selection {
        val hit = wordRefAt(x, y, tolerance) ?: return Selection()
        return Selection(words = setOf(hit), anchor = hit, focus = hit)
    }

    /** Extends a drag selection to a point, in either direction from the anchor. */
    fun extendDrag(current: Selection, x: Float, y: Float, tolerance: Float): Selection {
        val anchor = current.anchor ?: return beginDrag(x, y, tolerance)
        val hit = wordRefAt(x, y, tolerance) ?: return current
        return Selection(words = rangeBetween(anchor, hit), anchor = anchor, focus = hit)
    }

    /** Selects everything intersecting a user-drawn rectangle. */
    fun selectRegion(region: TextRect): Selection {
        val refs = result.blocks
            .flatMap { it.lines }
            .flatMap { line ->
                line.words.mapIndexedNotNull { index, word ->
                    if (word.box.intersects(region)) WordRef(line.id, index) else null
                }
            }
            .toSet()
        return Selection(
            words = refs,
            anchor = refs.minByOrNull { orderIndex(it) },
            focus = refs.maxByOrNull { orderIndex(it) },
        )
    }

    fun selectAll(): Selection {
        val all = order.toSet()
        return Selection(words = all, anchor = order.firstOrNull(), focus = order.lastOrNull())
    }

    fun refsForLine(lineId: Int): Set<WordRef> {
        val line = linesById[lineId] ?: return emptySet()
        return line.words.indices.mapTo(LinkedHashSet()) { WordRef(lineId, it) }
    }

    fun refsForBlockContaining(lineId: Int): Set<WordRef> {
        val block = result.blocks.firstOrNull { block -> block.lines.any { it.id == lineId } }
            ?: return refsForLine(lineId)
        return block.lines.flatMapTo(LinkedHashSet()) { line ->
            line.words.indices.map { WordRef(line.id, it) }
        }
    }

    private fun rangeBetween(a: WordRef, b: WordRef): Set<WordRef> {
        val first = orderIndex(a)
        val second = orderIndex(b)
        if (first < 0 || second < 0) return setOf(a)
        val from = minOf(first, second)
        val to = maxOf(first, second)
        return order.subList(from, to + 1).toSet()
    }

    private fun orderIndex(ref: WordRef): Int = indexOf[ref] ?: -1

    // --- Reading back --------------------------------------------------------------------------

    /**
     * The selected text, with line breaks where the original had them.
     *
     * Words within a line are joined by a single space rather than reconstructed from their
     * bounding boxes: ML Kit already splits on whitespace, and guessing at spacing from pixel gaps
     * inserts phantom spaces into Devanagari conjuncts.
     */
    fun textOf(selection: Selection): String {
        if (selection.isEmpty) return ""
        val selectedByLine = selection.words.groupBy { it.lineId }
        return result.blocks
            .mapNotNull { block ->
                val blockText = block.lines
                    .mapNotNull { line ->
                        val indices = selectedByLine[line.id]?.map { it.wordIndex }?.sorted()
                            ?: return@mapNotNull null
                        if (indices.size == line.words.size) {
                            // Whole line selected — use the original text so punctuation spacing
                            // and RTL marks survive exactly as recognised.
                            line.text
                        } else {
                            indices.mapNotNull { line.words.getOrNull(it)?.text }
                                .joinToString(" ")
                        }
                    }
                    .filter { it.isNotBlank() }
                    .joinToString("\n")
                blockText.takeIf { it.isNotBlank() }
            }
            .joinToString("\n\n")
    }

    /** One rectangle per fully or partly selected line, for drawing the highlight. */
    fun highlightsFor(selection: Selection): List<TextRect> {
        if (selection.isEmpty) return emptyList()
        return selection.words
            .groupBy { it.lineId }
            .mapNotNull { (lineId, refs) ->
                val line = linesById[lineId] ?: return@mapNotNull null
                refs.mapNotNull { line.words.getOrNull(it.wordIndex)?.box }
                    .reduceOrNull { acc, box -> acc.union(box) }
            }
    }

    /** True when there is exactly one line of text in the whole result. */
    val hasSingleLine: Boolean get() = result.lines.size == 1
}

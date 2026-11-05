package com.inkspire.ebookreader.util

import com.inkspire.ebookreader.domain.model.FilteredResult
import com.inkspire.ebookreader.domain.model.Highlight
import kotlin.math.max

object TextFilterTransformer {

    fun applyFilters(
        originalText: String,
        highlights: List<Highlight>,
        textsToHide: List<String>
    ): FilteredResult {
        if (textsToHide.isEmpty()) return FilteredResult(originalText, highlights)

        val deleteRanges = mutableListOf<IntRange>()

        textsToHide.forEach { text ->
            if (text.isNotEmpty()) {
                // Paragraphs from older imports hold a literal < as itself, newer ones as the marker.
                listOf(text, StoredText.encode(text)).distinct().forEach { pattern ->
                    Regex(Regex.escape(pattern)).findAll(originalText).forEach { match ->
                        deleteRanges.add(match.range)
                    }
                }
            }
        }

        if (deleteRanges.isEmpty()) return FilteredResult(originalText, highlights)

        val sortedRanges = deleteRanges.sortedBy { it.first }
        val mergedRanges = mutableListOf<IntRange>()

        sortedRanges.forEach { range ->
            val last = mergedRanges.lastOrNull()
            if (last != null && range.first <= last.last + 1) {
                val newEnd = max(last.last, range.last)
                mergedRanges[mergedRanges.lastIndex] = last.first..newEnd
            } else {
                mergedRanges.add(range)
            }
        }

        val sb = StringBuilder()
        var currentOriginalIndex = 0

        mergedRanges.forEach { range ->
            if (range.first > currentOriginalIndex) {
                sb.append(originalText.substring(currentOriginalIndex, range.first))
            }
            currentOriginalIndex = range.last + 1
        }

        if (currentOriginalIndex < originalText.length) {
            sb.append(originalText.substring(currentOriginalIndex))
        }

        val mappedHighlights = highlights.mapNotNull { h ->
            var shiftStart = 0
            var shiftEnd = 0

            mergedRanges.forEach { cut ->
                val cutLen = (cut.last - cut.first) + 1

                if (cut.last < h.startOffset) shiftStart += cutLen
                else if (cut.first < h.startOffset) shiftStart += (h.startOffset - cut.first)

                if (cut.last < h.endOffset) shiftEnd += cutLen
                else if (cut.first < h.endOffset) shiftEnd += (h.endOffset - cut.first)
            }

            val finalStart = h.startOffset - shiftStart
            val finalEnd = h.endOffset - shiftEnd

            if (finalEnd <= finalStart) null
            else h.copy(startOffset = finalStart, endOffset = finalEnd)
        }

        return FilteredResult(sb.toString(), mappedHighlights)
    }
}
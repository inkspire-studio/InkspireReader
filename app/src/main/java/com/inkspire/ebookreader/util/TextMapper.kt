package com.inkspire.ebookreader.util

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.em
import com.inkspire.ebookreader.domain.model.MappedAnnotatedString

object TextMapper {
    private val bold = SpanStyle(fontWeight = FontWeight.Bold)
    private val italic = SpanStyle(fontStyle = FontStyle.Italic)
    private val struck = SpanStyle(textDecoration = TextDecoration.LineThrough)

    private val styles = mapOf(
        "b" to bold,
        "strong" to bold,
        "i" to italic,
        "em" to italic,
        "u" to SpanStyle(textDecoration = TextDecoration.Underline),
        "s" to struck,
        "strike" to struck,
        "del" to struck,
        "sup" to SpanStyle(baselineShift = BaselineShift.Superscript, fontSize = 0.75.em),
        "sub" to SpanStyle(baselineShift = BaselineShift.Subscript, fontSize = 0.75.em),
        "code" to SpanStyle(fontFamily = FontFamily.Monospace),
    )

    /** Heading tags draw nothing here. Paragraphs imported by older versions can hold a stray closing one. */
    private val ignoredTags = setOf("h1", "h2", "h3", "h4", "h5", "h6")

    fun convertToAnnotatedStringWithMap(paragraph: String): MappedAnnotatedString {
        val displayToRaw = mutableListOf<Int>()

        val annotatedString = buildAnnotatedString {
            val stack = mutableListOf<String>()
            var currentIndex = 0

            while (currentIndex < paragraph.length) {
                val end = tagEnd(paragraph, currentIndex)
                if (end != -1) {
                    val isClosing = paragraph[currentIndex + 1] == '/'
                    val name = paragraph.substring(currentIndex + if (isClosing) 2 else 1, end)
                    if (isClosing) {
                        if (stack.lastOrNull() == name) {
                            if (name !in ignoredTags) pop()
                            stack.removeAt(stack.lastIndex)
                        }
                    } else if (name !in ignoredTags) {
                        pushStyle(styles.getValue(name))
                        stack.add(name)
                    }
                    currentIndex = end + 1
                } else {
                    displayToRaw.add(currentIndex)
                    val char = paragraph[currentIndex]
                    append(if (StoredText.isLiteralLessThan(char)) '<' else char)
                    currentIndex++
                }
            }
            displayToRaw.add(paragraph.length)
        }
        return MappedAnnotatedString(annotatedString, displayToRaw)
    }

    fun convertToAnnotatedStrings(paragraph: String): AnnotatedString =
        convertToAnnotatedStringWithMap(paragraph).text

    /** The index of the `>` when a tag this mapper knows starts at [index], or -1. */
    private fun tagEnd(paragraph: String, index: Int): Int {
        if (paragraph[index] != '<') return -1
        val end = paragraph.indexOf('>', index)
        if (end == -1) return -1
        val name = paragraph.substring(index + 1, end).removePrefix("/")
        return if (name in styles || name in ignoredTags) end else -1
    }
}

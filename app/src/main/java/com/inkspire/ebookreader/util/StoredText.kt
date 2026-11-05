package com.inkspire.ebookreader.util

/**
 * Keeps a `<` that belongs to a book's text apart from the tags in a stored paragraph.
 *
 * Stored paragraphs carry their formatting as tags, and every reader of them strips or interprets anything shaped like a
 * tag, so a literal `<System>` would vanish and a literal `<b>` would turn bold. The marker is one private-use character
 * standing in for one `<`, which keeps every offset into a paragraph valid. Paragraphs stored before the marker existed
 * hold none and read as they always did. Anything that shows or exports stored text must [decode] it.
 */
object StoredText {
    private const val LITERAL_LESS_THAN = ''

    fun encode(text: String): String = text.replace(LITERAL_LESS_THAN, ' ').replace('<', LITERAL_LESS_THAN)

    fun decode(text: String): String = text.replace(LITERAL_LESS_THAN, '<')

    fun isLiteralLessThan(char: Char): Boolean = char == LITERAL_LESS_THAN

    /** For output that is HTML again, such as an exported EPUB. */
    fun decodeToHtml(text: String): String = text.replace(LITERAL_LESS_THAN.toString(), "&lt;")
}

package com.inkspire.ebookreader.worker.epub

import com.inkspire.ebookreader.util.StoredText
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import org.jsoup.select.NodeFilter
import java.io.ByteArrayInputStream

/** One unit of a chapter. [Text.stored] is in the stored paragraph format; [Image.source] is the reference as written. */
internal sealed interface EpubBlock {
    data class Text(val stored: String) : EpubBlock
    data class Image(val source: String) : EpubBlock
}

/** The blocks between two split points of a file, in document order. A null [anchor] is the part ahead of the first. */
internal data class EpubSegment(val anchor: String?, val blocks: List<EpubBlock>)

/**
 * Turns one EPUB content file into stored paragraphs and images, split at the given anchors.
 *
 * Takes bytes so that Jsoup can honor the file's own encoding. Formatting that a stylesheet applies through a class is not
 * read: only tags and hiding are.
 */
internal object EpubHtmlParser {

    /** Always returns the lead segment first, then one segment per anchor found, in document order. */
    fun parse(bytes: ByteArray, anchors: Collection<String> = emptyList()): List<EpubSegment> {
        val document = parseDocument(bytes)
        val walker = ParagraphWalker(findBoundaries(document, anchors))
        (document.selectFirst("body") ?: document).filter(walker)
        return walker.finish()
    }

    fun firstHeadingText(blocks: List<EpubBlock>): String? = blocks.asSequence()
        .filterIsInstance<EpubBlock.Text>()
        .firstOrNull { HEADING_PARAGRAPH.containsMatchIn(it.stored) }
        ?.let { StoredText.decode(ANY_TAG.replace(it.stored, "")).trim() }
        ?.takeIf { it.isNotEmpty() }

    /**
     * XHTML goes through the XML parser, because the HTML parser ignores a self-closed `<i/>` or `<a id="x"/>` and carries
     * it over everything after it. Being lenient, the XML parser also survives a stray unclosed tag.
     */
    private fun parseDocument(bytes: ByteArray): Document {
        val start = String(bytes, 0, minOf(bytes.size, 2048), Charsets.ISO_8859_1).replace("\u0000", "")
        val isXhtml = start.contains("<?xml", ignoreCase = true) || start.contains("xhtml", ignoreCase = true)
        val parser = if (isXhtml) Parser.xmlParser() else Parser.htmlParser()
        return Jsoup.parse(ByteArrayInputStream(bytes), null, "", parser)
    }

    /**
     * Maps each element a chapter starts at to its anchors. An anchor inside a heading starts at the heading, or the heading
     * would be cut in two and the new chapter would open on half of it.
     */
    private fun findBoundaries(document: Document, anchors: Collection<String>): Map<Node, List<String>> {
        if (anchors.isEmpty()) return emptyMap()
        val elementsById = HashMap<String, Element>()
        document.allElements.forEach { element ->
            listOf("id", "name", "xml:id").forEach { key ->
                val value = element.attr(key)
                if (value.isNotEmpty()) elementsById.putIfAbsent(value, element)
            }
        }
        val boundaries = LinkedHashMap<Node, MutableList<String>>()
        anchors.forEach { anchor ->
            val element = elementsById[anchor] ?: return@forEach
            val target = if (isHeading(element)) element else element.parents().firstOrNull(::isHeading) ?: element
            boundaries.getOrPut(target) { mutableListOf() }.add(anchor)
        }
        return boundaries
    }

    private fun isHeading(element: Element) = localName(element) in HEADINGS

    private fun localName(element: Element) = element.tagName().substringAfter(':').lowercase()

    private fun isHidden(element: Element, tag: String): Boolean {
        if (tag in UNREAD_TAGS || element.hasAttr("hidden")) return true
        if (HIDING_STYLE.containsMatchIn(element.attr("style"))) return true
        return element.attr("epub:type").contains("pagebreak") || element.attr("role") == "doc-pagebreak"
    }

    private class ParagraphWalker(private val boundaries: Map<Node, List<String>>) : NodeFilter {
        private val segments = mutableListOf(EpubSegment(null, emptyList()))
        private var blocks = mutableListOf<EpubBlock>()
        private val buffer = StringBuilder()
        private val openMarks = mutableListOf<String>()
        private var heading: String? = null
        private var preDepth = 0
        private var hasVisibleText = false
        private var endsWithSpace = true

        override fun head(node: Node, depth: Int): NodeFilter.FilterResult {
            when (node) {
                is TextNode -> appendTextNode(node)
                is Element -> {
                    val tag = localName(node)
                    if (isHidden(node, tag)) return NodeFilter.FilterResult.SKIP_ENTIRELY
                    boundaries[node]?.let(::startSegments)
                    openElement(node, tag)
                }
            }
            return NodeFilter.FilterResult.CONTINUE
        }

        override fun tail(node: Node, depth: Int): NodeFilter.FilterResult {
            if (node is Element) closeElement(localName(node))
            return NodeFilter.FilterResult.CONTINUE
        }

        fun finish(): List<EpubSegment> {
            flush()
            closeSegment(null)
            return segments
        }

        private fun openElement(element: Element, tag: String) {
            when {
                tag in HEADINGS -> {
                    if (heading == null) {
                        flush()
                        heading = tag
                    }
                }
                tag in BLOCKS -> {
                    if (heading == null) flush()
                    if (tag == "pre") preDepth++
                }
                tag == "br" -> if (heading == null) flush() else appendText(" ")
                tag in CELLS -> appendText(" ")
                tag == "img" || tag == "image" -> addImage(element)
                else -> MARKS[tag]?.let(::openMark)
            }
        }

        private fun closeElement(tag: String) {
            when {
                tag in HEADINGS -> {
                    if (heading == tag) {
                        flush()
                        heading = null
                    }
                }
                tag in BLOCKS -> {
                    if (heading == null) flush()
                    if (tag == "pre") preDepth--
                }
                tag in CELLS -> appendText(" ")
                else -> MARKS[tag]?.let(::closeMark)
            }
        }

        private fun appendTextNode(node: TextNode) {
            if (preDepth == 0) {
                appendText(node.text())
                return
            }
            node.wholeText.split('\n').forEachIndexed { index, line ->
                if (index > 0 && heading == null) flush()
                appendText(line.trim())
            }
        }

        /** A whitespace-only node still separates words, so a space is dropped only where one is already showing. */
        private fun appendText(text: String) {
            StoredText.encode(text).forEach { char ->
                val isSpace = char.isWhitespace()
                if (isSpace && endsWithSpace) return@forEach
                if (buffer.isEmpty()) openBuffer()
                buffer.append(if (isSpace) ' ' else char)
                endsWithSpace = isSpace
                if (!isSpace) hasVisibleText = true
            }
        }

        /** Marks do not reach inside a heading, which the reader draws in one style. */
        private fun openMark(mark: String) {
            if (heading != null) return
            openMarks.add(mark)
            if (buffer.isNotEmpty()) buffer.append("<$mark>")
        }

        private fun closeMark(mark: String) {
            if (heading != null || openMarks.lastOrNull() != mark) return
            openMarks.removeAt(openMarks.lastIndex)
            if (buffer.isNotEmpty()) buffer.append("</$mark>")
        }

        private fun openBuffer() {
            heading?.let { buffer.append("<$it>") } ?: openMarks.forEach { buffer.append("<$it>") }
        }

        /** Emits the paragraph so far. Marks still open are closed here and reopen in the next, so they survive a `<br>`. */
        private fun flush() {
            if (hasVisibleText) {
                if (endsWithSpace && buffer.endsWith(' ')) buffer.setLength(buffer.length - 1)
                heading?.let { buffer.append("</$it>") } ?: openMarks.asReversed().forEach { buffer.append("</$it>") }
                blocks.add(EpubBlock.Text(removeEmptyMarks(buffer.toString())))
            }
            buffer.setLength(0)
            hasVisibleText = false
            endsWithSpace = true
        }

        private fun removeEmptyMarks(paragraph: String): String {
            var current = paragraph
            while (true) {
                val next = EMPTY_MARK.replace(current, "")
                if (next == current) return current
                current = next
            }
        }

        private fun addImage(element: Element) {
            flush()
            val source = listOf("src", "data-src", "xlink:href", "href")
                .map { element.attr(it) }
                .firstOrNull { it.isNotBlank() }
            if (source != null) blocks.add(EpubBlock.Image(source))
        }

        private fun startSegments(anchors: List<String>) {
            flush()
            anchors.forEach { anchor ->
                closeSegment(anchor)
            }
        }

        /** Closes the blocks gathered so far into the open segment, then opens one for [nextAnchor]. */
        private fun closeSegment(nextAnchor: String?) {
            val last = segments.removeAt(segments.lastIndex)
            segments.add(last.copy(blocks = blocks))
            blocks = mutableListOf()
            if (nextAnchor != null) segments.add(EpubSegment(nextAnchor, emptyList()))
        }
    }

    private val HEADINGS = setOf("h1", "h2", "h3", "h4", "h5", "h6")
    private val BLOCKS = setOf(
        "address", "article", "aside", "blockquote", "body", "caption", "center", "dd", "details", "div", "dl", "dt",
        "fieldset", "figcaption", "figure", "footer", "form", "header", "hgroup", "hr", "legend", "li", "main", "nav", "ol",
        "p", "pre", "section", "summary", "table", "tbody", "tfoot", "thead", "tr", "ul",
    )
    private val CELLS = setOf("td", "th")
    private val UNREAD_TAGS = setOf("head", "noscript", "rp", "rt", "script", "style", "template", "title")

    /** Every tag a paragraph may store. `TextMapper` draws each of the right-hand values. */
    private val MARKS = mapOf(
        "b" to "b", "strong" to "b", "i" to "i", "em" to "i", "cite" to "i", "dfn" to "i", "var" to "i",
        "u" to "u", "ins" to "u", "s" to "s", "strike" to "s", "del" to "s", "sup" to "sup", "sub" to "sub",
        "code" to "code", "kbd" to "code", "samp" to "code", "tt" to "code",
    )
    private val EMPTY_MARK = Regex("""<(b|i|u|s|sup|sub|code)></\1>""")
    private val HIDING_STYLE = Regex("""(display\s*:\s*none|visibility\s*:\s*hidden)""", RegexOption.IGNORE_CASE)
    private val HEADING_PARAGRAPH = Regex("""^<h[1-6]>""")
    private val ANY_TAG = Regex("""<[^>]+>""")
}

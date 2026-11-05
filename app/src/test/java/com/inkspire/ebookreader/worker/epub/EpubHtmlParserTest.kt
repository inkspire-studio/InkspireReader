package com.inkspire.ebookreader.worker.epub

import com.inkspire.ebookreader.util.StoredText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpubHtmlParserTest {
    private fun xhtml(body: String) =
        """<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title/><style>p{color:red}</style></head><body>$body</body></html>"""

    private fun paragraphs(body: String): List<String> =
        EpubHtmlParser.parse(xhtml(body).toByteArray()).flatMap { it.blocks }.map {
            when (it) {
                is EpubBlock.Text -> it.stored
                is EpubBlock.Image -> "[${it.source}]"
            }
        }

    @Test
    fun `an inline link stays in its paragraph`() {
        assertEquals(listOf("See the note for details."), paragraphs("""<p>See <a href="n.html">the note</a> for details.</p>"""))
    }

    @Test
    fun `whitespace between inline tags separates words`() {
        assertEquals(
            listOf("Hello big <b>bold</b> <i>world</i>"),
            paragraphs("""<p><span>Hello</span> <span>big</span> <b>bold</b> <i>world</i></p>""")
        )
    }

    @Test
    fun `consecutive headings stay separate`() {
        assertEquals(
            listOf("<h1>Chapter 1</h1>", "<h2>The Start</h2>", "Body"),
            paragraphs("""<h1>Chapter 1</h1><h2>The Start</h2><p>Body</p>""")
        )
    }

    @Test
    fun `bare text beside a heading is not part of it`() {
        assertEquals(
            listOf("Before", "<h2>Title</h2>", "After"),
            paragraphs("""Before<h2>Title</h2>After""")
        )
    }

    @Test
    fun `marks survive a line break`() {
        assertEquals(
            listOf("<i>line one</i>", "<i>line two</i>"),
            paragraphs("""<p><i>line one<br/>line two</i></p>""")
        )
    }

    @Test
    fun `a paragraph with only empty marks is dropped`() {
        assertEquals(listOf("Real"), paragraphs("""<p><i> </i></p><p>Real</p>"""))
    }

    @Test
    fun `self-closed tags close themselves`() {
        assertEquals(
            listOf("First", "Second", "Third"),
            paragraphs("""<p><a id="x"/>First</p><p><i/>Second</p><div/><p>Third</p>""")
        )
    }

    @Test
    fun `text naming a file stays text`() {
        assertEquals(listOf("open photo.jpg to see it."), paragraphs("""<p>open photo.jpg to see it.</p>"""))
    }

    @Test
    fun `a literal less-than is kept apart from tags`() {
        val stored = paragraphs("""<p>&lt;System&gt;</p><p>literal &lt;b&gt;tag&lt;/b&gt; text</p><h2>&lt;Level Up&gt;</h2>""")
        assertEquals(
            listOf("<System>", "literal <b>tag</b> text", "<h2><Level Up></h2>"),
            listOf(StoredText.decode(stored[0]), StoredText.decode(stored[1]), "<h2>" + StoredText.decode(stored[2].removeSurrounding("<h2>", "</h2>")) + "</h2>")
        )
        assertEquals(StoredText.encode("<System>"), stored[0])
    }

    @Test
    fun `block tags the old list missed separate their text`() {
        assertEquals(
            listOf("Term", "Meaning", "Centered", "Addr"),
            paragraphs("""<dl><dt>Term</dt><dd>Meaning</dd></dl><center>Centered</center><address>Addr</address>""")
        )
    }

    @Test
    fun `pre keeps its lines`() {
        assertEquals(listOf("line 1", "line 2"), paragraphs("<pre>line 1\n  line 2\n</pre>"))
    }

    @Test
    fun `table rows are lines and cells are spaced`() {
        assertEquals(
            listOf("A B", "C D"),
            paragraphs("""<table><tr><td>A</td><td>B</td></tr><tr><td>C</td><td>D</td></tr></table>""")
        )
    }

    @Test
    fun `hidden content and ruby annotations are not read`() {
        assertEquals(
            listOf("漢字", "Shown"),
            paragraphs(
                """<p><ruby>漢<rt>kan</rt></ruby>字</p><p style="display: none">Hidden</p><p hidden="hidden">Hidden</p>""" +
                    """<span epub:type="pagebreak">12</span><p>Shown</p>"""
            )
        )
    }

    @Test
    fun `images keep their place and their reference`() {
        assertEquals(
            listOf("Before", "[img/a.jpg]", "After", "[img/b.jpg]"),
            paragraphs("""<p>Before<img src="img/a.jpg"/>After</p><svg xmlns:xlink="x"><image xlink:href="img/b.jpg"/></svg>""")
        )
    }

    @Test
    fun `an image inside a heading stays in order`() {
        assertEquals(
            listOf("<h1>Before</h1>", "[a.jpg]", "<h1>After</h1>"),
            paragraphs("""<h1>Before<img src="a.jpg"/>After</h1>""")
        )
    }

    @Test
    fun `an anchor inside a heading starts the chapter at the heading`() {
        val body = """<p>intro</p><h2><a id="c1"></a>One</h2><p>one</p><h2><a id="c2"></a>Two</h2><p>two</p>"""
        val segments = EpubHtmlParser.parse(xhtml(body).toByteArray(), listOf("c1", "c2"))
        assertEquals(
            listOf(
                EpubSegment(null, listOf(EpubBlock.Text("intro"))),
                EpubSegment("c1", listOf(EpubBlock.Text("<h2>One</h2>"), EpubBlock.Text("one"))),
                EpubSegment("c2", listOf(EpubBlock.Text("<h2>Two</h2>"), EpubBlock.Text("two"))),
            ),
            segments
        )
    }

    @Test
    fun `an anchor that is not in the file makes no segment`() {
        val segments = EpubHtmlParser.parse(xhtml("""<p>one</p>""").toByteArray(), listOf("missing"))
        assertEquals(listOf(EpubSegment(null, listOf(EpubBlock.Text("one")))), segments)
    }

    @Test
    fun `segments follow the document whatever order anchors arrive in`() {
        val body = """<p id="a">one</p><p id="b">two</p>"""
        assertEquals(
            listOf("a", "b"),
            EpubHtmlParser.parse(xhtml(body).toByteArray(), listOf("b", "a")).mapNotNull { it.anchor }
        )
    }

    @Test
    fun `the file's own encoding is honored`() {
        val html = """<html><head><meta charset="windows-1252"/></head><body><p>caf${'é'}</p></body></html>"""
        val blocks = EpubHtmlParser.parse(html.toByteArray(Charsets.ISO_8859_1)).flatMap { it.blocks }
        assertEquals(listOf(EpubBlock.Text("café")), blocks)
    }

    @Test
    fun `plain HTML with unclosed tags still reads`() {
        val blocks = EpubHtmlParser.parse("<html><body><p>one<br>two<p>three".toByteArray()).flatMap { it.blocks }
        assertEquals(listOf("one", "two", "three").map { EpubBlock.Text(it) }, blocks)
    }

    @Test
    fun `unclosed tags in a file declaring XHTML still read`() {
        assertEquals(listOf("one", "two", "three"), paragraphs("<p>one<br>two<p>three"))
    }

    @Test
    fun `the first heading names a chapter unless it is blank`() {
        assertEquals(
            "One",
            EpubHtmlParser.firstHeadingText(listOf(EpubBlock.Text("x"), EpubBlock.Text("<h2>One</h2>")))
        )
        assertNull(EpubHtmlParser.firstHeadingText(listOf(EpubBlock.Image("a.jpg"))))
    }
}

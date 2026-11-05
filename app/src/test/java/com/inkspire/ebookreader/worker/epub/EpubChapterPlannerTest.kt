package com.inkspire.ebookreader.worker.epub

import org.junit.Assert.assertEquals
import org.junit.Test

class EpubChapterPlannerTest {
    private fun toc(vararg entries: Pair<String, String?>) = entries.map { EpubTocEntry(it.first, it.second) }
    private fun text(value: String) = EpubBlock.Text(value)

    @Test
    fun `a spine file missing from a longer table of contents is kept`() {
        val plan = EpubChapterPlanner.plan(
            spine = listOf("cover.xhtml", "c1.xhtml", "c1b.xhtml"),
            toc = toc("c1.xhtml#a" to "A", "c1.xhtml#b" to "B", "c1.xhtml#c" to "C", "c1.xhtml#d" to "D")
        )
        assertEquals(listOf("cover.xhtml", "c1.xhtml", "c1b.xhtml"), plan.map { it.file })
        assertEquals(emptyList<EpubPlannedEntry>(), plan[0].entries)
        assertEquals(listOf("a", "b", "c", "d"), plan[1].entries.map { it.anchor })
    }

    @Test
    fun `splits survive a table of contents shorter than the spine`() {
        val plan = EpubChapterPlanner.plan(
            spine = listOf("a.xhtml", "b.xhtml", "c.xhtml"),
            toc = toc("b.xhtml#one" to "One", "b.xhtml#two" to "Two")
        )
        assertEquals(listOf("one", "two"), plan[1].entries.map { it.anchor })
    }

    @Test
    fun `the spine sets the order and files outside it come last`() {
        val plan = EpubChapterPlanner.plan(
            spine = listOf("a.xhtml", "b.xhtml"),
            toc = toc("extra.xhtml" to "Extra", "b.xhtml" to "B", "a.xhtml" to "A")
        )
        assertEquals(listOf("a.xhtml", "b.xhtml", "extra.xhtml"), plan.map { it.file })
    }

    @Test
    fun `encoded and differently spelled hrefs are the same file`() {
        val plan = EpubChapterPlanner.plan(
            spine = listOf("OEBPS/my%20file.xhtml"),
            toc = toc("OEBPS/./my file.xhtml#a%20b" to "T")
        )
        assertEquals(listOf(EpubPlannedFile("OEBPS/my file.xhtml", listOf(EpubPlannedEntry("a b", "T")))), plan)
    }

    @Test
    fun `duplicate table of contents entries make one chapter and blank titles count as none`() {
        val plan = EpubChapterPlanner.plan(listOf("a.xhtml"), toc("a.xhtml" to "  ", "a.xhtml" to "Again"))
        assertEquals(listOf(EpubPlannedEntry(null, null)), plan.single().entries)
    }

    @Test
    fun `text ahead of the first anchor goes to the first chapter`() {
        val drafts = EpubChapterPlanner.assemble(
            listOf(EpubPlannedEntry("a", "A"), EpubPlannedEntry("b", "B")),
            listOf(
                EpubSegment(null, listOf(text("intro"))),
                EpubSegment("a", listOf(text("one"))),
                EpubSegment("b", listOf(text("two"))),
            )
        )
        assertEquals(
            listOf(EpubChapterDraft("A", listOf(text("intro"), text("one"))), EpubChapterDraft("B", listOf(text("two")))),
            drafts
        )
    }

    @Test
    fun `an entry naming the whole file keeps the text ahead of the anchors`() {
        val drafts = EpubChapterPlanner.assemble(
            listOf(EpubPlannedEntry(null, "File"), EpubPlannedEntry("a", "A")),
            listOf(EpubSegment(null, listOf(text("intro"))), EpubSegment("a", listOf(text("one"))))
        )
        assertEquals(listOf("File", "A"), drafts.map { it.title })
    }

    @Test
    fun `an entry naming the whole file makes no blank chapter when an anchor opens the file`() {
        val drafts = EpubChapterPlanner.assemble(
            listOf(EpubPlannedEntry(null, "File"), EpubPlannedEntry("a", null)),
            listOf(EpubSegment(null, emptyList()), EpubSegment("a", listOf(text("one"))))
        )
        assertEquals(listOf(EpubChapterDraft("File", listOf(text("one")))), drafts)
    }

    @Test
    fun `a missing anchor gets no chapter and duplicates nothing`() {
        val drafts = EpubChapterPlanner.assemble(
            listOf(EpubPlannedEntry("a", "A"), EpubPlannedEntry("missing", "Gone")),
            listOf(EpubSegment(null, emptyList()), EpubSegment("a", listOf(text("one"))))
        )
        assertEquals(listOf(EpubChapterDraft("A", listOf(text("one")))), drafts)
    }

    @Test
    fun `when no anchor is found the whole file is the first entry`() {
        val drafts = EpubChapterPlanner.assemble(
            listOf(EpubPlannedEntry("x", "X"), EpubPlannedEntry("y", "Y")),
            listOf(EpubSegment(null, listOf(text("all"))))
        )
        assertEquals(listOf(EpubChapterDraft("X", listOf(text("all")))), drafts)
    }
}

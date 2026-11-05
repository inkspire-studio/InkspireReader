package com.inkspire.ebookreader.worker.epub

internal data class EpubTocEntry(val href: String, val title: String?)

internal data class EpubPlannedEntry(val anchor: String?, val title: String?)

internal data class EpubPlannedFile(val file: String, val entries: List<EpubPlannedEntry>)

internal data class EpubChapterDraft(val title: String?, val blocks: List<EpubBlock>)

/**
 * Decides which chapters a book becomes, so that no content depends on how complete its table of contents is.
 *
 * The spine is what the book contains and sets the order. The table of contents only names chapters and marks where a
 * file splits. A spine file it does not list is still a chapter, and a file it lists outside the spine goes at the end.
 */
internal object EpubChapterPlanner {

    fun plan(spine: List<String>, toc: List<EpubTocEntry>): List<EpubPlannedFile> {
        val entriesByFile = toc
            .groupBy { EpubPaths.normalize(it.href) }
            .filterKeys { it.isNotEmpty() }
            .mapValues { (_, entries) ->
                entries
                    .map { EpubPlannedEntry(EpubPaths.fragment(it.href), it.title?.takeIf(String::isNotBlank)) }
                    .distinctBy { it.anchor }
            }
        val spineFiles = spine.map(EpubPaths::normalize).filter { it.isNotEmpty() }.distinct()
        return (spineFiles + (entriesByFile.keys - spineFiles.toSet()))
            .map { file -> EpubPlannedFile(file, entriesByFile[file].orEmpty()) }
    }

    /**
     * Matches a parsed file's segments to what the table of contents says about it.
     *
     * Text ahead of the first anchor belongs to the entry naming the whole file, or else to the first anchored chapter.
     * An entry whose anchor is not in the file gets no chapter: it has no content of its own to show.
     */
    fun assemble(entries: List<EpubPlannedEntry>, segments: List<EpubSegment>): List<EpubChapterDraft> {
        val lead = segments.firstOrNull { it.anchor == null }?.blocks.orEmpty()
        val anchored = segments.filter { it.anchor != null }
        val fileEntry = entries.firstOrNull { it.anchor == null }

        if (anchored.isEmpty()) {
            return listOf(EpubChapterDraft((fileEntry ?: entries.firstOrNull())?.title, lead))
        }

        val titles = entries.filter { it.anchor != null }.associate { it.anchor to it.title }
        val leadHasOwnChapter = fileEntry != null && lead.isNotEmpty()
        val drafts = mutableListOf<EpubChapterDraft>()
        if (leadHasOwnChapter) drafts += EpubChapterDraft(fileEntry.title, lead)
        anchored.forEachIndexed { index, segment ->
            val isFirst = index == 0
            val title = titles[segment.anchor] ?: fileEntry?.title?.takeIf { isFirst && !leadHasOwnChapter }
            val blocks = if (isFirst && !leadHasOwnChapter) lead + segment.blocks else segment.blocks
            drafts += EpubChapterDraft(title, blocks)
        }
        return drafts
    }
}

package com.inkspire.ebookreader.domain.usecase

import com.inkspire.ebookreader.data.mapper.toDataClass
import com.inkspire.ebookreader.data.mapper.toInsertModel
import com.inkspire.ebookreader.domain.model.Chapter
import com.inkspire.ebookreader.domain.model.Highlight
import com.inkspire.ebookreader.domain.model.HighlightResult
import com.inkspire.ebookreader.domain.model.HighlightToInsert
import com.inkspire.ebookreader.domain.model.SearchResult
import com.inkspire.ebookreader.domain.repository.BookRepository
import com.inkspire.ebookreader.domain.repository.ChapterRepository
import com.inkspire.ebookreader.domain.repository.HiddenTextRepository
import com.inkspire.ebookreader.domain.repository.HighlightRepository
import com.inkspire.ebookreader.domain.repository.TableOfContentRepository
import com.inkspire.ebookreader.util.StoredText
import com.inkspire.ebookreader.util.HighlightUtil
import com.inkspire.ebookreader.util.TextFilterTransformer
import com.inkspire.ebookreader.util.TextMapper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

class BookContentUseCase(
    private val bookRepository: BookRepository,
    private val chapterRepository: ChapterRepository,
    private val tableOfContentRepository: TableOfContentRepository,
    private val highlightRepository: HighlightRepository,
    private val hiddenTextRepository: HiddenTextRepository
) {
    fun getBookAsFlow(bookId: String) = bookRepository.getBookAsFlow(bookId)
    fun getTableOfContentAsFlow(bookId: String) = tableOfContentRepository.getTableOfContentAsFlow(bookId)
    fun getChapterContentFlow(bookId: String, tocId: Int): Flow<Chapter?> {
        val contentFlow = chapterRepository.getChapterContentFlow(bookId, tocId)
        val highlightFlow = highlightRepository.getHighlightsForChapterFlow(bookId, tocId)
        val hiddenTextFlow = hiddenTextRepository.getHiddenTextsFlow()

        return combine(contentFlow, highlightFlow, hiddenTextFlow) { entity, highlightEntities, hiddenEntities ->
            if (entity == null) return@combine null

            val highlightMap = highlightEntities.groupBy { it.paragraphIndex }

            val hiddenTexts = hiddenEntities.map { it.textToHide }

            val newContent = ArrayList<String>()
            val newHighlightMap = HashMap<Int, List<Highlight>>()

            entity.content.forEachIndexed { index, rawParagraph ->
                val highlightsForPara = highlightMap[index] ?: emptyList()
                val result = TextFilterTransformer.applyFilters(rawParagraph, highlightsForPara, hiddenTexts)

                newContent.add(result.displayText)
                if (result.adjustedHighlights.isNotEmpty()) {
                    newHighlightMap[index] = result.adjustedHighlights
                }
            }

            Chapter(
                bookId = entity.bookId,
                tocId = entity.tocId,
                chapterTitle = entity.chapterTitle,
                content = newContent,
                highlights = newHighlightMap,
            )
        }
    }

    suspend fun updateParagraphContent(
        bookId: String,
        tocId: Int,
        paragraphIndex: Int,
        visualStart: Int,
        visualEnd: Int,
        replacementText: String
    ) {
        val chapterContent = chapterRepository.getChapterContent(bookId, tocId) ?: return
        val rawParagraph = chapterContent.content.getOrNull(paragraphIndex) ?: return

        val mapped = TextMapper.convertToAnnotatedStringWithMap(rawParagraph)

        if (visualStart >= mapped.displayToRawMap.size || visualEnd > mapped.displayToRawMap.size) return

        val rawStart = mapped.displayToRawMap[visualStart]
        val rawEnd = mapped.displayToRawMap[visualEnd]

        val sb = StringBuilder(rawParagraph)
        sb.replace(rawStart, rawEnd, StoredText.encode(replacementText))
        val newRawParagraph = sb.toString()

        val newContentList = chapterContent.content.toMutableList()
        newContentList[paragraphIndex] = newRawParagraph

        val currentHighlights = highlightRepository.getHighlightsForParagraph(bookId, tocId, paragraphIndex)
        val newHighlights = HighlightUtil.recalculateHighlightsAfterEdit(
            currentHighlights,
            rawStart,
            rawEnd,
            replacementText.length
        ).map { it.toInsertModel().copy(bookId = bookId, tocId = tocId) }
        chapterRepository.updateChapterContent(bookId, tocId, newContentList)
        highlightRepository.replaceHighlightsForParagraph(bookId, tocId, paragraphIndex, newHighlights)
    }
    suspend fun findAndReplaceInBook(
        bookId: String,
        find: String,
        replace: String,
        isCaseSensitive: Boolean
    ): Int {
        val chapters = chapterRepository.getAllChapters(bookId)
        var changedParagraphCount = 0

        chapters.forEach { chapter ->
            var isChapterChanged = false
            val newContentList = chapter.content.toMutableList()
            val allNewHighlights = mutableListOf<HighlightToInsert>()

            val chapterHighlights = highlightRepository.getHighlightsForChapterSync(bookId, chapter.tocId)
            val highlightMap = chapterHighlights.map { it.toDataClass() }.groupBy { it.paragraphIndex }

            for (i in newContentList.indices) {
                val originalText = newContentList[i]

                // Paragraphs from older imports hold a literal < as itself, newer ones as the marker.
                val findInParagraph = listOf(StoredText.encode(find), find).firstOrNull {
                    originalText.contains(it, ignoreCase = !isCaseSensitive)
                } ?: continue

                val currentParaHighlights = highlightMap[i] ?: emptyList()
                val (newText, newParaHighlights) = HighlightUtil.batchFindAndReplace(
                    originalText, findInParagraph, StoredText.encode(replace), currentParaHighlights, isCaseSensitive
                )

                if (newText != originalText) {
                    newContentList[i] = newText
                    isChapterChanged = true
                    changedParagraphCount++

                    allNewHighlights.addAll(
                        newParaHighlights.map { it.toInsertModel().copy(bookId = bookId, tocId = chapter.tocId) }
                    )
                }
            }

            if (isChapterChanged) {
                chapterRepository.updateChapterContentById(chapter.chapterContentId, newContentList)

                newContentList.forEachIndexed { index, text ->
                    if (text != chapter.content[index]) {
                        highlightRepository.replaceHighlightsForParagraph(
                            bookId, chapter.tocId, index,
                            allNewHighlights.filter { it.paragraphIndex == index }
                        )
                    }
                }
            }
        }
        return changedParagraphCount
    }
    suspend fun searchInBook(
        bookId: String,
        query: String,
        isCaseSensitive: Boolean
    ): List<SearchResult> {
        if (query.isBlank()) return emptyList()

        val chapters = chapterRepository.getAllChapters(bookId)
        val results = mutableListOf<SearchResult>()
        val contextLength = 40
        val htmlTagPattern = Regex("<[^>]*>")

        chapters.forEach { chapter ->
            chapter.content.forEachIndexed { index, rawParagraph ->
                val cleanParagraph = StoredText.decode(rawParagraph.replace(htmlTagPattern, ""))

                var matchIndex = cleanParagraph.indexOf(query, ignoreCase = !isCaseSensitive)

                while (matchIndex != -1) {
                    val start = (matchIndex - contextLength).coerceAtLeast(0)
                    val end = (matchIndex + query.length + contextLength).coerceAtMost(cleanParagraph.length)

                    var snippet = cleanParagraph.substring(start, end)
                    if (start > 0) snippet = "...$snippet"
                    if (end < cleanParagraph.length) snippet = "$snippet..."

                    snippet = snippet.replace("\n", " ").trim()

                    results.add(
                        SearchResult(
                            bookId = bookId,
                            tocId = chapter.tocId,
                            paragraphIndex = index,
                            chapterTitle = chapter.chapterTitle,
                            snippet = snippet,
                            matchWord = query,
                            isCaseSensitive = isCaseSensitive
                        )
                    )

                    matchIndex = cleanParagraph.indexOf(query, matchIndex + 1, ignoreCase = !isCaseSensitive)
                }
            }
        }
        return results
    }
    suspend fun getAllHighlightsInBook(bookId: String): List<HighlightResult> {
        val chapters = chapterRepository.getAllChapters(bookId)
        val allHighlights = highlightRepository.getAllHighlightsForBook(bookId)
        val hiddenTexts = hiddenTextRepository.getHiddenTexts()
        val hiddenPatterns = hiddenTexts.map { it.textToHide }
        val results = mutableListOf<HighlightResult>()
        val highlightsByChapter = allHighlights.groupBy { it.tocId }
        chapters.forEach { chapter ->
            val chapterHighlights = highlightsByChapter[chapter.tocId] ?: return@forEach
            val highlightsByPara = chapterHighlights.groupBy { it.paragraphIndex }
            highlightsByPara.forEach { (paraIndex, rawHighlights) ->
                val rawContent = chapter.content.getOrNull(paraIndex) ?: return@forEach
                val transformationResult = TextFilterTransformer.applyFilters(
                    originalText = rawContent,
                    highlights = rawHighlights.map { it.toDataClass() },
                    textsToHide = hiddenPatterns
                )
                if (transformationResult.adjustedHighlights.isNotEmpty()) {
                    results.add(
                        HighlightResult(
                            bookId = bookId,
                            tocId = chapter.tocId,
                            paragraphIndex = paraIndex,
                            chapterTitle = chapter.chapterTitle,
                            content = StoredText.decode(transformationResult.displayText),
                            highlights = transformationResult.adjustedHighlights
                        )
                    )
                }
            }
        }

        return results
    }
    suspend fun saveBookInfoChapterIndex(bookId: String, chapterIndex: Int) = bookRepository.saveBookInfoChapterIndex(bookId, chapterIndex)
    suspend fun saveBookInfoParagraphIndex(bookId: String, paragraphIndex: Int) = bookRepository.saveBookInfoParagraphIndex(bookId, paragraphIndex)
    suspend fun replaceHighlightsForParagraph(bookId: String, tocId: Int, paragraphIndex: Int, newHighlights: List<HighlightToInsert>) = highlightRepository.replaceHighlightsForParagraph(bookId, tocId, paragraphIndex, newHighlights)
    suspend fun getHighlightsForParagraph(bookId: String, tocId: Int, paragraphIndex: Int) = highlightRepository.getHighlightsForParagraph(bookId, tocId, paragraphIndex)
    suspend fun addHiddenText(text: String) {
        hiddenTextRepository.addHiddenText(text)
    }
}
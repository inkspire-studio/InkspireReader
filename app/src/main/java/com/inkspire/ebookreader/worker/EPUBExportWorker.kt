package com.inkspire.ebookreader.worker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.net.toUri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.inkspire.ebookreader.domain.repository.BookRepository
import com.inkspire.ebookreader.domain.repository.ChapterRepository
import com.inkspire.ebookreader.domain.repository.TableOfContentRepository
import com.inkspire.ebookreader.util.StoredText
import com.inkspire.ebookreader.util.HeaderTextSizeUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.siegmann.epublib.domain.Author
import nl.siegmann.epublib.domain.Book
import nl.siegmann.epublib.domain.Resource
import nl.siegmann.epublib.epub.EpubWriter
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs

class EPUBExportWorker(
    private val appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params), KoinComponent {

    private val bookRepository: BookRepository by inject()
    private val tableOfContentsRepository: TableOfContentRepository by inject()
    private val chapterRepository: ChapterRepository by inject()
    val linkPattern = Regex("""\.capstone\.bookshelf/files/[^ ]*""")

    companion object {
        const val SAVE_URI = "saveUri"
        const val BOOK_ID = "bookId"
        const val FONT_SIZE = "fontSize"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val uriString = inputData.getString(SAVE_URI) ?: return@withContext Result.failure()
            val bookId = inputData.getString(BOOK_ID) ?: return@withContext Result.failure()
            val bookEntity = bookRepository.getBook(bookId) ?: return@withContext Result.failure()
            val fontSize = inputData.getFloat(FONT_SIZE, 16f)
            val tableOfContents = tableOfContentsRepository.getTableOfContents(bookId)
            val uri = uriString.toUri()

            appContext.contentResolver.openOutputStream(uri,"wt")?.use { outputStream ->
                val epubBook = Book().apply {
                    metadata.addTitle(bookEntity.title.replace(Regex("\\s*\\(Draft\\)$"), ""))
                    bookEntity.authors.forEach { metadata.addAuthor(Author(it, "")) }

                    tableOfContents.forEachIndexed { index, toc ->
                        val chapter = chapterRepository.getChapterContent(bookId, toc.index)
                        val (html, imageResources) = buildChapterHtml(chapter?.content ?: emptyList(), fontSize)

                        val chapterFileName = "text/chapter${index + 1}.xhtml"
                        val chapterResource = Resource(html.toByteArray(), chapterFileName)
                        addSection(toc.title, chapterResource)

                        imageResources.forEach { imageResource ->
                            resources.add(imageResource)
                        }
                    }

                    bookEntity.coverImagePath.let{
                        val file = File(it)
                        if (file.exists()) {
                            val coverBytes = file.readBytes()
                            val coverResource = Resource(coverBytes, "images/cover.jpg")
                            coverImage = coverResource
                            resources.add(coverResource)
                        }
                    }
                }

                EpubWriter().write(epubBook, outputStream)
            }

            return@withContext Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext Result.failure()
        }
    }

    private fun buildChapterHtml(paragraphs: List<String>, fontSize: Float): Pair<String, List<Resource>> {
        if (paragraphs.isEmpty()) return "" to emptyList()

        val headingSizes = HeaderTextSizeUtil.calculateHeaderSizes(fontSize)
        val firstParagraph = paragraphs.first()
        val otherParagraphs = paragraphs.drop(1)

        val headerConverted = convertParagraphToHeader(firstParagraph, headingSizes)
        val imageResources = mutableListOf<Resource>()
        val htmlBuilder = StringBuilder()

        htmlBuilder.append("<html><body>")
        htmlBuilder.append(headerConverted)

        otherParagraphs.forEach { paragraphHtml ->
            if (linkPattern.containsMatchIn(paragraphHtml)) {
                val file = File(paragraphHtml)
                if (file.exists()) {
                    try {
                        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                        val outputStream = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                        val pngBytes = outputStream.toByteArray()
                        val imageFileName = "images/${file.nameWithoutExtension}.png"
                        val resource = Resource(pngBytes, imageFileName)
                        imageResources.add(resource)
                        htmlBuilder.append("<p><img src=\"../$imageFileName\" style=\"max-width:100%; height:auto;\" /></p>")
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            } else {
                htmlBuilder.append(StoredText.decodeToHtml(paragraphHtml))
            }
        }

        htmlBuilder.append("</body></html>")
        return htmlBuilder.toString() to imageResources
    }


    private fun convertParagraphToHeader(paragraphHtml: String, headingSizes: Array<Float>): String {
        val regex = Regex("font-size:\\s*(\\d+(?:\\.\\d+)?)px", RegexOption.IGNORE_CASE)
        val match = regex.find(paragraphHtml)
        val fontSizeInParagraph = match?.groupValues?.get(1)?.toFloatOrNull()
        if (fontSizeInParagraph != null) {
            val headerLevel = headingSizes.indexOfFirst { size ->
                abs(size - fontSizeInParagraph) < 1.0f
            }
            if (headerLevel != -1) {
                val headingTag = "h${headerLevel + 1}"
                val innerText = paragraphHtml
                    .replace(Regex("<p[^>]*>", RegexOption.IGNORE_CASE), "")
                    .replace("</p>", "")
                    .trim()
                return "<$headingTag>$innerText</$headingTag>"
            }
        }
        return paragraphHtml
    }
}
package com.inkspire.ebookreader.worker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
import android.graphics.Bitmap
import android.os.Build
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.inkspire.ebookreader.R
import com.inkspire.ebookreader.data.database.model.BookEntity
import com.inkspire.ebookreader.data.database.model.ChapterContentEntity
import com.inkspire.ebookreader.data.database.model.TableOfContentEntity
import com.inkspire.ebookreader.domain.model.Book
import com.inkspire.ebookreader.domain.repository.BookRepository
import com.inkspire.ebookreader.domain.repository.ChapterRepository
import com.inkspire.ebookreader.domain.repository.ImagePathRepository
import com.inkspire.ebookreader.domain.repository.TableOfContentRepository
import com.inkspire.ebookreader.util.BitmapUtil
import com.inkspire.ebookreader.util.FilePickerUtil
import com.inkspire.ebookreader.worker.epub.EpubBlock
import com.inkspire.ebookreader.worker.epub.EpubChapterDraft
import com.inkspire.ebookreader.worker.epub.EpubChapterPlanner
import com.inkspire.ebookreader.worker.epub.EpubHtmlParser
import com.inkspire.ebookreader.worker.epub.EpubImageResolver
import com.inkspire.ebookreader.worker.epub.EpubPaths
import com.inkspire.ebookreader.worker.epub.EpubPlannedFile
import com.inkspire.ebookreader.worker.epub.EpubSegment
import com.inkspire.ebookreader.worker.epub.EpubTocEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.InternalReadiumApi
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.cover
import org.readium.r2.shared.publication.services.isRestricted
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.shared.util.resource.buffered
import org.readium.r2.shared.util.toAbsoluteUrl
import org.readium.r2.shared.util.use
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import org.readium.r2.streamer.parser.audio.AudioParser
import org.readium.r2.streamer.parser.epub.EpubParser
import org.readium.r2.streamer.parser.image.ImageParser
import java.math.BigInteger
import java.security.MessageDigest

/**
 * Imports one EPUB: opens it, walks the chapters [EpubChapterPlanner] plans, and stores what [EpubHtmlParser] reads.
 *
 * A book's row has to exist before its chapters can, so an interrupted import leaves a partial book behind. Its saved
 * chapters then fall short of its total, which is how the next attempt tells it apart from a finished one.
 */
@OptIn(InternalReadiumApi::class, ExperimentalReadiumApi::class)
class EPUBImportWorker(
    private val context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params), KoinComponent {

    companion object {
        const val INPUT_URI_KEY = "input_uri"
        private const val PROGRESS_CHANNEL_ID = "book_import_progress_channel"
        private const val COMPLETION_CHANNEL_ID = "book_import_completion_channel"
        private const val MAX_BITMAP_DIMENSION = 2048
    }

    private sealed interface Outcome {
        data class Imported(val unreadableChapters: Int) : Outcome
        data class Failed(@param:StringRes val reason: Int) : Outcome
    }

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val notificationId = System.currentTimeMillis().toInt()
    private val completionNotificationId = notificationId + 1
    private val bookRepository: BookRepository by inject()
    private val tableOfContentsRepository: TableOfContentRepository by inject()
    private val chapterRepository: ChapterRepository by inject()
    private val imagePathRepository: ImagePathRepository by inject()
    private var finalBookTitle: String = ""
    private var finalBookId: String = ""
    private var insertedBook = false

    init {
        createNotificationChannel(PROGRESS_CHANNEL_ID, "Book Import Progress")
        createNotificationChannel(COMPLETION_CHANNEL_ID, "Book Import Completion")
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val initialNotification = createProgressNotificationBuilder(
            fileName = finalBookTitle,
            message = context.getString(R.string.status_starting)
        ).build()
        setForeground(getForegroundInfoCompat(initialNotification))

        val outcome = try {
            importBook()
        } catch (e: CancellationException) {
            withContext(NonCancellable) { removeInsertedBook() }
            throw e
        } catch (_: OutOfMemoryError) {
            Outcome.Failed(R.string.error_out_of_memory)
        } catch (e: Exception) {
            e.printStackTrace()
            Outcome.Failed(R.string.error_unexpected)
        }
        if (outcome is Outcome.Failed) removeInsertedBook()

        sendCompletionNotification(outcome)
        Result.success()
    }

    private suspend fun importBook(): Outcome {
        val httpClient = DefaultHttpClient()
        val assetRetriever = AssetRetriever(
            contentResolver = context.contentResolver,
            httpClient = httpClient
        )
        val publicationOpener = PublicationOpener(
            publicationParser = DefaultPublicationParser(
                context = context,
                httpClient = httpClient,
                assetRetriever = assetRetriever,
                pdfFactory = null,
                additionalParsers = listOf(
                    EpubParser(),
                    AudioParser(
                        assetSniffer = assetRetriever
                    ),
                    ImageParser(
                        assetRetriever = assetRetriever
                    ),
                )
            )
        )
        val uriString = inputData.getString(INPUT_URI_KEY) ?: return Outcome.Failed(R.string.error_read_file)
        val url = uriString.toUri().toAbsoluteUrl() ?: return Outcome.Failed(R.string.error_read_file)
        val asset = assetRetriever.retrieve(url).getOrElse { return Outcome.Failed(R.string.error_read_file) }
        val publication = publicationOpener.open(asset, allowUserInteraction = false)
            .getOrElse {
                asset.close()
                return Outcome.Failed(R.string.error_invalid_format)
            }
        return try {
            importPublication(publication, uriString)
        } finally {
            publication.close()
        }
    }

    private suspend fun importPublication(publication: Publication, uriString: String): Outcome {
        if (publication.isRestricted) return Outcome.Failed(R.string.error_book_protected)

        updateProgressNotification(finalBookTitle, context.getString(R.string.status_analyzing), null)
        val plan = EpubChapterPlanner.plan(
            spine = publication.readingOrder.map { it.href.toString() },
            toc = flattenLinks(publication.tableOfContents).map { EpubTocEntry(it.href.toString(), it.title) }
        )
        if (plan.isEmpty()) return Outcome.Failed(R.string.error_no_content)

        val authors = publication.metadata.authors.map { it.name }
        finalBookTitle = publication.metadata.title?.takeIf { it.isNotBlank() }
            ?: FilePickerUtil.getFileName(context, uriString.toUri()).substringBeforeLast('.')
        finalBookId = bookIdOf(finalBookTitle + authors.joinToString(prefix = "|", separator = "|"))

        val existing = findExistingBook(authors)
        if (existing != null) {
            if (isComplete(existing)) return Outcome.Failed(R.string.error_book_already_exists)
            removeBook(existing)
        }

        insertBook(publication, uriString, authors, plan.size)

        val linksByFile = (publication.readingOrder + publication.resources)
            .associateBy { EpubPaths.normalize(it.href.toString()) }
        val imageResolver = EpubImageResolver(linksByFile.keys)

        var chapterCount = 0
        var unreadable = 0
        plan.forEachIndexed { fileIndex, plannedFile ->
            val progress = (fileIndex + 1) * 100 / plan.size
            val drafts = readDrafts(publication, plannedFile, linksByFile)
            if (drafts == null) {
                unreadable += plannedFile.entries.size.coerceAtLeast(1)
                return@forEachIndexed
            }
            drafts.forEach { draft ->
                saveChapter(publication, plannedFile.file, draft, chapterCount, imageResolver, linksByFile, progress)
                chapterCount++
            }
        }

        if (chapterCount == 0) return Outcome.Failed(R.string.error_no_content)
        bookRepository.saveBookInfoTotalChapter(finalBookId, chapterCount)
        return Outcome.Imported(unreadable)
    }

    /**
     * The chapters a file becomes, or null when it could not be read. A blank file nothing in the table of contents names
     * gets none: books use them as separator pages.
     */
    private suspend fun readDrafts(
        publication: Publication,
        plannedFile: EpubPlannedFile,
        linksByFile: Map<String, Link>
    ): List<EpubChapterDraft>? {
        val link = linksByFile[plannedFile.file] ?: return null
        val bytes = readBytes(publication, link) ?: return null
        val segments = if (link.mediaType?.isHtml == false) {
            if (link.mediaType?.isBitmap != true) return emptyList()
            listOf(EpubSegment(null, listOf(EpubBlock.Image("/" + plannedFile.file))))
        } else {
            EpubHtmlParser.parse(bytes, plannedFile.entries.mapNotNull { it.anchor })
        }
        val drafts = EpubChapterPlanner.assemble(plannedFile.entries, segments)
        return if (plannedFile.entries.isEmpty()) drafts.filter { it.blocks.isNotEmpty() } else drafts
    }

    private suspend fun saveChapter(
        publication: Publication,
        file: String,
        draft: EpubChapterDraft,
        index: Int,
        imageResolver: EpubImageResolver,
        linksByFile: Map<String, Link>,
        progress: Int,
    ) {
        val title = draft.title
            ?: EpubHtmlParser.firstHeadingText(draft.blocks)
            ?: context.getString(R.string.placeholder_chapter_title_fmt, index + 1)
        updateProgressNotification(
            fileName = finalBookTitle,
            message = context.getString(R.string.status_processing_item_fmt, title),
            progress = progress
        )

        val imagePaths = mutableListOf<String>()
        val content = draft.blocks.mapIndexedNotNull { blockIndex, block ->
            when (block) {
                is EpubBlock.Text -> block.stored
                is EpubBlock.Image -> {
                    val bytes = EpubImageResolver.decodeDataUri(block.source)
                        ?: imageResolver.resolve(file, block.source)
                            ?.let { linksByFile[it] }
                            ?.let { readBytes(publication, it) }
                    bytes?.let { saveImage(it, index, blockIndex) }?.also(imagePaths::add)
                }
            }
        }

        tableOfContentsRepository.saveTableOfContent(
            TableOfContentEntity(bookId = finalBookId, title = title, index = index)
        )
        chapterRepository.saveChapterContent(
            ChapterContentEntity(tocId = index, bookId = finalBookId, chapterTitle = title, content = content)
        )
        if (imagePaths.isNotEmpty()) imagePathRepository.saveImagePath(finalBookId, imagePaths)
    }

    private suspend fun readBytes(publication: Publication, link: Link): ByteArray? =
        publication.get(link)?.buffered()?.use { resource -> resource.read().getOrNull() }

    /** Transparent images stay PNG: JPEG has no alpha, and Android fills it with black. */
    private fun saveImage(bytes: ByteArray, chapterIndex: Int, blockIndex: Int): String? {
        val bitmap = bytes.inputStream().use {
            BitmapUtil.decodeSampledBitmapFromStream(it, MAX_BITMAP_DIMENSION, MAX_BITMAP_DIMENSION)
        } ?: return null
        val path = BitmapUtil.saveBitmapToPrivateStorage(
            context = context,
            bitmap = bitmap,
            compressType = if (bitmap.hasAlpha()) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG,
            quality = 80,
            filenameWithoutExtension = "image_${finalBookId}_${chapterIndex}_$blockIndex"
        )
        bitmap.recycle()
        return path.takeUnless { it.startsWith("error") }
    }

    private fun flattenLinks(links: List<Link>): List<Link> =
        links.flatMap { listOf(it) + flattenLinks(it.children) }

    private fun bookIdOf(identity: String): String =
        BigInteger(1, MessageDigest.getInstance("MD5").digest(identity.toByteArray()))
            .toString(16)
            .padStart(32, '0')

    /** Books imported by older versions are identified by title alone, so they are looked up that way too. */
    private suspend fun findExistingBook(authors: List<String>): Book? =
        bookRepository.getBook(finalBookId)
            ?: bookRepository.getBook(bookIdOf(finalBookTitle))?.takeIf { it.authors == authors }

    private suspend fun isComplete(book: Book): Boolean =
        tableOfContentsRepository.getTableOfContents(book.id).size >= book.totalChapter

    private suspend fun insertBook(
        publication: Publication,
        uriString: String,
        authors: List<String>,
        plannedChapters: Int
    ) {
        val coverImagePath = publication.cover()?.let { bitmap ->
            BitmapUtil.saveBitmapToPrivateStorage(
                context = context,
                bitmap = bitmap,
                compressType = Bitmap.CompressFormat.JPEG,
                quality = 80,
                filenameWithoutExtension = "cover_$finalBookId"
            )
        } ?: "error"
        bookRepository.insertBook(
            BookEntity(
                bookId = finalBookId,
                title = finalBookTitle,
                coverImagePath = coverImagePath,
                authors = authors,
                description = publication.metadata.description,
                totalChapter = plannedChapters,
                currentChapter = 0,
                currentParagraph = 0,
                isFavorite = false,
                storagePath = uriString,
                isEditable = false,
                fileType = "epub"
            )
        )
        insertedBook = true
        bookRepository.updateRecentRead(finalBookId)
        imagePathRepository.saveImagePath(finalBookId, listOf(coverImagePath))
    }

    private suspend fun removeInsertedBook() {
        if (!insertedBook) return
        bookRepository.getBook(finalBookId)?.let { removeBook(it) }
        insertedBook = false
    }

    private suspend fun removeBook(book: Book) {
        imagePathRepository.getImagePathsByBookIds(listOf(book.id)).forEach {
            BitmapUtil.deleteImageFromPrivateStorage(it.imagePath)
        }
        imagePathRepository.deleteByBookIds(listOf(book.id))
        bookRepository.deleteBooks(listOf(book))
    }

    private fun createNotificationChannel(
        channelId: String,
        channelName: String
    ) {
        if (notificationManager.getNotificationChannel(channelId) == null) {
            val importance =
                if (channelId == PROGRESS_CHANNEL_ID) NotificationManager.IMPORTANCE_LOW else NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(channelId, channelName, importance).apply {
                description = context.getString(R.string.channel_import_desc)
                if (channelId == PROGRESS_CHANNEL_ID) setSound(null, null)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createProgressNotificationBuilder(
        fileName: String,
        message: String
    ): NotificationCompat.Builder {
        return NotificationCompat.Builder(context, PROGRESS_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.notification_title_importing_fmt, fileName.take(35) + "..."))
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
    }

    private suspend fun updateProgressNotification(
        fileName: String,
        message: String,
        progress: Int?
    ) {
        val builder = createProgressNotificationBuilder(fileName, message)
        if (progress != null) builder.setProgress(100, progress.coerceIn(0, 100), false)
        else builder.setProgress(0, 0, true)
        try {
            setForeground(getForegroundInfoCompat(builder.build()))
        } catch (_: Exception) {
            notificationManager.notify(notificationId, builder.build())
        }
    }

    private fun sendCompletionNotification(outcome: Outcome) {
        val displayTitle = finalBookTitle.ifEmpty { context.getString(R.string.error_default_title) }
        val title = context.getString(
            if (outcome is Outcome.Imported) R.string.result_success_title else R.string.result_failed_title
        )
        val text = when (outcome) {
            is Outcome.Imported -> if (outcome.unreadableChapters == 0) {
                context.getString(R.string.result_success_msg_fmt, displayTitle)
            } else {
                context.resources.getQuantityString(
                    R.plurals.result_partial_msg_fmt,
                    outcome.unreadableChapters,
                    displayTitle,
                    outcome.unreadableChapters
                )
            }
            is Outcome.Failed ->
                context.getString(R.string.error_detailed_fmt, displayTitle, context.getString(outcome.reason))
        }
        val builder = NotificationCompat.Builder(context, COMPLETION_CHANNEL_ID).apply {
            setSmallIcon(R.mipmap.ic_launcher_foreground)
            setContentTitle(title)
            setContentText(text)
            setStyle(
                NotificationCompat.BigTextStyle().bigText(text)
            )
            setPriority(NotificationCompat.PRIORITY_DEFAULT)
            setAutoCancel(true)
        }
        notificationManager.notify(completionNotificationId, builder.build())
        notificationManager.cancel(notificationId)
    }

    private fun getForegroundInfoCompat(notification: Notification): ForegroundInfo {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }
}

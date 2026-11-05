package com.inkspire.ebookreader.worker.epub

import java.util.Base64

/**
 * Finds the file an image reference means, given every file in the book as normalized paths.
 *
 * The reference is resolved against its chapter first. The looser matches are for books whose references are simply wrong,
 * and a filename match counts only when one file has that name, so that it can never pick a different picture.
 */
internal class EpubImageResolver(files: Collection<String>) {
    private val exact = files.toSet()
    private val byLowercase = files.groupBy { it.lowercase() }
    private val byFilename = files.groupBy { it.substringAfterLast('/').lowercase() }

    fun resolve(chapterFile: String, source: String): String? {
        val path = EpubPaths.resolve(chapterFile, source) ?: return null
        if (path in exact) return path
        byLowercase[path.lowercase()]?.singleOrNull()?.let { return it }
        return byFilename[path.substringAfterLast('/').lowercase()]?.singleOrNull()
    }

    companion object {
        /** The bytes of a base64 `data:` image, or null when [source] is anything else. */
        fun decodeDataUri(source: String): ByteArray? {
            if (!source.startsWith("data:", ignoreCase = true)) return null
            val header = source.substringBefore(',')
            if (!header.endsWith(";base64", ignoreCase = true)) return null
            return try {
                Base64.getMimeDecoder().decode(source.substringAfter(','))
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }
}

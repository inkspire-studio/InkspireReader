package com.inkspire.ebookreader.worker.epub

import java.net.URLDecoder

/**
 * Compares the file references inside an EPUB, which reach the same file through different spellings: relative to the
 * chapter, percent-encoded or not, with `./` and `../`, with a fragment.
 */
internal object EpubPaths {
    private val scheme = Regex("""^[a-zA-Z][a-zA-Z0-9+.-]*:""")

    /** The file part of [href], without fragment or query, decoded and with `.` and `..` resolved. */
    fun normalize(href: String): String = collapse(decode(stripSuffix(href)))

    /** The fragment of [href], decoded, or null when it has none. */
    fun fragment(href: String): String? =
        href.substringAfter('#', "").takeIf { it.isNotEmpty() }?.let(::decode)

    /** Where [reference] points when found inside [baseFile], or null when it leaves the book, as a web link does. */
    fun resolve(baseFile: String, reference: String): String? {
        if (scheme.containsMatchIn(reference)) return null
        val path = decode(stripSuffix(reference)).trim()
        if (path.isEmpty()) return null
        if (path.startsWith("/")) return collapse(path)
        return collapse(baseFile.substringBeforeLast('/', "") + "/" + path)
    }

    private fun stripSuffix(href: String) = href.substringBefore('#').substringBefore('?')

    /** The `+` is protected because URLDecoder reads it as a space, and in a path it is a plus. */
    private fun decode(text: String): String = try {
        URLDecoder.decode(text.replace("+", "%2B"), "UTF-8")
    } catch (_: IllegalArgumentException) {
        text
    }

    private fun collapse(path: String): String {
        val parts = ArrayDeque<String>()
        path.split('/').forEach { part ->
            when (part) {
                "", "." -> {}
                ".." -> parts.removeLastOrNull()
                else -> parts.addLast(part)
            }
        }
        return parts.joinToString("/")
    }
}

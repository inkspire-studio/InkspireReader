package com.inkspire.ebookreader.worker.epub

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpubImageResolverTest {
    private val resolver = EpubImageResolver(
        listOf("OEBPS/img/11.jpg", "OEBPS/1.jpg", "OEBPS/img/a.jpg", "OEBPS/img/my pic.jpg", "OEBPS/x/same.png", "OEBPS/y/same.png")
    )
    private val chapter = "OEBPS/text/c1.xhtml"

    @Test
    fun `a parent reference is not confused with a longer filename`() {
        assertEquals("OEBPS/1.jpg", resolver.resolve(chapter, "../1.jpg"))
    }

    @Test
    fun `every spelling of a path reaches the file`() {
        listOf("../img/a.jpg", "./../img/a.jpg", "/OEBPS/img/a.jpg", "../img/a.jpg#page1", "../img/a.jpg?v=2", "../IMG/A.JPG")
            .forEach { assertEquals(it, "OEBPS/img/a.jpg", resolver.resolve(chapter, it)) }
    }

    @Test
    fun `spaces match encoded or not`() {
        assertEquals("OEBPS/img/my pic.jpg", resolver.resolve(chapter, "../img/my%20pic.jpg"))
        assertEquals("OEBPS/img/my pic.jpg", resolver.resolve(chapter, "../img/my pic.jpg"))
    }

    @Test
    fun `a wrong folder still finds a file only one has that name`() {
        assertEquals("OEBPS/img/a.jpg", resolver.resolve(chapter, "images/a.jpg"))
    }

    @Test
    fun `a wrong folder with an ambiguous name finds nothing`() {
        assertNull(resolver.resolve(chapter, "images/same.png"))
    }

    @Test
    fun `a web link is not a file`() {
        assertNull(resolver.resolve(chapter, "https://example.com/a.jpg"))
    }

    @Test
    fun `a base64 data image decodes and anything else does not`() {
        assertArrayEquals(byteArrayOf(1, 2, 3), EpubImageResolver.decodeDataUri("data:image/png;base64,AQID"))
        assertNull(EpubImageResolver.decodeDataUri("data:image/svg+xml,<svg/>"))
        assertNull(EpubImageResolver.decodeDataUri("img/a.jpg"))
    }
}

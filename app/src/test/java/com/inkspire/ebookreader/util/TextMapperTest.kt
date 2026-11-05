package com.inkspire.ebookreader.util

import org.junit.Assert.assertEquals
import org.junit.Test

class TextMapperTest {
    private fun shown(paragraph: String) = TextMapper.convertToAnnotatedStrings(paragraph).text

    @Test
    fun `every mark the parser stores is drawn, not shown`() {
        assertEquals(
            "E = mc2 and H2O old x bold it un",
            shown("E = mc<sup>2</sup> and H<sub>2</sub>O <s>old</s> <code>x</code> <b>bold</b> <i>it</i> <u>un</u>")
        )
    }

    @Test
    fun `marks from older imports still draw`() {
        assertEquals("a b c", shown("<strong>a</strong> <em>b</em> <del>c</del>"))
    }

    @Test
    fun `a stray heading tag from an older import is not shown`() {
        assertEquals("One", shown("One</h2>"))
    }

    @Test
    fun `angle brackets that are not known tags stay as text`() {
        assertEquals("You got <Sword> and a < b", shown("You got <Sword> and a < b"))
    }

    @Test
    fun `an encoded less-than shows as text and never as a tag`() {
        assertEquals("literal <b>tag", shown(StoredText.encode("literal <b>tag")))
    }

    @Test
    fun `the map points each shown character at its stored position`() {
        val mapped = TextMapper.convertToAnnotatedStringWithMap("a<b>b</b>c")
        assertEquals(listOf(0, 4, 9, 10), mapped.displayToRawMap)
    }
}

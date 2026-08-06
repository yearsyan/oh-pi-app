package io.github.yearsyan.ohpi.net

import kotlin.test.Test
import kotlin.test.assertEquals

class UrlEncodeTest {

    @Test
    fun urlEncodeKeepsUnreservedAscii() {
        assertEquals("abcXYZ019-_.~", urlEncode("abcXYZ019-_.~"))
    }

    @Test
    fun urlEncodeEncodesAsciiPunctuation() {
        assertEquals("a%20b%26c%3Dd%3Fe", urlEncode("a b&c=d?e"))
        assertEquals("%2FUsers%2Fu", urlEncode("/Users/u"))
    }

    @Test
    fun urlEncodeEncodesNonAsciiAsUtf8Bytes() {
        // '，' is U+FF0C; its UTF-8 form is EF BC 8C. Truncating the code
        // unit to its low byte produced %0C (form feed) and broke paths.
        assertEquals(
            "%E6%88%91%E9%83%BD%E8%BD%AE%E5%9B%9E%E4%B8%89%E4%B8%96%EF%BC%8C%E5%B8%88%E7%88%B6%E4%BD%A0%E6%80%8E%E4%B9%88%E8%BF%98%E6%B4%BB%E7%9D%80.txt",
            urlEncode("我都轮回三世，师父你怎么还活着.txt"),
        )
    }

    @Test
    fun urlEncodeEncodesFullWidthPunctuationWithoutTruncation() {
        assertEquals("%EF%BC%8C", urlEncode("，")) // U+FF0C
        assertEquals("%E3%80%82", urlEncode("。")) // U+3002
        assertEquals("%E3%80%81", urlEncode("、")) // U+3001
    }
}

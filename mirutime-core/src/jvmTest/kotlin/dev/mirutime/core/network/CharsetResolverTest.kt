package dev.mirutime.core.network

import org.junit.Assert.assertEquals
import org.junit.Test

class CharsetResolverTest {

    @Test
    fun `metaCharset wins over everything`() {
        assertEquals(
            "windows-1251",
            pluginCharset(
                metaCharset = "windows-1251",
                contentType = "text/html; charset=koi8-r",
                htmlHead = """<meta charset="utf-8">"""
            )
        )
    }

    @Test
    fun `contentType charset parsed case-insensitively with quotes`() {
        assertEquals(
            "koi8-r",
            pluginCharset(null, "text/html; CHARSET=koi8-r", null)
        )
        assertEquals(
            "ISO-8859-1",
            pluginCharset(null, "text/html; charset=\"ISO-8859-1\"", null)
        )
    }

    @Test
    fun `meta tag in htmlHead parsed when no header charset`() {
        assertEquals(
            "windows-1251",
            pluginCharset(null, "text/html", """<meta charset="windows-1251">""")
        )
        assertEquals(
            "koi8-r",
            pluginCharset(
                null,
                "text/html",
                """<meta http-equiv="Content-Type" content="text/html; charset=koi8-r">"""
            )
        )
    }

    @Test
    fun `falls back to UTF-8`() {
        assertEquals("UTF-8", pluginCharset(null, null, "<html></html>"))
        assertEquals("UTF-8", pluginCharset("", "", ""))
    }

    @Test
    fun `blank metaCharset skipped to contentType`() {
        assertEquals(
            "shift_jis",
            pluginCharset("  ", "text/html; charset=shift_jis", null)
        )
    }

    @Test
    fun `meta charset beyond first 1KB of head ignored`() {
        val padding = "a".repeat(1100)
        assertEquals(
            "UTF-8",
            pluginCharset(null, null, "$padding<meta charset=\"windows-1251\">")
        )
    }
}

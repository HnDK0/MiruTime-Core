package dev.mirutime.core.host

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FakeCookieStoreTest {

    @Test
    fun setThenGetSameHostReturnsCookie() = runTest {
        val store = FakeCookieStore()
        val url = "https://example.com/login"

        store.setFromResponse(url, listOf("session=abc; HttpOnly; Path=/"))

        val result = store.get(url)
        assertEquals(1, result.size)
        assertEquals("session", result[0].name)
        assertEquals("abc", result[0].value)
        assertEquals("example.com", result[0].domain)
    }

    @Test
    fun getOtherHostReturnsEmpty() = runTest {
        val store = FakeCookieStore()
        store.setFromResponse("https://example.com/", listOf("session=abc"))

        assertTrue(store.get("https://other.example.org/").isEmpty())
    }

    @Test
    fun clearRemovesOnlyThatHost() = runTest {
        val store = FakeCookieStore()
        store.setFromResponse("https://example.com/", listOf("session=abc"))
        store.setFromResponse("https://other.example.org/", listOf("session=xyz"))

        store.clear("example.com")

        assertTrue(store.get("https://example.com/").isEmpty())
        assertEquals(1, store.get("https://other.example.org/").size)
    }

    // NoveLA clearCookiesForDomain: the CF re-solve path removes ONLY
    // cf_clearance — unrelated cookies (sort order, locale) survive.
    @Test
    fun clearCookiesRemovesOnlyNamedCookies() = runTest {
        val store = FakeCookieStore()
        store.setFromResponse("https://example.com/", listOf("cf_clearance=dead", "session=abc"))

        store.clearCookies("example.com", listOf("cf_clearance"))

        val remaining = store.get("https://example.com/")
        assertEquals(1, remaining.size)
        assertEquals("session", remaining[0].name)
    }
}

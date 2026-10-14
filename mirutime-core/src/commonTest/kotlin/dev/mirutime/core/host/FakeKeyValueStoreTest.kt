package dev.mirutime.core.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FakeKeyValueStoreTest {

    @Test
    fun namespacesAreIsolatedForSameKey() {
        val store = FakeKeyValueStore()
        store.set("plugin-a", "token", "aaa")
        store.set("plugin-b", "token", "bbb")

        assertEquals("aaa", store.get("plugin-a", "token"))
        assertEquals("bbb", store.get("plugin-b", "token"))
        assertNull(store.get("plugin-c", "token"))
    }

    @Test
    fun removeOnlyAffectsGivenNamespace() {
        val store = FakeKeyValueStore()
        store.set("plugin-a", "token", "aaa")
        store.set("plugin-b", "token", "bbb")

        store.remove("plugin-a", "token")

        assertNull(store.get("plugin-a", "token"))
        assertEquals("bbb", store.get("plugin-b", "token"))
    }
}

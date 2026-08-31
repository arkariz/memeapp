package com.arkarizdev.bonked.core.gif

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GiphyMemeGifProviderTest {
    @Test
    fun `parseSearchResults reads every entry with a usable rendition`() {
        val body = """
            {"data": [
                {"id": "abc", "images": {"fixed_height": {"url": "http://x/abc.gif"}}, "analytics": {"onsent": {"url": "http://x/onsent-abc"}}},
                {"id": "def", "images": {"downsized": {"url": "http://x/def.gif"}}}
            ]}
        """.trimIndent()
        val results = GiphyMemeGifProvider.parseSearchResults(body)
        assertEquals(2, results.size)
        assertEquals(MemeGifResult(id = "abc", downloadUrl = "http://x/abc.gif", onSentUrl = "http://x/onsent-abc"), results[0])
        assertEquals(MemeGifResult(id = "def", downloadUrl = "http://x/def.gif", onSentUrl = null), results[1])
    }

    @Test
    fun `parseSearchResults prefers fixed_height over downsized when both exist`() {
        val body = """
            {"data": [
                {"id": "abc", "images": {
                    "fixed_height": {"url": "http://x/fixed.gif"},
                    "downsized": {"url": "http://x/downsized.gif"}
                }}
            ]}
        """.trimIndent()
        assertEquals("http://x/fixed.gif", GiphyMemeGifProvider.parseSearchResults(body)[0].downloadUrl)
    }

    @Test
    fun `parseSearchResults skips an entry with no usable rendition url`() {
        val body = """
            {"data": [
                {"id": "no-rendition", "images": {"preview": {"url": "http://x/preview.gif"}}},
                {"id": "usable", "images": {"fixed_height": {"url": "http://x/usable.gif"}}}
            ]}
        """.trimIndent()
        val results = GiphyMemeGifProvider.parseSearchResults(body)
        assertEquals(listOf("usable"), results.map { it.id })
    }

    @Test
    fun `parseSearchResults returns empty list for a zero-result search`() {
        assertEquals(emptyList<MemeGifResult>(), GiphyMemeGifProvider.parseSearchResults("""{"data": []}"""))
    }

    @Test
    fun `parseSearchResults returns empty list when data is missing entirely`() {
        assertEquals(emptyList<MemeGifResult>(), GiphyMemeGifProvider.parseSearchResults("""{"meta": {}}"""))
    }

    @Test
    fun `parseSearchResults tolerates a malformed entry without failing the whole parse`() {
        val body = """
            {"data": [
                {"id": "malformed"},
                {"id": "usable", "images": {"fixed_height": {"url": "http://x/usable.gif"}}}
            ]}
        """.trimIndent()
        val results = GiphyMemeGifProvider.parseSearchResults(body)
        assertEquals(listOf("usable"), results.map { it.id })
    }

    @Test
    fun `parseSearchResults with several usable entries supports random variety selection`() {
        val body = """
            {"data": [
                {"id": "a", "images": {"fixed_height": {"url": "http://x/a.gif"}}},
                {"id": "b", "images": {"fixed_height": {"url": "http://x/b.gif"}}},
                {"id": "c", "images": {"fixed_height": {"url": "http://x/c.gif"}}}
            ]}
        """.trimIndent()
        val results = GiphyMemeGifProvider.parseSearchResults(body)
        assertEquals(3, results.size)
        assertTrue(results.map { it.id }.containsAll(listOf("a", "b", "c")))
    }
}

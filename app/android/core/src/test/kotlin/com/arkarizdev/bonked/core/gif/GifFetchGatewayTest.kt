package com.arkarizdev.bonked.core.gif

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** In-memory fake — no network, so these tests run fast and deterministically. */
private class FakeMemeGifProvider(
    private val searchResult: MemeGifResult? = null,
    private val searchDelayMs: Long = 0,
    private val searchThrows: Boolean = false,
    private val downloadBytes: ByteArray? = byteArrayOf(1, 2, 3),
) : MemeGifProvider {
    var searchCalls = 0
        private set
    var registerUsedCalls = 0
        private set

    override suspend fun search(queries: List<String>): MemeGifResult? {
        searchCalls++
        if (searchDelayMs > 0) delay(searchDelayMs)
        if (searchThrows) throw RuntimeException("network down")
        return searchResult
    }

    override suspend fun downloadBytes(url: String): ByteArray? = downloadBytes

    override suspend fun registerUsed(onSentUrl: String) {
        registerUsedCalls++
    }
}

class GifFetchGatewayTest {
    private fun tempCache(): GifCache {
        val dir = File.createTempFile("gif_fetch_gateway_test", "").apply {
            delete()
            mkdirs()
        }
        return GifCache(dir)
    }

    @Test
    fun `null provider returns Unavailable without calling the fake`() = runTest {
        val fake = FakeMemeGifProvider()
        val gateway = GifFetchGateway(provider = null, cache = tempCache())

        val result = gateway.fetch("side_eye", listOf("side eye"), sessionId = 1L)

        assertEquals(GifFetchGateway.FetchResult.Unavailable, result)
        assertEquals(0, fake.searchCalls)
    }

    @Test
    fun `empty queries returns Unavailable without calling search`() = runTest {
        val fake = FakeMemeGifProvider(searchResult = MemeGifResult("id", "http://x", null))
        val gateway = GifFetchGateway(fake, tempCache())

        val result = gateway.fetch("side_eye", emptyList(), sessionId = 1L)

        assertEquals(GifFetchGateway.FetchResult.Unavailable, result)
        assertEquals(0, fake.searchCalls)
    }

    @Test
    fun `search returning null yields Unavailable`() = runTest {
        val fake = FakeMemeGifProvider(searchResult = null)
        val gateway = GifFetchGateway(fake, tempCache())

        val result = gateway.fetch("side_eye", listOf("side eye"), sessionId = 1L)

        assertEquals(GifFetchGateway.FetchResult.Unavailable, result)
    }

    @Test
    fun `search throwing yields Unavailable, no crash`() = runTest {
        val fake = FakeMemeGifProvider(searchThrows = true)
        val gateway = GifFetchGateway(fake, tempCache())

        val result = gateway.fetch("side_eye", listOf("side eye"), sessionId = 1L)

        assertEquals(GifFetchGateway.FetchResult.Unavailable, result)
    }

    @Test
    fun `search exceeding the budget times out to Unavailable, not a hang`() = runTest {
        val fake = FakeMemeGifProvider(
            searchResult = MemeGifResult("id", "http://x", null),
            searchDelayMs = 500,
        )
        val gateway = GifFetchGateway(fake, tempCache(), fetchBudgetMs = 20)

        val result = gateway.fetch("side_eye", listOf("side eye"), sessionId = 1L)

        assertEquals(GifFetchGateway.FetchResult.Unavailable, result)
    }

    @Test
    fun `successful fetch caches the bytes and returns Fetched with gifId and onSentUrl`() = runTest {
        val fake = FakeMemeGifProvider(
            searchResult = MemeGifResult(id = "abc123", downloadUrl = "http://x/gif", onSentUrl = "http://x/onsent"),
            downloadBytes = byteArrayOf(9, 9, 9),
        )
        val cache = tempCache()
        val gateway = GifFetchGateway(fake, cache)

        val result = gateway.fetch("side_eye", listOf("side eye"), sessionId = 7L)

        val fetched = result as? GifFetchGateway.FetchResult.Fetched
        assertTrue("expected Fetched, got $result", fetched != null)
        assertEquals("abc123", fetched!!.gifId)
        assertEquals("http://x/onsent", fetched.onSentUrl)
        assertEquals(cache.fileFor(7L, "side_eye").path, fetched.localPath)
        assertEquals(listOf(9.toByte(), 9.toByte(), 9.toByte()), File(fetched.localPath).readBytes().toList())
    }

    @Test
    fun `download failure after a successful search still yields Unavailable`() = runTest {
        val fake = FakeMemeGifProvider(
            searchResult = MemeGifResult("id", "http://x", null),
            downloadBytes = null,
        )
        val gateway = GifFetchGateway(fake, tempCache())

        val result = gateway.fetch("side_eye", listOf("side eye"), sessionId = 1L)

        assertEquals(GifFetchGateway.FetchResult.Unavailable, result)
    }

    @Test
    fun `registerUsed no-ops with a null provider`() = runTest {
        val gateway = GifFetchGateway(provider = null, cache = tempCache())
        gateway.registerUsed("http://x/onsent") // must not throw
    }

    @Test
    fun `registerUsed delegates to the provider`() = runTest {
        val fake = FakeMemeGifProvider()
        val gateway = GifFetchGateway(fake, tempCache())

        gateway.registerUsed("http://x/onsent")

        assertEquals(1, fake.registerUsedCalls)
    }
}

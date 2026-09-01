package com.arkarizdev.bonked.core.roast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoastPackManifestTest {
    @Test
    fun `parse reads a well-formed manifest`() {
        val json = """{"latest":4,"url":"https://example.com/pack_v4.zip","sha256":"abc123"}"""
        val manifest = RoastPackManifest.parse(json)

        assertEquals(RoastPackManifest(latest = 4, url = "https://example.com/pack_v4.zip", sha256 = "abc123"), manifest)
    }

    @Test
    fun `parse returns null for malformed json`() {
        assertNull(RoastPackManifest.parse("not json"))
    }

    @Test
    fun `parse returns null when a required field is missing`() {
        assertNull(RoastPackManifest.parse("""{"latest":4,"url":"https://example.com/pack_v4.zip"}"""))
    }
}

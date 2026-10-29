package io.github.aviato28.scopecreep.update

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class UpdateLogicTest {
    private fun v(s: String) = Semver.parse(s)!!

    @Test fun ordering() {
        val ordered = listOf("0.9.0", "1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta", "1.0.0-beta.2", "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0", "1.0.1", "1.10.0", "2.0.0")
        for (i in ordered.indices) for (j in ordered.indices)
            assertEquals(i.compareTo(j), v(ordered[i]).compareTo(v(ordered[j])).coerceIn(-1, 1), "${ordered[i]} vs ${ordered[j]}")
    }

    @Test fun parsing() {
        assertEquals(0, v("v1.2.3").compareTo(v("1.2.3+build5")))
        assertNull(Semver.parse("1.2"))
        assertNull(Semver.parse("latest"))
    }

    @Test fun newer() {
        assertTrue(isNewer("v0.3.0", "0.2.9"))
        assertFalse(isNewer("v0.3.0", "0.3.0"))
        assertFalse(isNewer("v0.3.0-beta.1", "0.3.0"))
        assertTrue(isNewer("v0.3.0", "0.3.0-beta.1"))
        assertTrue(isNewer("v0.1.0", "0.0.0-dev"))
        assertFalse(isNewer("v0.3.0", "0.3.0-preview"))
        assertFalse(isNewer("garbage", "0.3.0"))
    }

    @Test fun checksumLines() {
        val h1 = "a".repeat(64); val h2 = "B".repeat(64)
        val text = "$h1  scope-creep-0.3.0.apk\n$h2 *other.zip\nnot a line\n"
        assertEquals(h1, Checksums.find(text, "scope-creep-0.3.0.apk"))
        assertEquals("b".repeat(64), Checksums.find(text, "other.zip"))
        assertNull(Checksums.find(text, "missing.apk"))
        assertNull(Checksums.find("abc  scope-creep-0.3.0.apk", "scope-creep-0.3.0.apk"))
    }
}

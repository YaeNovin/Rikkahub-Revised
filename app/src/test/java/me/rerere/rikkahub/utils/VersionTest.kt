package me.rerere.rikkahub.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionTest {
    @Test
    fun `basic version comparison`() {
        assertTrue(Version("1.0.0") < Version("2.0.0"))
        assertTrue(Version("1.0.0") < Version("1.1.0"))
        assertTrue(Version("1.0.0") < Version("1.0.1"))
        assertEquals(0, Version("1.0.0").compareTo(Version("1.0.0")))
    }

    @Test
    fun `different length versions`() {
        assertEquals(0, Version("1.0").compareTo(Version("1.0.0")))
        assertTrue(Version("1.0") < Version("1.0.1"))
    }

    @Test
    fun `v prefix is ignored`() {
        assertEquals(0, Version("v2.4.9").compareTo(Version("2.4.9")))
        assertEquals(0, Version("V2.4.9").compareTo(Version("2.4.9")))
    }

    @Test
    fun `development build is lower than revised release`() {
        assertTrue(Version("2.4.8dev") < Version("v2.4.8-revised.1"))
        assertTrue(
            Version("2.4.8dev-20260818-120000-debug") < Version("2.4.8-revised.1")
        )
    }

    @Test
    fun `revised releases compare by revision number`() {
        assertTrue(Version("2.4.8-revised.1") < Version("v2.4.8-revised.2"))
        assertTrue(Version("2.4.8-revised.9") < Version("2.4.8-revised.10"))
    }

    @Test
    fun `prerelease has lower precedence than release`() {
        assertTrue(Version("1.0.0-alpha") < Version("1.0.0"))
        assertTrue(Version("1.0.0-beta") < Version("1.0.0"))
        assertTrue(Version("1.0.0-rc.1") < Version("1.0.0"))
    }

    @Test
    fun `semver prerelease ordering`() {
        val versions = listOf(
            Version("1.0.0-alpha"),
            Version("1.0.0-alpha.1"),
            Version("1.0.0-beta"),
            Version("1.0.0-beta.2"),
            Version("1.0.0-rc.1"),
            Version("1.0.0"),
        )
        versions.zipWithNext().forEach { (current, next) ->
            assertTrue("${current.value} should be < ${next.value}", current < next)
        }
    }

    @Test
    fun `build metadata is ignored`() {
        assertEquals(0, Version("1.0.0+build1").compareTo(Version("1.0.0+build2")))
        assertEquals(0, Version("1.0.0-alpha+build").compareTo(Version("1.0.0-alpha")))
    }

    @Test
    fun `string extension operators`() {
        assertTrue("1.0.0" < Version("2.0.0"))
        assertTrue(Version("2.0.0") > "1.0.0")
    }
}

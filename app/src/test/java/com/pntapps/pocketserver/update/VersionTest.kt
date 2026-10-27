package com.pntapps.pocketserver.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionTest {
    private fun v(s: String) = requireNotNull(Version.parse(s)) { s }

    @Test fun parsesTagsWithPrefixAndMetadata() {
        assertEquals(Version(listOf(1, 2, 3), emptyList()), v("v1.2.3"))
        assertEquals(Version(listOf(1, 2, 3), emptyList()), v("V1.2.3+build.7"))
        assertEquals(Version(listOf(0, 4), listOf("rc", "1")), v(" 0.4-rc.1 "))
    }

    @Test fun rejectsGarbage() {
        listOf("", "v", "latest", "1..2", "1.2.x", "1.2.3-", "1.2.3-a..b", "nightly-2024").forEach { assertNull(it, Version.parse(it)) }
    }

    @Test fun ordersCoreNumerically() {
        assertTrue(v("0.10.0") > v("0.9.9"))
        assertTrue(v("2.0.0") > v("1.99.99"))
        assertEquals(0, v("1.2").compareTo(v("1.2.0")))
    }

    @Test fun followsSemverPreReleaseOrder() {
        // The example chain from semver.org section 11.
        val chain = listOf("1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta", "1.0.0-beta.2", "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0")
        chain.zipWithNext().forEach { (a, b) -> assertTrue("$a < $b", v(a) < v(b)) }
    }

    @Test fun isNewerOnlyWhenStrictlyNewer() {
        assertTrue(Version.isNewer("v0.2.0", "0.1.0"))
        assertTrue(Version.isNewer("v0.1.0", "0.1.0-beta.3"))
        assertFalse(Version.isNewer("v0.1.0", "0.1.0"))
        assertFalse(Version.isNewer("v0.1.0-rc.1", "0.1.0"))
        assertFalse(Version.isNewer("v0.0.9", "0.1.0"))
        assertFalse(Version.isNewer("latest", "0.1.0"))
        assertFalse(Version.isNewer("v9.0.0", "dev"))
    }
}

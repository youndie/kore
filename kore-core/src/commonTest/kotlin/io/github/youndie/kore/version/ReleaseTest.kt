package io.github.youndie.kore.version

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class Built(
    override val version: String = "1.4.0",
    override val commit: String = "abc123abc123",
    override val dirty: Boolean = false,
    override val builtAt: String = "2026-09-12T00:00:00Z",
) : BuildIdentity

class ReleaseTest {
    @Test
    fun `with no override the release is the version and the commit`() {
        assertEquals("1.4.0+abc123abc123", releaseOf(Built()).name)
    }

    @Test
    fun `a dirty build carries the marker into the release`() {
        assertEquals("1.4.0+abc123abc123-dirty", releaseOf(Built(dirty = true)).name)
    }

    @Test
    fun `a build with no git still produces a release`() {
        assertEquals("1.4.0+unknown", releaseOf(Built(commit = BuildIdentity.UNKNOWN)).name)
    }

    @Test
    fun `an override becomes the release and is reported as a disagreement`() {
        val release = releaseOf(Built(), override = "2026.09.12-hotfix")

        assertEquals("2026.09.12-hotfix", release.name)
        assertTrue(release.overridden)
        assertEquals("1.4.0+abc123abc123", release.compiled, "the compiled value was lost")
    }

    /** Agreement is the common case; calling it an override would bury the interesting one. */
    @Test
    fun `an override equal to the compiled release is not a disagreement`() {
        val release = releaseOf(Built(), override = "1.4.0+abc123abc123")

        assertEquals("1.4.0+abc123abc123", release.name)
        assertTrue(!release.overridden)
    }

    /** What an unset chart value looks like after templating. Honouring it would name the binary "". */
    @Test
    fun `a blank override is treated as absent`() {
        val release = releaseOf(Built(), override = "   ")

        assertEquals("1.4.0+abc123abc123", release.name)
        assertTrue(!release.overridden)
    }

    @Test
    fun `the keys kore reads are named the way the charts already name them`() {
        assertEquals("RELEASE", KoreKeys.RELEASE.name)
        assertEquals("KORE_VERSION_REDUCED", KoreKeys.VERSION_REDUCED.name)
    }
}

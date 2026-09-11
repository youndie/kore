package io.github.youndie.kore

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KorePlatformLinuxTest {
    @Test
    fun `the linux targets can enumerate the environment`() {
        assertEquals("linux", korePlatform().name)
        // Derived from the real implementation since B-22, so this now asserts the walk rather than
        // a constant that was written beside it.
        assertTrue(korePlatform().canEnumerateEnvironment)
    }
}

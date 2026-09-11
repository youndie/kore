package io.github.youndie.kore

import kotlin.test.Test
import kotlin.test.assertTrue

class KorePlatformTest {
    @Test
    fun `every target names itself`() {
        assertTrue(korePlatform().name.isNotBlank(), "the platform must name itself")
    }
}

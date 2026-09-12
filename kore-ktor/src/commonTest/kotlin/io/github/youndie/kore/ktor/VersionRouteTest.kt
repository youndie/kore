package io.github.youndie.kore.ktor

import io.github.youndie.kore.version.BuildIdentity
import io.github.youndie.kore.version.releaseOf
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private class Built(
    override val version: String = "1.4.0",
    override val commit: String = "abc123abc123",
    override val dirty: Boolean = false,
    override val builtAt: String = "2026-09-12T00:00:00Z",
) : BuildIdentity

class VersionRouteTest {
    @Test
    fun `the body names the release and the version and the commit and the build time`() = testApplication {
        application { installKoreVersion(Built()) }

        val response = client.get(KoreRoutes.VERSION)

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(
            """
            release: 1.4.0+abc123abc123
            version: 1.4.0
            commit: abc123abc123
            built: 2026-09-12T00:00:00Z

            """.trimIndent(),
            response.bodyAsText(),
            "the body is a contract a deploy check greps, so it is asserted whole",
        )
    }

    @Test
    fun `a dirty build says so in the commit`() = testApplication {
        application { installKoreVersion(Built(dirty = true)) }

        assertTrue(client.get(KoreRoutes.VERSION).bodyAsText().contains("commit: abc123abc123-dirty"))
    }

    /**
     * The switch reduces and does not remove. A `404` is what a deploy check sees when the deployment
     * is broken, so a route that answers it by configuration has made the two indistinguishable.
     */
    @Test
    fun `reduced still answers with the release alone`() = testApplication {
        val identity = Built()
        application { installKoreVersion(identity, releaseOf(identity, "2026.09.12"), reduced = true) }

        val response = client.get(KoreRoutes.VERSION)

        assertEquals(HttpStatusCode.OK, response.status, "the switch removed the route")
        assertEquals("release: 2026.09.12\n", response.bodyAsText())
    }

    @Test
    fun `reduced does not leak the commit or the build time`() = testApplication {
        val identity = Built()
        application { installKoreVersion(identity, releaseOf(identity, "2026.09.12"), reduced = true) }

        val body = client.get(KoreRoutes.VERSION).bodyAsText()

        assertTrue(!body.contains("abc123abc123"), "the reduced body carried the commit: $body")
        assertTrue(!body.contains("2026-09-12T"), "the reduced body carried the build time: $body")
    }

    /**
     * The reduction that would reduce nothing. kore's default release is `version+commit`, so turning
     * the switch on without naming a release serves the commit under a switch meant to hide it — a
     * deployment that believes it is private and is not.
     */
    @Test
    fun `reduction without a release of its own is refused at startup`() {
        val identity = Built()
        val refusal =
            assertFailsWith<IllegalArgumentException> {
                testApplication {
                    application { installKoreVersion(identity, reduced = true) }
                    client.get(KoreRoutes.VERSION)
                }
            }

        assertTrue(refusal.message!!.contains("RELEASE"), "the refusal did not name the fix: ${refusal.message}")
        assertTrue(refusal.message!!.contains("abc123abc123"), "the refusal did not show what would leak")
    }

    /** A build with no git has no commit to leak, so the reduction has nothing to refuse. */
    @Test
    fun `reduction is allowed when there is no commit to hide`() = testApplication {
        application { installKoreVersion(Built(commit = BuildIdentity.UNKNOWN), reduced = true) }

        assertEquals("release: 1.4.0+unknown\n", client.get(KoreRoutes.VERSION).bodyAsText())
    }

    /**
     * The case the whole "one value, one place" rule exists for: the environment names one release
     * and the binary was built from another. Research §1.11 — today the override is all that exists,
     * so the disagreement cannot be seen at all.
     */
    @Test
    fun `an override that disagrees with the compiled identity is visible`() = testApplication {
        val identity = Built()
        application { installKoreVersion(identity, releaseOf(identity, override = "2026.09.12-hotfix")) }

        val body = client.get(KoreRoutes.VERSION).bodyAsText()

        assertTrue(body.contains("release: 2026.09.12-hotfix"), "the override did not become the release")
        assertTrue(
            body.contains("compiled-release: 1.4.0+abc123abc123"),
            "the disagreement was not reported: $body",
        )
    }

    /** An override agreeing with the compiled value is not a disagreement, and must not look like one. */
    @Test
    fun `an override equal to the compiled release adds no line`() = testApplication {
        val identity = Built()
        application { installKoreVersion(identity, releaseOf(identity, override = "1.4.0+abc123abc123")) }

        assertTrue(!client.get(KoreRoutes.VERSION).bodyAsText().contains("compiled-release"))
    }

    /**
     * A reduced body hides the commit, and an override that disagrees with it is exactly what a
     * private deployment is hiding. It must not come back through the disagreement line.
     */
    @Test
    fun `reduced hides the disagreement too`() = testApplication {
        val identity = Built()
        application {
            installKoreVersion(identity, releaseOf(identity, override = "2026.09.12-hotfix"), reduced = true)
        }

        val body = client.get(KoreRoutes.VERSION).bodyAsText()

        assertEquals("release: 2026.09.12-hotfix\n", body)
    }
}

package io.github.youndie.kore.ktor

import io.github.youndie.kore.version.BuildIdentity
import io.github.youndie.kore.version.KoreRelease
import io.github.youndie.kore.version.releaseOf
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

/**
 * `GET /version` — which build this is. `docs/api/endpoint-kore-admin.md`.
 *
 * Answers the question asked during a deploy and during an incident: *is what is running the thing we
 * think we pushed?* Today the portfolio answers it by trusting that the image tag matches the chart
 * value that matches the commit — three claims, each true separately, and the case where they
 * disagree is precisely the case somebody is investigating.
 *
 * ## The body is a contract, unlike the probes'
 *
 * A probe body is for a person with `curl` and kore reserves the right to improve it. This one is
 * read by deploy checks, so it is `key: value` per line, in a fixed order, plain text — the shape a
 * shell can `grep` without a parser. Lines may be **added**; the four below keep their names.
 *
 * ## Reduced, never removed
 *
 * With [reduced] the body is the release name alone. A commit hash is not a secret for a public
 * repository and the route earns its keep in every deploy check; for a private one a hash and a
 * build timestamp narrow down what is running. What the switch must not do is remove the route,
 * because a `404` is indistinguishable from a broken deployment to the check that reads it.
 *
 * **And it refuses to start when it would not actually reduce anything.** kore's default release is
 * `version+commit`, because that is what a deploy marker and a crash group need to tell two builds
 * of one version apart. So a deployment that turns the reduction on and sets no `RELEASE` would
 * serve the commit under a switch whose purpose is to hide it — a deployment that believes it is
 * private and is not. That is the same shape as an observability endpoint configured without its
 * key, and it gets the same answer the first consumer already got right (research §1.11 consequence
 * 2): a refusal at startup, not a silent no-op.
 *
 * @param reduced normally [io.github.youndie.kore.version.KoreKeys.VERSION_REDUCED].
 */
public fun Application.installKoreVersion(
    identity: BuildIdentity,
    release: KoreRelease = releaseOf(identity),
    reduced: Boolean = false,
) {
    if (reduced) {
        require(identity.commit == BuildIdentity.UNKNOWN || !release.name.contains(identity.commit)) {
            "KORE_VERSION_REDUCED is on and the release still names the commit: \"${release.name}\". " +
                "Reduction hides the commit and the build time, and kore's default release is " +
                "version+commit. Set RELEASE to the name this deployment should report, or turn the " +
                "reduction off."
        }
    }

    routing {
        get(KoreRoutes.VERSION) {
            call.respondText(
                if (reduced) reducedBody(release) else fullBody(release),
                ContentType.Text.Plain,
            )
        }
    }
}

private fun reducedBody(release: KoreRelease): String = "release: ${release.name}\n"

private fun fullBody(release: KoreRelease): String =
    buildString {
        appendLine("release: ${release.name}")
        appendLine("version: ${release.identity.version}")
        appendLine("commit: ${release.identity.describe}")
        appendLine("built: ${release.identity.builtAt}")
        // Only when they disagree, and that is the design rather than brevity. A line that is always
        // there is one nobody reads; a line that appears exactly when the environment names a
        // different release from the one compiled in is the disagreement research §1.11 says is
        // currently impossible to notice — the deploy marker and the crash group would carry the
        // override while the binary carried something else, and nothing would say so.
        if (release.overridden) appendLine("compiled-release: ${release.compiled}")
    }

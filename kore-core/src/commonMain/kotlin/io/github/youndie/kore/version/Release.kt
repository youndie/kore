package io.github.youndie.kore.version

import io.github.youndie.kore.config.ConfigKey

/**
 * What this binary calls itself to everything outside it.
 *
 * One value in one place, which is the whole point: today the release travels as an environment
 * variable into a metrik deploy marker and a katcher crash group, and **nothing relates it to the
 * code that was built** (research §1.11). kore makes the compiled-in identity the source and lets the
 * environment override it, so the two can be compared instead of only one of them existing.
 *
 * @property name what `/version` reports and what the agents are given.
 * @property identity what the binary was actually built from, whatever [name] says.
 * @property overridden whether [name] came from the environment **and disagrees** with the compiled
 *   value. Not merely "the variable was set": a chart that sets `RELEASE` to the same thing kore
 *   computed is agreement, and reporting that as an override would make the interesting case
 *   invisible among the boring ones.
 */
public class KoreRelease(
    public val name: String,
    public val identity: BuildIdentity,
    public val overridden: Boolean,
) {
    /** What kore would have called this binary with no environment at all. */
    public val compiled: String get() = compiledReleaseOf(identity)
}

/**
 * The release a binary has when nobody tells it otherwise.
 *
 * `version+commit` rather than either alone. The version answers "which release is this meant to be"
 * and the commit answers "which code is in it", and the pair is the only form that survives the case
 * this exists for: two builds of the same version, one of them a rebuild nobody expected.
 */
private fun compiledReleaseOf(identity: BuildIdentity): String = "${identity.version}+${identity.describe}"

/**
 * @param override the value of [KoreKeys.RELEASE], or `null` when it is unset.
 */
public fun releaseOf(identity: BuildIdentity, override: String? = null): KoreRelease {
    val compiled = compiledReleaseOf(identity)
    // A blank override is treated as absent. An empty environment variable is what an unset chart
    // value looks like after templating, and honouring it would name the binary "".
    val declared = override?.takeIf { it.isNotBlank() }
    return KoreRelease(
        name = declared ?: compiled,
        identity = identity,
        overridden = declared != null && declared != compiled,
    )
}

/**
 * The variables kore reads for itself.
 *
 * Declared as [ConfigKey]s rather than read with `getenv` so they appear in `--print-config` beside a
 * consumer's own — a variable kore reads and does not print is exactly the "production is running
 * something else" gap the config feature exists to close.
 */
public object KoreKeys {
    /**
     * `RELEASE`, unprefixed, because that is the name the charts already set (research §1.11). kore
     * reads the portfolio's existing variable rather than asking every chart to add a second one.
     */
    public val RELEASE: ConfigKey<String?> = ConfigKey.optional("RELEASE")

    /**
     * Reduces `/version` to the release name. It never removes the route: a route that disappears by
     * configuration is one a deploy check cannot tell apart from a broken deployment.
     */
    public val VERSION_REDUCED: ConfigKey<Boolean> = ConfigKey.boolean("KORE_VERSION_REDUCED")
}

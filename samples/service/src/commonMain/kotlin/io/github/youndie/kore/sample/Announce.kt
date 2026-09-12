package io.github.youndie.kore.sample

import io.github.youndie.kore.generated.KoreBuildIdentity
import io.github.youndie.kore.version.BuildIdentity

/**
 * Printed by both entry points before anything else starts.
 *
 * Its real job is to be a **reference**. The generated identity is wired into `commonMain` by the
 * `io.github.youndie.kore.build` plugin, and a generated file nothing mentions still compiles — so a
 * broken wiring would produce a green build and an object that is simply absent. One line of code
 * that names `KoreBuildIdentity` turns that into a compile error, which is the only way the sample
 * proves the plugin on both targets rather than merely applying it.
 */
public fun buildLine(identity: BuildIdentity = KoreBuildIdentity): String =
    "kore sample ${identity.version} ${identity.describe} built ${identity.builtAt}"

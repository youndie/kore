package io.github.youndie.kore.sample

import io.github.youndie.kore.config.ConfigKey
import io.github.youndie.kore.config.ConfigPair
import io.github.youndie.kore.config.ConfigSchema
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * What this service is configured as — the sample's half of
 * [feature-typed-config](../../../../../../../../docs/features/feature-typed-config.md).
 *
 * **Every key here is read by something below it.** That is not a style rule, it is the defect this
 * file was written to repair: the feature document claimed for weeks that the sample carried a
 * schema, naming a file that had never existed, while the service read its port from a constant
 * ([B-50](../../../../../../../../docs/backlog/B-50-sample-uses-the-config-schema.md)). A schema
 * whose values nothing consumes would have satisfied the document and demonstrated nothing.
 *
 * So the four shapes a schema has are here because the service needs all four, not because a
 * checklist asked for them:
 *
 * - [POOL_DSN] is **required**. A service cannot invent where its data lives, and refusing at
 *   startup is the whole feature — the alternative is a route that fails later, under a user.
 * - [PORT] and [WORK_MS] have **defaults**. A value with a sensible default is a value a deployment
 *   should not have to repeat, and `--print-config` still prints `default` beside it so nobody has
 *   to guess which happened.
 * - [TRACY_KEY] is a **secret**, masked wherever the configuration is rendered — a property of the
 *   declaration rather than a list of names somebody keeps in sync.
 * - [TRACY_ENDPOINT] and [TRACY_KEY] are a **pair**: both or neither. One without the other is a
 *   deployment that believes it is observed and is not (research §1.11).
 *
 * **The near miss is [WORK_MS], and it is chosen rather than accidental.** `SAMPLE_WORK_MSEC` is
 * what somebody writes, it is four edits away, and the unknown-variable check names the declared
 * variable it is probably a misspelling of instead of leaving the reader to find it. Ignoring that
 * variable silently is the failure the check exists for, and `samples/oracle`'s `configRefusal` run
 * asserts it happens against the real image rather than in a unit test.
 */
public object SampleConfig {
    /** The port to serve on. The container publishes 8080, so the default is the deployed value. */
    public val PORT: ConfigKey<Int> = ConfigKey.int("PORT", default = 8080)

    /**
     * How long `/work` takes when the request does not say.
     *
     * The name a deployment gets wrong: `_MSEC` for `_MS`. See the class KDoc.
     */
    public val WORK_MS: ConfigKey<Duration> = ConfigKey.millis("WORK_MS", default = 2_000.milliseconds)

    /**
     * Where the pool [FragileResource] stands in for would connect.
     *
     * Required, and this is the one key with no sensible default: a service that invents a database
     * address starts happily and serves wrong data, which is worse than not starting.
     */
    public val POOL_DSN: ConfigKey<String> = ConfigKey.required("POOL_DSN")

    /** Half of the observability pair. Unset means "not observed", which is a decision. */
    public val TRACY_ENDPOINT: ConfigKey<String?> = ConfigKey.optional("TRACY_ENDPOINT")

    /** The other half, and a secret. Masked by `--print-config`. */
    public val TRACY_KEY: ConfigKey<String?> = ConfigKey.optional("TRACY_KEY", secret = true)

    public val SCHEMA: ConfigSchema =
        ConfigSchema(
            prefix = "SAMPLE",
            keys = listOf(PORT, WORK_MS, POOL_DSN, TRACY_ENDPOINT, TRACY_KEY),
            pairs = listOf(ConfigPair(TRACY_ENDPOINT.name, TRACY_KEY.name)),
        )
}

/**
 * The resolved configuration, in the shape the rest of the sample uses.
 *
 * A small class rather than passing `Configuration` around, so that a value nothing reads shows up
 * as an unused property here instead of hiding behind an indexing call that may never be made.
 */
public class SampleSettings(
    public val port: Int,
    public val workMillis: Long,
    public val poolDsn: String,
    public val observed: Boolean,
) {
    /** One line for `docker logs`, naming what was configured and masking what must not be printed. */
    public fun describe(): String =
        "configured: port=$port work=${workMillis}ms pool=$poolDsn observability=${if (observed) "on" else "off"}"
}

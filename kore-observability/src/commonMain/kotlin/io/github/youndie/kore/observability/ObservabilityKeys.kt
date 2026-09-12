package io.github.youndie.kore.observability

import io.github.youndie.kore.config.ConfigKey
import io.github.youndie.kore.config.ConfigPair
import io.github.youndie.kore.config.Configuration
import kotlin.time.Duration

/**
 * The variables the three agents need, declared for a consumer to splice into **its own** schema.
 *
 * Not a schema of kore's own, and that is the point. A `ConfigSchema` owns a prefix, and the
 * unknown-variable refusal is scoped to it — two schemas would mean two scopes and a variable that
 * is unknown to one and declared by the other. So kore hands over keys and lets the service's single
 * schema carry them:
 *
 * ```kotlin
 * val schema = ConfigSchema("KONEKT", keys = myKeys + ObservabilityKeys.all, pairs = ObservabilityKeys.pairs)
 * ```
 *
 * **Every one of these is prefixed like everything else** — `KONEKT_RELEASE`, not `RELEASE`. An
 * unprefixed key would sit outside the typo check by construction, and the check is the feature: a
 * misspelled `RELEASSE` that nothing reads is exactly the class of mistake this schema exists to
 * refuse. The cost is one line in a chart that already sets `RELEASE`, paid once at adoption.
 */
public object ObservabilityKeys {
    /**
     * Validated for shape and nothing else — there is no registration step in any of the three
     * agents, so a typo does not fail, it creates a phantom service that looks healthy and receives
     * nothing. `--print-config` prints the value rather than only the fact that it is set, because a
     * person reading the name is the only thing that catches `konket-server`.
     */
    public val SERVICE: ConfigKey<String> = ConfigKey.required("SERVICE")

    /**
     * Required once any agent is on. Unset, katcher's own default is `Unspecified`, and a crash
     * group named `Unspecified` is a crash nobody can act on. It is the value `/version` reports —
     * see `feature-build-identity` rule 4 — and kore does not read it from a second place.
     */
    public val RELEASE: ConfigKey<String?> = ConfigKey.optional("RELEASE")

    /**
     * Defaults to the pod name. Overridable because a chart that sets `HOSTNAME` to something else —
     * which has happened in this portfolio — otherwise merges two pods into one instance.
     */
    public val INSTANCE: ConfigKey<String?> = ConfigKey.optional("INSTANCE")

    /** katcher's own field. Its default is `Dev`, which is wrong everywhere it matters. */
    public val ENVIRONMENT: ConfigKey<String> = ConfigKey.string("ENVIRONMENT", "prod")

    public val TRACY_ENDPOINT: ConfigKey<String?> = ConfigKey.optional("TRACY_ENDPOINT")
    public val TRACY_KEY: ConfigKey<String?> = ConfigKey.optional("TRACY_KEY", secret = true)
    /**
     * What fraction of requests keep their trace — tracy's own knob, passed through when set.
     *
     * It does **not** thin spans: it decides whether the request's whole pending trace is kept, so
     * below the rate what survives is warnings and entity references with no body behind them. The
     * right value is a property of the deployment's traffic and kore has no way to know it — a
     * reference build serving a handful of requests a minute wants `1.0`, a service at 100 rps very
     * much does not.
     *
     * Unset leaves tracy's default rather than a number of kore's own. Reported by the first consumer
     * after a demonstration went missing from its own trace
     * ([#57](https://github.com/youndie/kore/issues/57)).
     */
    public val TRACY_SAMPLE_RATE: ConfigKey<Double?> = ConfigKey.optionalDouble("TRACY_SAMPLE_RATE")

    public val METRIK_ENDPOINT: ConfigKey<String?> = ConfigKey.optional("METRIK_ENDPOINT")
    public val METRIK_KEY: ConfigKey<String?> = ConfigKey.optional("METRIK_KEY", secret = true)
    /**
     * How long metrik aggregates before it sends — metrik's own knob, passed through when set.
     *
     * The agent sends a window **when the window closes**, and its default is 60 000 ms, so a freshly
     * started process reports nothing at all for a minute. That is right for a deployment and wrong
     * for a stand, where the whole end-to-end run is shorter than one window: the first consumer's
     * scenario test passed four times against a stand that had been up a while and failed on a
     * freshly rebuilt one, which is a flaky test rather than a broken one — the worse of the two.
     *
     * Unset leaves metrik's default rather than a number of kore's
     * ([#68](https://github.com/youndie/kore/issues/68)).
     */
    public val METRIK_WINDOW_MS: ConfigKey<Duration?> = ConfigKey.optionalMillis("METRIK_WINDOW_MS")

    public val KATCHER_ENDPOINT: ConfigKey<String?> = ConfigKey.optional("KATCHER_ENDPOINT")
    public val KATCHER_KEY: ConfigKey<String?> = ConfigKey.optional("KATCHER_KEY", secret = true)

    /**
     * Where katcher keeps a report it has not delivered yet. Unset, katcher writes `.katcher_cache`
     * next to the working directory — a path in the container's writable layer, which dies with the
     * pod. A deployment that wants the crash that killed a binary to survive its restart points this
     * at a mounted volume.
     *
     * Optional because it is only worth setting when there is a volume to set it to, and a service
     * with none is better off with katcher's default than with a path that is not there.
     */
    public val KATCHER_CACHE_DIR: ConfigKey<String?> = ConfigKey.optional("KATCHER_CACHE_DIR")

    public val all: List<ConfigKey<*>> =
        listOf(
            SERVICE, RELEASE, INSTANCE, ENVIRONMENT,
            TRACY_ENDPOINT, TRACY_KEY, TRACY_SAMPLE_RATE,
            METRIK_ENDPOINT, METRIK_KEY, METRIK_WINDOW_MS,
            KATCHER_ENDPOINT, KATCHER_KEY, KATCHER_CACHE_DIR,
        )

    /**
     * Endpoint and key, per agent — rule 1. One without the other is refused at startup, because all
     * three agents answer a missing value by doing nothing, quietly, in three different ways.
     */
    public val pairs: List<ConfigPair> =
        listOf(
            ConfigPair("TRACY_ENDPOINT", "TRACY_KEY"),
            ConfigPair("METRIK_ENDPOINT", "METRIK_KEY"),
            ConfigPair("KATCHER_ENDPOINT", "KATCHER_KEY"),
        )
}

/** An agent that is on: both halves present, because the schema refused the case where one is not. */
public class AgentEndpoint(public val endpoint: String, public val key: String)

/**
 * Everything `installKoreObservability` needs, and nothing about *how* it was obtained.
 *
 * A separate type from [Configuration] so the wiring can be tested without an environment, and so a
 * consumer that does not use kore's schema can still use the wiring.
 */
public class ObservabilitySettings(
    public val service: String,
    /** `null` only when no agent is on; [installKoreObservability] refuses the other combination. */
    public val release: String?,
    public val instance: String,
    public val environment: String = "prod",
    public val tracy: AgentEndpoint? = null,
    public val metrik: AgentEndpoint? = null,
    public val katcher: AgentEndpoint? = null,
    /**
     * Where katcher keeps an undelivered report — [ObservabilityKeys.KATCHER_CACHE_DIR]. `null`
     * leaves katcher's own platform default, which for a server binary is the working directory.
     *
     * Ignored when katcher is off: there is nothing to store.
     */
    public val katcherCacheDir: String? = null,
    /** See [ObservabilityKeys.TRACY_SAMPLE_RATE]. `null` leaves tracy's own default. */
    public val tracySampleRate: Double? = null,
    /** See [ObservabilityKeys.METRIK_WINDOW_MS]. `null` leaves metrik's own default. */
    public val metrikWindow: Duration? = null,
) {
    public val anyAgentOn: Boolean get() = tracy != null || metrik != null || katcher != null

    public companion object {
        /**
         * Reads the settings out of a configuration built with [ObservabilityKeys].
         *
         * @param instanceFallback the pod name, which only the host can read — `HOSTNAME` on the JVM
         *   and a `getenv` on Kotlin/Native. Passed in rather than looked up so this stays free of a
         *   platform split that would have to exist in both targets for one string.
         */
        public fun from(configuration: Configuration, instanceFallback: String): ObservabilitySettings =
            ObservabilitySettings(
                service = configuration[ObservabilityKeys.SERVICE],
                release = configuration[ObservabilityKeys.RELEASE],
                instance = configuration[ObservabilityKeys.INSTANCE] ?: instanceFallback,
                environment = configuration[ObservabilityKeys.ENVIRONMENT],
                tracy = endpointOf(configuration, ObservabilityKeys.TRACY_ENDPOINT, ObservabilityKeys.TRACY_KEY),
                metrik = endpointOf(configuration, ObservabilityKeys.METRIK_ENDPOINT, ObservabilityKeys.METRIK_KEY),
                katcher = endpointOf(configuration, ObservabilityKeys.KATCHER_ENDPOINT, ObservabilityKeys.KATCHER_KEY),
                katcherCacheDir = configuration[ObservabilityKeys.KATCHER_CACHE_DIR],
                tracySampleRate = configuration[ObservabilityKeys.TRACY_SAMPLE_RATE],
                metrikWindow = configuration[ObservabilityKeys.METRIK_WINDOW_MS],
            )

        private fun endpointOf(
            configuration: Configuration,
            endpoint: ConfigKey<String?>,
            key: ConfigKey<String?>,
        ): AgentEndpoint? {
            val resolvedEndpoint = configuration[endpoint]
            val resolvedKey = configuration[key]
            // Both or neither: the schema's pair rule already refused one without the other, so this
            // does not re-check it — it reads the case the refusal let through.
            return if (resolvedEndpoint != null && resolvedKey != null) {
                AgentEndpoint(resolvedEndpoint, resolvedKey)
            } else {
                null
            }
        }
    }
}

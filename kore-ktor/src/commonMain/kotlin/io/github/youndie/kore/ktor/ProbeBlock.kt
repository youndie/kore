package io.github.youndie.kore.ktor

/**
 * The probe block a chart should carry, ready to paste.
 *
 * It is printed by `--print-config` so a consumer does not have to find the feature document — and
 * because the chart is where this feature is most easily got wrong. Pointing a readiness probe at
 * `/health` gives a probe that cannot fail while the process is alive, which is what the portfolio
 * does today and what `feature-health-probes` exists to stop.
 *
 * `failureThreshold: 60` with `periodSeconds: 1` rather than a long `initialDelaySeconds`: the pod
 * becomes ready as soon as it is ready, instead of at a fixed time set for the worst case.
 */
public fun koreProbeBlock(terminationGracePeriodSeconds: Int = 30): String =
    """
    probes — paste into the chart:

      startupProbe:
        httpGet: { path: ${KoreRoutes.STARTUP}, port: http }
        periodSeconds: 1
        failureThreshold: 60
      livenessProbe:
        httpGet: { path: ${KoreRoutes.LIVE}, port: http }
        periodSeconds: 20
        failureThreshold: 3
      readinessProbe:
        httpGet: { path: ${KoreRoutes.READY}, port: http }
        periodSeconds: 2
        failureThreshold: 3
      terminationGracePeriodSeconds: $terminationGracePeriodSeconds
    """.trimIndent() + "\n"

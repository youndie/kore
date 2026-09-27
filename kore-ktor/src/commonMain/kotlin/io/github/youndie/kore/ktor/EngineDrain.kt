package io.github.youndie.kore.ktor

import io.github.youndie.kore.lifecycle.DrainGate
import io.github.youndie.kore.lifecycle.ShutdownParticipant
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import kotlin.time.Duration

/**
 * The drain stage, as a participant of the stage machine.
 *
 * It calls `EmbeddedServer.stopSuspend` **itself**, with kore's own numbers, and that is two
 * decisions rather than one.
 *
 * **kore calls stop, rather than letting a shutdown hook do it.** On Kotlin/Native
 * `EmbeddedServer.addShutdownHook` is a single global slot whose last registration wins — kore would
 * be replacing Ktor's hook or being replaced by it, decided by an order nobody writes down. Calling
 * it directly also buys the thing the announce stage needs: somewhere to run *before* the engine
 * starts stopping, which no Ktor event offers (research §1.2, §1.3).
 *
 * **On the JVM that only holds if Ktor's own hook is off**, because there the hooks are a list the JVM
 * runs concurrently, and Ktor's stops the engine at the signal. So this stage refuses to be built
 * beside it, and [startForKore] is how a server is started without it (#90).
 *
 * **It opens the refusal, and nothing earlier does.** [gate] is flipped as this stage's first act,
 * before the engine is told to stop, and [installShutdownRefusal] reads it. The announce before it
 * goes on serving — refusing there answers `503` to the requests the pre-drain wait exists to serve,
 * which the sample did until B-61. Here rather than in a participant of its own, because participants
 * inside a stage run concurrently and "refuse, then stop" is an order.
 *
 * **kore passes the numbers, rather than inheriting them.**
 * `ApplicationEngine.Configuration.shutdownGracePeriod` defaults to **1000 ms**, which is shorter
 * than a great many real requests — an unconfigured service drops in-flight work on `SIGTERM`, on
 * both platforms, for a reason that has nothing to do with ordering (research §1.13, measured).
 *
 * ## What this stage's duration actually is
 *
 * Not a ceiling. CIO's stop waits for the connectors' jobs and a keep-alive client keeps those alive,
 * so the grace period is spent **in full** whenever anything is still connected: a 20-second grace
 * produced a 20.5-second shutdown with eight busy connections. Size [grace] as "how long shutdown
 * takes under load", because that is what it is.
 */
public class EngineDrain(
    private val server: EmbeddedServer<out ApplicationEngine, *>,
    /** How long the engine may spend finishing what it has. */
    public val grace: Duration,
    /** The **total** budget, not an extra one — see the note below. */
    public val timeout: Duration,
    /** The latch [installShutdownRefusal] reads. The same instance, or the refusal never opens. */
    private val gate: DrainGate,
) : ShutdownParticipant {
    /**
     * The form before B-61, which had no latch of its own to open — so a consumer gated the refusal
     * on readiness, and refused through the whole announce.
     */
    @Deprecated(
        "Pass the DrainGate that installShutdownRefusal reads, so the refusal opens at the drain " +
            "and not at the announce (kore B-61).",
    )
    public constructor(
        server: EmbeddedServer<out ApplicationEngine, *>,
        grace: Duration,
        timeout: Duration,
    ) : this(server, grace, timeout, DrainGate())

    init {
        require(!grace.isNegative()) { "the drain grace period cannot be negative, was $grace" }
        // CIO's hard-kill window is `timeout - grace`. With `timeout <= grace` the engine cancels the
        // server job and then waits a non-positive time for the cancellation to take effect — so the
        // second number silently does nothing. Refused here, where both numbers are in front of
        // somebody, rather than discovered during an incident.
        require(timeout > grace) {
            "the drain timeout must be longer than the grace period, because the engine's hard-kill " +
                "window is timeout minus grace: grace=$grace timeout=$timeout leaves ${timeout - grace}"
        }
        // HERE, because this is the one kore object every consumer builds with the server in hand, and
        // a hook that is on makes this stage's position in the sequence a fiction: the engine stops at
        // the signal whatever the stages say. A refusal at startup rather than a readiness that is
        // quietly a refused connection in production — which is what it was until #90, invisibly to
        // an oracle that probed on one keep-alive connection.
        check(ktorShutdownHookArmed() != true) { ktorShutdownHookMessage() }
    }

    override val name: String = "http engine"

    override suspend fun stop() {
        gate.beginDrain()
        server.stopSuspend(grace.inWholeMilliseconds, timeout.inWholeMilliseconds)
    }
}

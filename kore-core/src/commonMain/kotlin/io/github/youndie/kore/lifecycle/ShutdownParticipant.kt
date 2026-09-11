package io.github.youndie.kore.lifecycle

/**
 * Something that must be told to stop, and the stage it belongs to decides when.
 *
 * A participant is "something holding a socket, a position or a pool that must be told to stop
 * before the thing below it closes". kore declares the contract and depends on no broker and no
 * driver: a participant is a contract, not a booblik type (research D5, the half that stands).
 *
 * [stop] is expected to be **cooperatively cancellable**. A participant that ignores cancellation
 * cannot be stopped by anything, and [ShutdownSequence] answers that by refusing to wait for it past
 * the stage deadline rather than by pretending it succeeded — see the note on the time bound there.
 */
public interface ShutdownParticipant {
    /**
     * What this is called in a transcript and in a failure. It is read by a person during an
     * incident, so `"outbox relay"` beats `"Participant@4f8e"`.
     */
    public val name: String

    /** Stop. Throwing is allowed and is recorded; it does not stop the sequence. */
    public suspend fun stop()
}

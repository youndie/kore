# Flush before close, against a real broker — [B-45](../../backlog/B-45-booblik-against-a-real-broker.md), 2026-09-12

The half of [feature-ordered-shutdown](../../features/feature-ordered-shutdown.md) §5 that unit tests
cannot reach: not *does kore flush first* — `BooblikParticipantTest` asserts that — but *does flushing
first keep records a shutdown would otherwise lose*. That is the client's behaviour against a server,
and a double reproducing the loss would be asserting the thing it was written to reproduce.

## What was measured, and on what

| | |
|---|---|
| Subject | `samples/oracle` `brokerFlush`, three shutdowns of one JVM `Producer` |
| Broker | `ghcr.io/youndie/booblik@sha256:db73efcb00572e48b3b7e7bed98fe06f777a557dea9103199ace714fb6165fce created 2026-09-07T15:43:03.814569559Z` |
| kore | branch `feat/b-45-real-broker`, commit `ae211f66c257` |
| Host | the project's Linux build host — 20 cores, 15 GB, kernel 6.6.87.2, Docker 29.1.3 |
| Producer | `lingerMillis = 60_000`, `maxBatchSize = 10_000` — so nothing leaves on its own |
| Records | one warm-up, sent **and awaited**; then 50 sent and **not** awaited |
| Repetitions | 5 |
| Raw output | [`raw-broker-flush.txt`](raw-broker-flush.txt) |

```bash
./gradlew :samples:oracle:brokerFlush
```

## Result — identical in all five runs

| Shutdown | Records read back, of 51 |
|---|---|
| `producer.close()`, then `connection.close()` and `scope.cancel()` in the same breath | **1** |
| `producer.close()`, 500 ms of quiet, then the same teardown | **51** |
| `booblikParticipant(…).stop()` — flush awaited, then close — then the same teardown | **51** |

The count is taken over a **new connection**, fetching from offset 0: the question is what the broker
has, not what the old client believed it sent. The 1 is the warm-up, which is why the warm-up exists —
it separates "the batch was lost" from "nothing ever worked".

## What it says

**The first and third rows are the result; the second row is the mechanism, and it is the one that
corrected this repository.** Research §1.8 said the JVM `close()` discards the accumulated batch. It
does not. The second arm is the same `close()` as the first, and it keeps all 51 records — so the
batch is sent, on the producer's own coroutine, without waiting. A shutdown is precisely when that
coroutine's scope and connection are being torn down, and that race is what loses the records.

kore's contribution is therefore the **waiting**, not the sending: `flush()` is the only call in that
client that suspends until the broker has answered. The rule survives unchanged — flush, then close —
and the reason behind it is now measured rather than inferred.

## Two preconditions that cost four runs

- **The broker does not create topics.** `BooblikConfig.topics` is fixed at startup (booblik M-42). A
  produce to an undeclared topic is refused into a handle nobody awaits, and the first *fetch* of one
  costs the connection — an `EOFException` that reads like a protocol failure. The harness declares
  `BOOBLIK_TOPICS` and awaits the warm-up, so a refusal fails the run instead of arriving at the end
  as an empty fetch indistinguishable from a lost batch.
- **`docker run -p` publishes the port before the process binds it.** The first connection lands on
  docker's proxy and dies when it is asked anything. Readiness here is a METADATA round trip naming
  the topic, not an open socket.

Both were found by printing the **broker's own log** on failure. Three runs were spent reading the
client's stack trace, which could only ever describe the half of the failure the client could see.

# Offline-First Event Sourcing — Design & Implementation Guide

A minimal, abstract event-sourcing core that is **offline-first** (you can read and
write with no network) and **real-time when online** (other clients' changes stream in
live). The same core is reused across modules, client and server.

---

## Mental model

Everything is built on one idea: **state is a pure fold over an append-only log of
events.** You never mutate state directly — you append an event, and state is recomputed
by folding events through a pure function. "Undo" is not a reverse operation; it's
re-folding while excluding an event.

```
state = events.fold(initial, ::evolve)
```

Because the fold is pure and re-runnable, rejection, conflict, and reordering all reduce
to "recompute the fold." That's the property that keeps the whole system simple.

### The two clocks (the offline-first invariant)

Every stored event carries two sequence numbers:

- **`clientSeq`** — local monotonic counter, assigned the moment you append locally.
  Always present. This is what lets you work offline: you don't need the server to have
  a usable, ordered local history.
- **`serverSeq`** — the authoritative global order, assigned by the server. **Null until
  the server acknowledges the event.**

"`serverSeq` is null" *is* the outbox. There is no separate pending queue — the set of
events still needing sync is just a query: `WHERE serverSeq IS NULL`.

---

## Core types

```kotlin
sealed interface SyncState {
    data object Pending : SyncState                      // local-only, awaiting server
    data class Synced(val serverSeq: Long) : SyncState   // authoritative
    data class Rejected(val reason: String) : SyncState  // server refused; excluded from folds
}

data class Recorded<out E : DomainEvent>(
    val event: E,
    val clientSeq: Long,
    val sync: SyncState,
)

data class Ack(val eventId: EventId, val serverSeq: Long)
data class Nack(val eventId: EventId, val reason: String)
```

### The only domain-specific code

Per aggregate (e.g. Project, User, Cart), a module writes exactly one thing:

```kotlin
interface Aggregate<S, C, E : DomainEvent> {
    val initial: S
    fun decide(state: S, command: C): List<E>   // pure; throws on invalid command
    fun evolve(state: S, event: E): S            // pure fold step
}
```

`decide` = "given current state and a command, what events should happen (or is it
invalid)?" `evolve` = "given state and an event, what's the new state?" **Both pure, both
re-runnable.** Everything else in this document is generic over `E : DomainEvent` and
written once.

---

## Interfaces — shared vs. per-side

The client and server share **far less than it first looks**. The genuinely shared,
high-value asset is not an interface — it's the **pure code**: `Aggregate`, `stateOf`,
`effectiveOrder`, and the core types. That's what carries reuse across modules. The
storage interfaces are thin and mostly per-side, because the two sides differ in
**authority**:

- The **client** applies events *optimistically* — it shows them before the server has
  confirmed them, so it needs a Pending → Ack/Nack lifecycle.
- The **server** is *authoritative* — an event is real the instant it commits, so there
  is no Pending state and no ack/nack to receive.

The only **storage** capability both sides truly share is reading one aggregate's events
to fold its state:

```kotlin
// the one shared read port
interface AggregateReader<E : DomainEvent> {
    suspend fun forAggregate(id: AggregateId): List<Recorded<E>>
}

// client: optimistic lifecycle + pull
interface ClientEventStore<E : DomainEvent> : AggregateReader<E> {
    suspend fun append(events: List<E>): List<Recorded<E>>   // sync = Pending, assigns clientSeq
    suspend fun applyRemote(events: List<Recorded<E>>)       // pull lands here; dedup by eventId
    suspend fun unsynced(): List<Recorded<E>>                // sync is Pending
    suspend fun ack(acks: List<Ack>)                         // Pending -> Synced
    suspend fun nack(nacks: List<Nack>)                      // Pending -> Rejected
    fun stream(fromClientSeq: Long): Flow<Recorded<E>>       // projection feed
}

// server: authoritative assignment + the validation that PRODUCES acks/nacks
interface ServerEventStore<E : DomainEvent> : AggregateReader<E> {
    suspend fun commit(events: List<E>): List<Result>        // Result = Ack | Nack
    fun stream(fromServerSeq: Long): Flow<Recorded<E>>       // broadcast feed
}
```

Why this is split, not one shared `EventLog`:

- **`forAggregate`** is the read path for `decide`/`commit` (see below) — both sides
  genuinely call it, so it's the one shared port.
- **`applyRemote`** is **not** shared. The authoritative server *is* the source of
  truth; it never applies remote events (it `commit`s them). Only the client — or a
  future *replica/multi-node* server — applies remote. Putting it on a shared base would
  force the authoritative server into a no-op implementation.
- **`stream`** even uses a **different seq space** per side (`clientSeq` vs `serverSeq`),
  another sign it doesn't want to be one shared method.

Only introduce a shared `EventLog` later, if you build a replica server that genuinely
calls `applyRemote`.

The symmetry to notice: the server's `commit` is the **source** of `Ack`/`Nack`; the
client's `ack`/`nack` are the **sink**. Same types, opposite ends of the transport.

### What `forAggregate` is for

It's the read path for **command handling**, not for projections. To handle a command you
need that aggregate's *current* state, so you fold just its events:

```
forAggregate(id) → stateOf(...) → decide(state, command) → events
```

The server's `commit` uses it the same way: load the aggregate's already-committed
events, then sequentially fold the incoming batch on top to validate. (Later
optimization: snapshot/cache aggregate state so you don't re-fold from event 0 —
`forAggregate` stays the source of truth.)

### Why outbox lives *in* the store (client) but is *separate* (server)

- **Client**: an event's sync status is part of its lifecycle (tentative until acked),
  and rejection must re-fold local state. Keep status *on the record* — one table, one
  transaction, no dual-write hazard. Don't build a separate outbox table here.
- **Server**: the event is already authoritative on commit; any "outbox" is purely a
  delivery/broadcast buffer (with retry metadata), never rejected. *There*, a classic
  separate transactional outbox is the right tool.

They look similar but solve different problems — don't force them into one interface.

---

## Transport & sync loop

```kotlin
// the ONLY thing that differs per environment
interface SyncTransport<E : DomainEvent> {
    suspend fun push(events: List<E>): List<Result>          // -> server.commit
    fun subscribe(fromServerSeq: Long): Flow<Recorded<E>>    // real-time stream when online
}
```

- **Client transport** = RPC over your existing connection (`RpcConnectionManager`).
- **Server-side / cross-module transport** = in-process call + broadcast.

The **SyncEngine** is generic and written once:

```
on (connection established):                 // catch-up + drain, in this order
    1. pull:  subscribe(fromServerSeq = lastKnownServerSeq)
              → everything missed while offline streams in, then continues live
              → store.applyRemote(incoming)  (dedup by eventId), re-fold
    2. push:  push(store.unsynced())  →  apply returned Acks/Nacks to store

on (local append, while online):
    push(store.unsynced())  →  apply returned Acks/Nacks
```

That's the whole offline↔online behavior. Offline: append + read work against the local
log. Online: the same push/pull runs continuously.

### Offline catch-up *is* the real-time path

There is no separate "sync after being offline" mechanism — and that's the point. The
client's high-water mark is **`lastKnownServerSeq = max(serverSeq)` already in the local
store** (derive it; don't persist it separately). On reconnect you `subscribe` from that
mark: everything missed streams in, then the subscription continues live. Being offline
for 3 seconds or 3 days is identical — only how far back `fromServerSeq` starts differs.
Real-time is just the tail of the same subscription.

Pull-then-push is the clean order: converge to authoritative history first, your pending
tail re-folds on top, then push it. (The server validates authoritatively on push
regardless, so order is about local convergence, not correctness.)

---

## Ordering & state derivation

```kotlin
fun <S, E : DomainEvent> Aggregate<S, *, E>.stateOf(records: List<Recorded<E>>): S =
    records
        .filter { it.sync !is SyncState.Rejected }   // rejected never affected state
        .sortedWith(effectiveOrder)
        .map { it.event }
        .fold(initial, ::evolve)
```

`effectiveOrder`: **`Synced` events first, in `serverSeq` order** (authoritative
history), **then `Pending` events by `clientSeq`** (your optimistic tail). This means an
incoming remote event slots into the authoritative section ahead of your pending ones,
and the next recompute naturally reorders everything correctly.

---

## Rejection — three rules

1. **Locally invalid command** → `decide` throws, no event produced. Never touches the
   store. Trivial.

2. **Server rejects events in a push batch** → server `commit` returns `Nack`s. Client
   flips those records to `Rejected` and re-folds the affected aggregates. Because the
   fold excludes `Rejected`, "rollback" is just recomputation.

   **Load-bearing requirement:** the server must validate a batch **sequentially** —
   fold each accepted event into its working state before validating the next. Then if
   event A is rejected, any later event B in the same batch that depended on A also fails
   server-side and is nacked too. **This removes all client-side cascade logic** — the
   client never has to reason about "is this dependent event still valid"; the server
   already did.

3. **A dependency is invalidated *after* it was already Synced** (e.g. another device or
   an admin later removes the premise) → you can't nack authoritative history. Emit a
   **compensating event** (e.g. `TaskUnassigned`). This is normal event-sourcing, not a
   sync artifact.

Rule of thumb: **dependent event still Pending → it gets nacked in the same batch;
dependent event already Synced → compensate.**

### Worked example (cascade handled entirely server-side)

Offline, on a seat-limited Project (local view: 3 members; server is actually at 5):

```
e1  MemberInvited(carol)        clientSeq 11
e2  TaskAssigned(task#7, carol)  clientSeq 12   // only valid if carol is a member
```

Reconnect, push `[e1, e2]`. Server `commit` folds sequentially:

```
e1 → NACK("seat limit")                  // server already at cap
e2 → NACK("assignee not a member")       // validated against state where e1 was rejected
```

Client applies `nack([e1, e2])`, re-folds the Project once → carol is not a member,
task#7 unassigned. No extra round-trips, no special client code.

---

## Implementation stories

Build bottom-up. Each story is independently testable.

### Story 1 — Core types & the pure fold
- Define `Recorded`, `SyncState`, `Ack`, `Nack`, `Result`.
- Define `Aggregate<S, C, E>` with `decide` / `evolve`.
- Implement `stateOf` + `effectiveOrder`.
- **Done when:** given a hand-built list of `Recorded`, folding produces correct state,
  and excluding a `Rejected` record changes the result. Pure unit tests, no storage.

### Story 2 — Persistence + `AggregateReader`
- Persist `Recorded` (SQLDelight). Implement `forAggregate` — the one shared read port.
- **Done when:** `forAggregate(id)` returns that aggregate's events in order and reads
  round-trip through the DB.

### Story 3 — `ClientEventStore` (optimistic lifecycle + pull)
- Add `append` (assign `clientSeq`, `sync = Pending`), `applyRemote` (idempotent dedup by
  `eventId`), `unsynced`, `ack`, `nack`, `stream(fromClientSeq)`.
- **Done when:** append → state reflects it immediately; applying the same remote event
  twice is a no-op; `unsynced` returns Pending; `ack` flips to `Synced(serverSeq)`;
  `nack` flips to `Rejected` and re-folding drops it.

### Story 4 — `ServerEventStore` (authority)
- Implement `commit`: load committed state via `forAggregate`, **sequentially
  fold-and-validate** the batch, assign `serverSeq` to accepted events, return
  `Ack`/`Nack` per event. Add `stream(fromServerSeq)` as the broadcast feed.
- **Done when:** a batch where a later event depends on a rejected earlier one nacks
  both; accepted events get monotonic `serverSeq`.

### Story 5 — `SyncTransport` + `SyncEngine`
- Define `SyncTransport`. Implement the client RPC transport over `RpcConnectionManager`
  and a server/in-process transport.
- Implement the generic `SyncEngine`: on connect, **pull from `lastKnownServerSeq`
  (= `max(serverSeq)` in the store) then push `unsynced`**; push on local append while
  online. Catch-up and real-time are the same subscription.
- **Done when:** two clients online see each other's events live; a client that goes
  offline, appends, and reconnects converges to the same state as the server.

### Story 6 — Rejection & compensation wiring
- Route `commit`'s nacks back through `SyncEngine` → `store.nack` → re-fold → emit new
  state + reason to the projection (or a `Flow<Rejected>` side-channel).
- Document/implement the compensating-event path for already-Synced dependencies.
- **Done when:** the worked example above passes end-to-end, and reasons surface to the
  caller.

### Story 7 — Projections / read models
- Subscribe to `stream` (or per-aggregate `stateOf`) to drive UI/read models, reacting to
  both optimistic appends and incoming remote events.
- **Done when:** a projection updates on local append, on remote arrival, and on
  rejection re-fold, without bespoke per-case code.

---

## Guardrails for the implementer

- **One table on the client.** Don't add a separate outbox — it's the
  `serverSeq IS NULL` view. A separate table reintroduces the dual-write hazard.
- **`decide` / `evolve` must stay pure.** No I/O, no clocks, no randomness inside them.
  All non-determinism (ids, timestamps) goes into the event *before* it reaches `evolve`.
- **`commit` must fold sequentially.** This is the single line that makes cascade
  rejection free. Don't validate a batch against one frozen snapshot.
- **Dedup by `eventId` everywhere remote events enter.** Networks redeliver.
- **`serverSeq` is the only authority for ordering.** `clientSeq` orders only your local
  pending tail.
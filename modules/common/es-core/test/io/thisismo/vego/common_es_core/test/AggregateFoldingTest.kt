package io.thisismo.vego.common_es_core.test

import io.thisismo.vego.common.es_core.*
import io.thisismo.vego.identity.common.UserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Clock
import kotlin.time.Instant

data class TestAddedEvent(
    val value: Int,
    override val eventId: EventId = EventId.random(),
    override val userId: UserId = UserId.nextId(),
    override val timestamp: Instant = Clock.System.now(),
    override val aggregateId: AggregateId<*> = AggregateId("haha123")
) : DomainEvent

data class TestAddCommand(
    val value: Int
)

class TestAdderAggregate(override val initial: Int = 0) : Aggregate<Int,  TestAddCommand, TestAddedEvent> {
    override fun decide(
        state: Int,
        command: TestAddCommand
    ): List<TestAddedEvent> {
        if(command.value < 0) throw IllegalArgumentException("value must be positive")

        return listOf(TestAddedEvent(command.value))
    }

    override fun evolve(state: Int, event: TestAddedEvent): Int {
        return state + event.value
    }
}

class AggregateFoldingTest {
    private val adder = TestAdderAggregate(initial = 0)

    private fun rec(value: Int, clientSeq: Long, sync: SyncState) =
        Recorded(TestAddedEvent(value), clientSeq, sync)

    @Test
    fun `folds synced by serverSeq - then pending by clientSeq - excluding rejected`() {
        val records = listOf(
            rec(10, clientSeq = 2, SyncState.Synced(serverSeq = 2)),   // authoritative, 2nd
            rec(100, clientSeq = 1, SyncState.Synced(serverSeq = 1)),  // authoritative, 1st
            rec(1, clientSeq = 5, SyncState.Pending),                  // optimistic tail
            rec(999, clientSeq = 4, SyncState.Rejected("nope")),       // excluded
        )

        val state = with(clientEffectiveOrder) { adder.stateOf(records) }

        // 0 +100 +10 +1  → 111 ; the rejected 999 never applies
        assertEquals(111, state)
    }

    @Test
    fun `decide is pure and reads nothing it shouldn't`() {
        assertEquals(listOf(TestAddedEvent(5).value),
            adder.decide(state = 42, TestAddCommand(5)).map { it.value })
    }

    @Test
    fun `decide throws exception when command is invalid`() {
        assertFailsWith<IllegalArgumentException> {
            adder.decide(state = 42, TestAddCommand(-1))
        }
    }
}
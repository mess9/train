package train.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class OutboxTest {
    private val press = exercise("Жим")
    private val a = item(press, TargetSet(), TargetSet())
    private val w = workout(block(a))
    private val set1 = w.fact(a, 1, weight = 10.0, reps = 10)

    @Test
    fun markUnmarkMarkCollapses() {
        val o = Outbox()
            .put(w)
            .put(set1)
            .put(set1.copy(done = false))
            .put(set1.copy(reps = 9))
        assertEquals(listOf(w.id, set1.id), o.pending.map { it.record.id })
        assertEquals(set1.copy(reps = 9), o.pending[1].record)
        assertEquals(0, o.pending[1].baseRev)
    }

    @Test
    fun retryAfterLostResponseKeepsOpId() {
        val o = Outbox().put(set1)
        val sent = o.next()!!
        // Ответ потерялся, правок не было — повтор с тем же opId, сервер ответит 200 без изменений
        assertEquals(sent, o.next())
        val done = o.accepted(sent.opId, set1.copy(rev = 1))
        assertEquals(0, done.size)
    }

    @Test
    fun editWhileInFlightStaysOnTopOfNewRev() {
        val sent = Outbox().put(set1)
        val opId = sent.next()!!.opId
        // Пока запрос летел, отметку сняли
        val edited = sent.put(set1.copy(done = false))
        assertNotEquals(opId, edited.next()!!.opId)
        assertEquals(0, edited.next()!!.baseRev)
        // Пришёл ответ на первую отправку
        val o = edited.accepted(opId, set1.copy(rev = 1))
        assertEquals(1, o.size)
        assertEquals(1, o.next()!!.baseRev)
        assertEquals(false, (o.next()!!.record as SetFact).done)
    }

    @Test
    fun putKeepsBaseRevOfPending() {
        // Экран мог держать старую копию записи — база не откатывается
        val o = Outbox().put(set1.copy(rev = 3)).put(set1.copy(rev = 0, reps = 5))
        assertEquals(3, o.next()!!.baseRev)
    }

    @Test
    fun conflictInActiveWorkoutPhoneWins() {
        val o = Outbox().put(set1)
        val first = o.next()!!
        val retry = o.conflict(set1.copy(rev = 4, reps = 3), phoneWins = true)
        val p = retry.next()!!
        assertEquals(4, p.baseRev)
        assertNotEquals(first.opId, p.opId)
        assertEquals(10, (p.record as SetFact).reps)
    }

    @Test
    fun conflictInFinishedWorkoutServerWins() {
        val o = Outbox().put(w).put(set1).conflict(set1.copy(rev = 4), phoneWins = false)
        assertEquals(listOf(w.id), o.pending.map { it.record.id })
    }

    @Test
    fun outboxSurvivesStorage() {
        // У замера своё поле `type` — дискриминатор полиморфной записи с ним не должен совпасть
        val weight = Measurement(newId(), BODYWEIGHT, 82.9, "kg", Instant.parse("2026-11-03T10:00:00Z"))
        val o = Outbox().put(w).put(set1).put(weight)
        val back = TrainJson.decodeFromString<Outbox>(TrainJson.encodeToString(o))
        assertEquals(o, back)
        assertTrue(back.pending[0].record is Workout)
        assertTrue(back.pending[2].record is Measurement)
    }
}

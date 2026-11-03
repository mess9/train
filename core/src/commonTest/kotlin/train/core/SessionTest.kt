package train.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class SessionTest {
    private val press = exercise("Жим")
    private val row = exercise("Тяга", perSide = true)
    private val rope = exercise("Скакалка", type = RecordType.TIME)
    private val a = item(press, TargetSet(weight = 14.0, repsMin = 10, repsMax = 12), TargetSet(weight = 14.0, repsMin = 10, repsMax = 12))
    private val b = item(row, TargetSet(weight = 14.0, repsMax = 12), TargetSet(weight = 14.0, repsMax = 12))
    private val circle = block(a, b, restAfterPassSec = 90)
    private val warm = block(item(rope, TargetSet(seconds = 120)), role = BlockRole.WARMUP)
    private val user = newId()

    private fun started(): Session =
        Session(user).start(null, WorkoutLayout(listOf(warm, circle)), listOf(press, row, rope), emptyList(), T0)

    private val Session.w get() = workout!!

    private fun Session.fact(item: LayoutItem, setNo: Int) = sets.single { it.itemId == item.id && it.setNo == setNo && !it.deleted }

    @Test
    fun startQueuesWorkoutFirst() {
        val s = started()
        assertEquals(listOf(s.w.id), s.outbox.pending.map { it.record.id })
        assertEquals(T0, s.w.startedAt)
    }

    @Test
    fun rateMarksWithPrefilledValuesThenSameRatingUnmarks() {
        var s = started().rate(circle.id, a.id, 1, Rating.NORMAL, T0 + 3.minutes)
        val f = s.fact(a, 1)
        assertTrue(f.done)
        assertEquals(14.0, f.weight)
        assertEquals(12, f.reps)
        assertEquals(a.sets[0].id, f.targetId)
        assertEquals(T0 + 3.minutes, f.doneAt)

        s = s.rate(circle.id, a.id, 1, Rating.HARD, T0 + 4.minutes)
        assertEquals(Rating.HARD, s.fact(a, 1).rating)
        assertEquals(T0 + 3.minutes, s.fact(a, 1).doneAt, "смена оценки не двигает время отметки")

        s = s.rate(circle.id, a.id, 1, Rating.HARD, T0 + 5.minutes)
        assertFalse(s.fact(a, 1).done)
        assertEquals(1, s.sets.size)
        // Тренировка и один подход — отметить, сменить, снять ушло бы одной отправкой
        assertEquals(2, s.outbox.size)
    }

    @Test
    fun editTouchesOnlyThatSet() {
        val s = started().edit(circle.id, a.id, 2, SetValues(weight = 12.0, reps = 10))
        assertEquals(12.0, s.fact(a, 2).weight)
        assertFalse(s.fact(a, 2).done)
        val rows = s.passes()[1].rows(a)
        assertEquals(14.0, rows[0].values(s.previous).weight)
    }

    @Test
    fun addAndRemoveSets() {
        var s = started().rate(circle.id, a.id, 1, Rating.NORMAL, T0).addSet(circle.id, a.id)
        val extra = s.fact(a, 3)
        assertNull(extra.targetId)
        assertEquals(14.0, extra.weight)
        assertEquals(3, s.passes()[1].passCount)

        s = s.removeSet(circle.id, a.id)
        assertEquals(listOf(1, 2), s.passes()[1].rows(a).map { it.setNo })
        // Плановый невыполненный убирается надгробием: в compare он пропущен
        s = s.removeSet(circle.id, a.id)
        assertEquals(listOf(1), s.passes()[1].rows(a).map { it.setNo })
        assertTrue(s.sets.single { it.itemId == a.id && it.setNo == 2 }.deleted)
        // Новый подход не встаёт на номер удалённого
        s = s.addSet(circle.id, a.id)
        assertEquals(listOf(1, 4), s.passes()[1].rows(a).map { it.setNo })
    }

    @Test
    fun replaceKeepsDoneSetsWithOldExercise() {
        val goblet = exercise("Гоблет-присед")
        var s = started().rate(circle.id, a.id, 1, Rating.NORMAL, T0).edit(circle.id, a.id, 2, SetValues(weight = 14.0, reps = 11))
        s = s.replace(circle.id, a.id, goblet)
        val it = s.w.layout.blocks[1].items[0]
        assertEquals(goblet.id, it.exerciseId)
        assertEquals(press.id, it.replacedFrom)
        assertEquals(press.id, s.fact(a, 1).exerciseId)
        assertEquals(goblet.id, s.fact(a, 2).exerciseId)
        assertNotNull(s.exercise(goblet.id))
        // Вторая замена помнит исходное упражнение
        s = s.replace(circle.id, a.id, exercise("Выпады"))
        assertEquals(press.id, s.w.layout.blocks[1].items[0].replacedFrom)
    }

    @Test
    fun addItemGetsSetPerPassFromLastTime() {
        val curl = exercise("Бицепс")
        val last = workout(block(item(curl, TargetSet()))).let { old ->
            old.fact(old.layout.blocks[0].items[0], 1, weight = 10.0, reps = 12)
        }
        val s = started().addItem(circle.id, curl, listOf(last))
        val added = s.w.layout.blocks[1].items.last()
        assertTrue(added.added)
        val rows = s.passes()[1].rows(added)
        assertEquals(listOf(1, 2), rows.map { it.setNo })
        assertEquals(10.0, rows[0].fact!!.weight)
        assertNull(rows[1].fact!!.weight, "прошлого второго подхода нет — пусто, а не ноль")
    }

    @Test
    fun moveSwapsVisibleNeighbours() {
        var s = started().move(circle.id, b.id, -1)
        assertEquals(listOf(b.id, a.id), s.w.layout.blocks[1].items.map { it.id })
        s = s.move(circle.id, b.id, -1)
        assertEquals(listOf(b.id, a.id), s.w.layout.blocks[1].items.map { it.id }, "выше некуда")
    }

    @Test
    fun recordTypeChangesOpenSetsOnly() {
        var s = started().rate(warm.id, warm.items[0].id, 1, Rating.EASY, T0).addSet(warm.id, warm.items[0].id)
        s = s.setRecordType(warm.id, warm.items[0].id, RecordType.REPS)
        assertEquals(RecordType.TIME, s.fact(warm.items[0], 1).recordType)
        assertEquals(RecordType.REPS, s.fact(warm.items[0], 2).recordType)
        assertEquals(RecordType.REPS, s.w.layout.blocks[0].items[0].recordType)
    }

    @Test
    fun finishedWorkoutIsFrozenAndClosable() {
        var s = started().rate(circle.id, a.id, 1, Rating.NORMAL, T0 + 2.minutes).finish(T0 + 40.minutes)
        assertNull(s.active)
        val frozen = s.rate(circle.id, a.id, 2, Rating.NORMAL, T0 + 41.minutes)
        assertEquals(s, frozen)
        s = s.comment("поясница").close()
        assertNull(s.workout)
        assertEquals("поясница", (s.outbox.pending.first().record as Workout).comment)
    }

    @Test
    fun forgottenWorkoutClosesFiveMinutesAfterLastMark() {
        val s = started().rate(circle.id, a.id, 1, Rating.NORMAL, T0 + 10.minutes)
        assertNull(s.forgottenEnd(T0 + 2.hours))
        assertEquals(T0 + 15.minutes, s.forgottenEnd(T0 + 6.hours))
    }

    @Test
    fun serverAnswersUpdateRevs() {
        var s = started().rate(circle.id, a.id, 1, Rating.NORMAL, T0)
        val sent = s.outbox.next()!!
        s = s.accepted(sent, s.w.copy(rev = 1))
        assertEquals(1, s.w.rev)
        assertEquals(1, s.outbox.size)

        // Активная тренировка: побеждает телефон — тот же подход уйдёт поверх версии сервера
        val setSent = s.outbox.next()!!
        val server = (setSent.record as SetFact).copy(rev = 5, done = false)
        s = s.conflict(server)
        assertTrue(s.fact(a, 1).done)
        assertEquals(5, s.outbox.next()!!.baseRev)

        // Завершённая: побеждает сервер
        s = s.finish(T0 + 30.minutes)
        s = s.conflict(server.copy(rev = 6))
        assertFalse(s.fact(a, 1).done)
        assertTrue(s.outbox.pending.none { it.record.id == server.id })
    }

    /** Старт дошёл до сервера, ответ потерялся, потом финиш: `409` на свою же версию финиш не отменяет. */
    @Test
    fun lostStartResponseKeepsFinish() {
        var s = started()
        val start = s.outbox.next()!!
        val server = (start.record as Workout).copy(rev = 1)
        s = s.finish(T0 + 40.minutes)
        assertEquals(0, s.outbox.next()!!.baseRev)

        val own = s.conflict(server, start.opId)
        assertEquals(T0 + 40.minutes, own.w.finishedAt)
        val again = own.outbox.next()!!
        assertEquals(1, again.baseRev)
        assertEquals(T0 + 40.minutes, (again.record as Workout).finishedAt)
        assertTrue(again.replaced.isEmpty())

        // Чужая версия — завершённую решает сервер, как и раньше
        val foreign = s.conflict(server.copy(comment = "агент"), newId())
        assertEquals("агент", foreign.w.comment)
        assertTrue(foreign.outbox.pending.none { it.record.id == server.id })
    }

    /** Отметка дошла, ответ потерялся, её поправили и закончили тренировку: правка уходит поверх. */
    @Test
    fun lostSetResponseKeepsEdit() {
        var s = started()
        s = s.accepted(s.outbox.next()!!, s.w.copy(rev = 1))
        s = s.rate(circle.id, a.id, 1, Rating.NORMAL, T0)
        val mark = s.outbox.next()!!
        s = s.edit(circle.id, a.id, 1, SetValues(weight = 16.0, reps = 10)).finish(T0 + 40.minutes)
        assertNull(s.active)
        assertEquals(mark.record.id, s.outbox.next()!!.record.id)

        s = s.conflict((mark.record as SetFact).copy(rev = 1), mark.opId)
        assertEquals(16.0, s.fact(a, 1).weight)
        val again = s.outbox.next()!!
        assertEquals(1, again.baseRev)
        assertEquals(16.0, (again.record as SetFact).weight)
    }

    @Test
    fun sessionSurvivesStorage() {
        val store = object : Storage {
            val m = mutableMapOf<String, String>()
            override fun read(key: String) = m[key]
            override fun write(key: String, value: String) { m[key] = value }
            override fun remove(key: String) { m.remove(key) }
        }
        val s = started().rate(circle.id, a.id, 1, Rating.NORMAL, T0)
        store.saveSession(s)
        assertEquals(s, store.loadSession())
        store.write(SESSION_KEY, "{битый")
        assertNull(store.loadSession())
    }
}

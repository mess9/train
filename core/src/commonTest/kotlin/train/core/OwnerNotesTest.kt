package train.core

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Поля и правила итерации 2: сердечко и заметка к упражнению, день тренировки, вес тела, версия. */
class OwnerNotesTest {
    private val press = exercise("Жим")
    private val a = item(press, TargetSet(weight = 14.0, repsMax = 12))
    private val circle = block(a)

    private fun started(now: Instant = T0, offset: UtcOffset? = null) =
        Session(newId()).start(null, WorkoutLayout(listOf(circle)), listOf(press), emptyList(), now, offset)

    private val Session.item get() = workout!!.layout.blocks.single().items.single()

    @Test
    fun likeAndCommentGoToQueueAndCompare() {
        var s = started().like(circle.id, a.id, true).itemComment(circle.id, a.id, "  локоть ноет ")
        assertTrue(s.item.liked)
        assertEquals("локоть ноет", s.item.comment)
        assertEquals(1, s.outbox.size, "тренировка в очереди одной записью")
        assertEquals<SyncRecord?>(s.workout, s.outbox.pending.single().record)

        val c = compare(s.workout!!, emptyList(), mapOf(press.id to press))
        val ic = c.blocks.single().items.single()
        assertTrue(ic.liked)
        assertEquals("локоть ноет", ic.comment)

        s = s.itemComment(circle.id, a.id, " ").like(circle.id, a.id, false)
        assertNull(s.item.comment)
        assertFalse(s.item.liked)
    }

    @Test
    fun likeWorksAfterFinish() {
        val s = started().finish(T0 + 30.minutes).like(circle.id, a.id, true)
        assertTrue(s.item.liked)
        assertTrue(s.close().workout == null)
    }

    @Test
    fun workoutDateIsPhoneStartDate() {
        // 22:30 UTC — в Ереване (+4) уже следующий день
        val late = Instant.parse("2026-11-04T22:30:00Z")
        val w = started(late, UtcOffset(hours = 4)).workout!!
        assertEquals(UtcOffset(hours = 4), w.utcOffset)
        assertEquals(LocalDate(2026, 11, 5), w.date)
        assertEquals(LocalDate(2026, 11, 5), w.localDate(TimeZone.UTC))
        // Старая запись без смещения — по поясу сервера
        assertEquals(LocalDate(2026, 11, 4), w.copy(utcOffset = null).localDate(TimeZone.UTC))
    }

    @Test
    fun oldWorkoutJsonStillReads() {
        val json = """{"id":"019a4a2e-0000-7000-8000-000000000001","startedAt":"2026-11-04T10:00:00Z","layout":{"blocks":[]}}"""
        val w = TrainJson.decodeFromString(Workout.serializer(), json)
        assertNull(w.utcOffset)
        assertNull(w.date)
        val withOffset = TrainJson.encodeToString(Workout.serializer(), w.copy(utcOffset = UtcOffset(hours = 4)))
        assertTrue("\"utcOffset\":\"+04:00\"" in withOffset, withOffset)
    }

    @Test
    fun bodyweightPrefersWeighingOnWorkout() {
        val w = workout(circle)
        val before = Measurement(newId(), BODYWEIGHT, 82.0, "kg", T0 - 72.hours)
        val after = Measurement(newId(), BODYWEIGHT, 80.5, "kg", T0 + 1.hours, workoutId = w.id)
        val waist = Measurement(newId(), "waist", 90.0, "cm", T0 + 1.hours, workoutId = w.id)
        assertEquals(82.0, bodyweightFor(w, listOf(before)))
        assertEquals(80.5, bodyweightFor(w, listOf(before, after, waist)))
        assertEquals(82.0, bodyweightFor(w, listOf(before, after.copy(deleted = true))))
        assertNull(bodyweightFor(w, listOf(after.copy(workoutId = null))), "без привязки замер после старта не берётся")

        val c = compare(w, emptyList(), emptyMap(), measurements = listOf(before, after, waist), heightCm = 180.0)
        assertEquals(listOf(after.id, waist.id).toSet(), c.measurements.map { it.id }.toSet())
        assertEquals(180.0, c.heightCm)
    }

    @Test
    fun measurementLinksToRecentWorkout() {
        val done = workout(circle).copy(finishedAt = T0 + 1.hours)
        assertEquals(done.id, workoutForMeasurement(listOf(done), T0 + 2.hours))
        assertNull(workoutForMeasurement(listOf(done), T0 + 5.hours), "через 4 часа после конца — сам по себе")
        assertNull(workoutForMeasurement(listOf(done), T0 - 1.hours), "до старта — не к ней")
        assertNull(workoutForMeasurement(listOf(done.copy(deleted = true)), T0 + 2.hours))

        val active = workout(circle).copy(id = newId(), startedAt = T0 + 3.hours)
        assertEquals(active.id, workoutForMeasurement(listOf(done, active), T0 + 3.5.hours), "идущая — новее")
    }

    @Test
    fun fullVersionHasCommit() {
        assertEquals("0.2.0+5cdae85", fullVersion("0.2.0", "5cdae85"))
        assertEquals("0.2.0+dev", fullVersion("0.2.0", null))
        assertTrue(Regex("""\d+\.\d+\.\d+""").matches(TRAIN_VERSION))
    }
}

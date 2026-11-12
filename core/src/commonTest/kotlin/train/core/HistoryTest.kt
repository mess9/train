package train.core

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class HistoryTest {
    private val bench = exercise("Жим", implements = 2)
    private val pull = exercise("Подтягивания", type = RecordType.REPS, bodyweightFactor = 1.0)
    private val exercises = listOf(bench, pull).associateBy { it.id }
    private val workouts = mutableListOf<Workout>()
    private val sets = mutableListOf<SetFact>()

    /** Тренировка дня [d]: подходы (вес, повторы) упражнения [ex], цель — те же числа. */
    private fun day(d: LocalDate, ex: Exercise, vararg wr: Pair<Double, Int>, role: BlockRole = BlockRole.MAIN): Workout {
        val row = item(ex, *wr.map { TargetSet(weight = it.first, repsMax = it.second) }.toTypedArray())
        val start = d.atStartOfDayIn(TimeZone.UTC) + 10.hours
        val w = workout(block(row, role = role)).copy(startedAt = start, finishedAt = start + 60.minutes, date = d)
        workouts += w
        wr.forEachIndexed { i, (wt, r) -> sets += w.fact(row, i + 1, weight = wt, reps = r).copy(doneAt = start + (i + 1).minutes) }
        return w
    }

    private fun d(month: Int, day: Int) = LocalDate(2026, month, day)

    @Test
    fun trendRecordAndEarlierByMonth() {
        day(d(9, 1), bench, 12.0 to 10)
        day(d(11, 3), bench, 12.0 to 10, 12.0 to 8)
        day(d(11, 5), bench, 14.0 to 8)
        day(d(11, 7), bench, 14.0 to 8)

        val h = history(listOf(bench.id), workouts, sets, exercises, recent = 2).single()
        assertEquals(4, h.sessions)
        assertEquals(d(9, 1), h.first)
        assertEquals(listOf(d(11, 7), d(11, 5)), h.recent.map { it.date })
        assertEquals(listOf(Trend.SAME, Trend.UP), h.recent.map { it.trend })
        // Рекорд — первая тренировка, где лучший подход достиг максимума
        assertEquals(d(11, 5), h.record?.date)
        assertEquals(1, h.sinceRecord)
        assertEquals(listOf("2026-11", "2026-09"), h.earlier.map { it.month })
        assertEquals(12.0 * (1 + 10 / 30.0), h.earlier[0].best.score)
        assertEquals(listOf(10, 8), h.earlier[0].best.sets.map { it.reps })
        assertEquals(14.0 to 8, h.recent[0].targets.single().let { it.weight to it.repsMax })
    }

    @Test
    fun bodyweightScoresPullUps() {
        val w = day(d(11, 3), pull, 0.0 to 8)
        val weight = Measurement(newId(), BODYWEIGHT, 60.0, "kg", w.startedAt - 24.hours)
        val withWeight = history(listOf(pull.id), workouts, sets, exercises, listOf(weight)).single()
        assertEquals(60.0 * (1 + 8 / 30.0), withWeight.recent.single().score)
        val without = history(listOf(pull.id), workouts, sets, exercises).single()
        assertEquals(8.0, without.recent.single().score)
    }

    @Test
    fun unknownExerciseHasEmptyHistory() {
        val h = history(listOf(bench.id), emptyList(), emptyList(), exercises).single()
        assertEquals(0, h.sessions)
        assertNull(h.record)
        assertTrue(h.recent.isEmpty())
    }

    @Test
    fun journalOfExerciseOnly() {
        day(d(11, 3), bench, 12.0 to 10)
        val entries = listOf(
            JournalEntry(newId(), d(11, 3), "жим: держать 12", "r", "v", exerciseIds = listOf(bench.id), createdAt = T0),
            JournalEntry(newId(), d(11, 4), "общая", "r", "v", createdAt = T0),
            JournalEntry(newId(), d(11, 5), "удалена", "r", "v", exerciseIds = listOf(bench.id), createdAt = T0, deletedAt = T0),
        )
        val h = history(listOf(bench.id), workouts, sets, exercises, journal = entries).single()
        assertEquals(listOf("жим: держать 12"), h.journal.map { it.decision })
    }

    @Test
    fun sinceKeepsWholeWindow() {
        day(d(9, 10), bench, 10.0 to 10)
        day(d(10, 20), bench, 12.0 to 10)
        day(d(11, 3), bench, 12.0 to 10)
        day(d(11, 5), bench, 14.0 to 8)
        val h = history(listOf(bench.id), workouts, sets, exercises, recent = 1, since = d(10, 15)).single()
        assertEquals(listOf(d(11, 5), d(11, 3), d(10, 20)), h.recent.map { it.date })
        assertEquals(listOf("2026-09"), h.earlier.map { it.month })
    }

    @Test
    fun mainExercisesNewestFirstWithoutWarmup() {
        day(d(11, 3), pull, 0.0 to 8)
        day(d(11, 4), bench, 12.0 to 10, role = BlockRole.WARMUP)
        day(d(11, 5), bench, 12.0 to 10)
        assertEquals(listOf(bench.id, pull.id), mainExercises(workouts, sets))
        assertEquals(listOf(pull.id), mainExercises(workouts.take(2), sets))
    }

    @Test
    fun weeksIncludeEmptyOnes() {
        day(d(11, 3), bench, 12.0 to 10, 12.0 to 8)
        day(d(11, 5), bench, 14.0 to 8)
        val ws = weeks(d(10, 28), d(11, 8), workouts, sets, exercises)
        assertEquals(listOf(d(10, 26), d(11, 2)), ws.map { it.from })
        assertEquals(0, ws[0].workouts)
        with(ws[1]) {
            assertEquals(2, workouts)
            assertEquals(listOf(60, 60), minutes)
            assertEquals(3, workSets)
            assertEquals(3, normal)
            assertEquals((12.0 * 18 + 14.0 * 8) * 2, volumeKg)
        }
        assertEquals(
            "26.10–01.11: нет тренировок\n" +
                "02.11–08.11: 2 трен. (60, 60 мин) · рабочих 3: ↑0 ✓3 ✕0 · сверх плана 0, пропущено 0 · объём 0.7 т",
            ws.toText(),
        )
    }
}

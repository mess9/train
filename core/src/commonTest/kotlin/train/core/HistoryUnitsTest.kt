package train.core

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days

class HistoryUnitsTest {
    private val ex = exercise("Время или повторы", type = RecordType.TIME)
    private val workouts = mutableListOf<Workout>()
    private val sets = mutableListOf<SetFact>()

    private fun day(day: Int, type: RecordType, value: Int) {
        val row = item(ex.copy(recordType = type))
        val w = workout(block(row)).copy(startedAt = T0 + day.days, date = LocalDate(2026, 11, day))
        workouts += w
        sets += w.fact(row, 1, reps = value.takeIf { type == RecordType.REPS }, seconds = value.takeIf { type == RecordType.TIME })
    }

    private fun result(recent: Int = 100) = history(listOf(ex.id), workouts, sets, mapOf(ex.id to ex), recent = recent).single()

    @Test
    fun differentUnitsAtWindowBoundaryHideOnlyPhoneTrend() {
        day(1, RecordType.TIME, 60)
        day(2, RecordType.REPS, 10)
        val h = result(recent = 1)
        val latest = h.recent.single()
        assertEquals(Trend.DOWN, latest.trend) // Прежний числовой результат API/CLI.
        assertEquals(ScoreUnit.REPS, latest.scoreUnit)
        assertEquals(ScoreUnit.SECONDS, latest.previousScoreUnit)
        assertNull(latest.comparableTrend)
        // Рекорд — в единицах последней тренировки: 60 с и 10 повторов не сравниваются.
        assertEquals(10.0, h.record?.score)
        assertEquals(LocalDate(2026, 11, 2), h.record?.date)
        assertEquals(h, TrainJson.decodeFromString(ExerciseHistory.serializer(), TrainJson.encodeToString(ExerciseHistory.serializer(), h)))
    }

    @Test
    fun mixedUnitsHiddenByMonthSelectionStillSuppressPlateau() {
        day(1, RecordType.TIME, 120)
        day(2, RecordType.REPS, 10)
        day(3, RecordType.TIME, 90)
        day(4, RecordType.TIME, 90)
        day(5, RecordType.TIME, 90)
        val h = result(recent = 1)
        assertEquals(4, h.sinceRecord)
        assertEquals(setOf(ScoreUnit.SECONDS), (h.recent + h.earlier.map { it.best }).map { it.scoreUnit }.toSet())
        assertEquals(setOf(ScoreUnit.SECONDS, ScoreUnit.REPS), h.scoreUnits)
        assertFalse(h.hasComparablePlateau)
        assertEquals(Trend.SAME, h.recent.single().comparableTrend) // Соседние секунды всё ещё сравнимы.
        assertEquals(120.0, h.record?.score)
    }

    @Test
    fun uniformHistoryKeepsPlateauAndArrows() {
        day(1, RecordType.TIME, 60)
        day(2, RecordType.TIME, 50)
        day(3, RecordType.TIME, 55)
        day(4, RecordType.TIME, 58)
        val h = result()
        assertEquals(3, h.sinceRecord)
        assertTrue(h.hasComparablePlateau)
        assertEquals(listOf(Trend.UP, Trend.UP, Trend.DOWN, null), h.recent.map { it.comparableTrend })
        assertEquals(h.recent.map { it.trend }, h.recent.map { it.comparableTrend })
    }

    @Test
    fun oldServerWithoutUnitsRemainsReadableAndMakesNoComparabilityClaims() {
        val old = """{"exerciseId":"${ex.id}","recordType":"TIME","sessions":4,"sinceRecord":3,
            "recent":[{"workoutId":"${newId()}","date":"2026-11-01","sets":[],"score":60,"trend":"DOWN"}]}"""
        val h = TrainJson.decodeFromString(ExerciseHistory.serializer(), old)
        assertEquals(Trend.DOWN, h.recent.single().trend)
        assertNull(h.recent.single().comparableTrend)
        assertFalse(h.hasComparablePlateau)
    }
}

package train.core

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

class RecordsTest {
    private val ex = exercise("Жим", implements = 2)
    private val item = item(ex, TargetSet(), TargetSet(), TargetSet())
    private val old = workout(block(item)).copy(startedAt = T0 - 1.days, finishedAt = T0 - 1.days + 30.minutes, date = LocalDate(2026, 11, 2))
    private val current = workout(block(item)).copy(date = LocalDate(2026, 11, 3))
    private val catalog = mapOf(ex.id to ex)
    private val past = old.fact(item, 1, weight = 10.0, reps = 10)
    private val best = records(listOf(old), listOf(past), catalog)

    @Test
    fun onlyBestSetWinsAndEditingOrUnmarkingRecalculates() {
        val first = current.fact(item, 1, weight = 11.0, reps = 10)
        // Прибавка между подходами меньше 0,5 %: кубок всё равно у лучшего.
        val second = current.fact(item, 2, weight = 11.01, reps = 10)
        val weaker = current.fact(item, 3, weight = 10.0, reps = 8)
        assertEquals(mapOf(second.id to best.exercises.single()), setRecords(listOf(weaker, second, first), catalog, null, best))
        assertEquals(setOf(first.id), setRecords(listOf(first, second.copy(done = false)), catalog, null, best).keys)
        assertEquals(setOf(first.id), setRecords(listOf(first, second.copy(deleted = true)), catalog, null, best).keys)
        assertEquals(setOf(first.id), setRecords(listOf(first, second.copy(weight = 9.0)), catalog, null, best).keys)
        assertEquals(setOf(first.id), setRecords(listOf(first, second.copy(weight = first.weight)), catalog, null, best).keys)
    }

    @Test
    fun toleranceFirstExerciseAndUnknownValues() {
        val f = current.fact(item, 1, weight = 10.04, reps = 10)
        assertTrue(setRecords(listOf(f), catalog, null, best).isEmpty())
        assertTrue(setRecords(listOf(f.copy(weight = 10.05)), catalog, null, best).isEmpty())
        assertEquals(setOf(f.id), setRecords(listOf(f.copy(weight = 10.051)), catalog, null, best).keys)
        assertTrue(setRecords(listOf(f.copy(weight = 20.0)), catalog, null, Records()).isEmpty())
        assertTrue(setRecords(listOf(f.copy(weight = null)), catalog, null, best).isEmpty())
    }

    @Test
    fun bodyweightPullUpsUseSameScoreAsHistory() {
        val pull = exercise("Подтягивания", type = RecordType.REPS, bodyweightFactor = 1.0)
        val row = item(pull, TargetSet())
        val previousWorkout = workout(block(row)).copy(startedAt = old.startedAt, date = old.date)
        val previous = previousWorkout.fact(row, 1, reps = 8)
        val weight = Measurement(newId(), BODYWEIGHT, 60.0, "kg", old.startedAt - 1.days)
        val previousBest = records(listOf(previousWorkout), listOf(previous), mapOf(pull.id to pull), listOf(weight))
        val next = workout(block(row)).fact(row, 1, reps = 9)
        assertEquals(60.0 * (1 + 8 / 30.0), previousBest.exercises.single().score)
        assertEquals(setOf(next.id), setRecords(listOf(next), mapOf(pull.id to pull), 60.0, previousBest).keys)
        assertTrue(setRecords(listOf(next), mapOf(pull.id to pull), null, previousBest).isEmpty())
    }

    @Test
    fun recordsAreKeptPerScoreUnit() {
        val plank = exercise("Планка", type = RecordType.WEIGHT_TIME)
        val row = item(plank, TargetSet())
        val previousWorkout = workout(block(row)).copy(startedAt = old.startedAt, date = old.date)
        val bare = previousWorkout.fact(row, 1, seconds = 90)
        val catalog = mapOf(plank.id to plank)
        val previousBest = records(listOf(previousWorkout), listOf(bare), catalog)
        assertEquals(listOf(ScoreUnit.SECONDS), previousBest.exercises.map { it.unit })
        val next = workout(block(row))
        // 5 кг × 30 с = 150 кг·с — больше 90, но это другие единицы: не рекорд.
        assertTrue(setRecords(listOf(next.fact(row, 1, weight = 5.0, seconds = 30)), catalog, null, previousBest).isEmpty())
        val longer = next.fact(row, 2, seconds = 100)
        assertEquals(setOf(longer.id), setRecords(listOf(next.fact(row, 1, weight = 5.0, seconds = 30), longer), catalog, null, previousBest).keys)
        val both = previousBest.withCompletedWorkout(next.copy(finishedAt = next.startedAt + 30.minutes),
            listOf(next.fact(row, 1, weight = 5.0, seconds = 30)), catalog)
        assertEquals(setOf(ScoreUnit.SECONDS, ScoreUnit.KG_SECONDS), both.exercises.mapNotNull { it.unit }.toSet())
    }

    @Test
    fun fullHistoryExclusionAndComparisonAgree() {
        val f = current.fact(item, 1, weight = 12.0, reps = 10)
        val all = listOf(old, current)
        val facts = listOf(past, f)
        val previous = records(all, facts, catalog, exclude = current.id)
        assertEquals(best, previous)
        assertEquals(current.id, records(all, facts, catalog).exercises.single().workoutId)
        val c = compare(current, listOf(f), catalog, best = previous)
        assertEquals(best.exercises.single(), c.blocks.single().items.single().sets.first().record)
        assertEquals(best.volume, c.volumeRecord)
        assertTrue("рекорд" in c.toText())
        assertTrue("рекорд объёма" in c.toText())
        assertEquals(best, records(all, facts, catalog, before = current.startedAt))
        assertTrue(records(all.map { it.copy(deleted = true) }, facts, catalog).exercises.isEmpty())
    }

    @Test
    fun volumeUsesKnownPartAndIsIndependentOfExerciseFilter() {
        val f = current.fact(item, 1, weight = 12.0, reps = 10)
        val unknown = current.fact(item, 2, weight = null, reps = 10)
        val c = compare(current, listOf(f, unknown), catalog, best = best)
        assertEquals(240.0, c.totals.volumeKg)
        assertEquals(1, c.totals.volumeUnknownSets)
        assertEquals(200.0, c.volumeRecord?.volumeKg)
        assertNull(volumeRecord(200.0, best))
        assertNull(volumeRecord(199.0, best))
        assertNull(volumeRecord(300.0, Records()))
        val filtered = records(listOf(old), listOf(past), catalog, ids = listOf(newId()))
        assertTrue(filtered.exercises.isEmpty())
        assertEquals(best.volume, filtered.volume)
    }

    @Test
    fun completedOfflineWorkoutRaisesBaselineForTheNextOne() {
        val finished = current.copy(finishedAt = T0 + 30.minutes)
        val strongest = finished.fact(item, 1, weight = 12.0, reps = 10)
        val cached = best.withCompletedWorkout(finished, listOf(strongest), catalog)
        assertEquals(finished.id, cached.exercises.single().workoutId)
        assertEquals(240.0, cached.volume?.volumeKg)
        // 11 кг бьют прежние 10, но не только что сделанные 12: ложного кубка нет.
        val next = workout(block(item)).fact(item, 1, weight = 11.0, reps = 10)
        assertTrue(setRecords(listOf(next), catalog, null, cached).isEmpty())
        assertNull(volumeRecord(220.0, cached))
        assertEquals(best, best.withCompletedWorkout(current, listOf(strongest), catalog))
        assertEquals(best, best.withCompletedWorkout(finished.copy(deleted = true), listOf(strongest), catalog))
    }

    @Test
    fun firstOfflineExerciseEstablishesBaselineWithoutCelebratingItself() {
        val finished = current.copy(finishedAt = T0 + 30.minutes)
        val first = finished.fact(item, 1, weight = 12.0, reps = 10)
        assertTrue(setRecords(listOf(first), catalog, null, Records()).isEmpty())
        val cached = Records().withCompletedWorkout(finished, listOf(first), catalog)
        assertEquals(finished.id, cached.exercises.single().workoutId)
        assertEquals(240.0, cached.volume?.volumeKg)
    }

    @Test
    fun staleServerResponseCannotLowerPendingOfflineRecords() {
        val finished = current.copy(finishedAt = T0 + 30.minutes)
        val local = best.withCompletedWorkout(finished, listOf(finished.fact(item, 1, weight = 12.0, reps = 10)), catalog)
        assertEquals(local, best.withPending(local, setOf(finished.id)))
        // После отправки сервер авторитетен, в том числе после удаления/исправления истории.
        assertEquals(best, best.withPending(local, emptySet()))
        assertEquals(best, best.withPending(local, setOf(finished.id), exclude = finished.id))
    }

    @Test
    fun baselineSurvivesSessionSerializationAndDoesNotContainDerivedCups() {
        val s = Session(newId(), workout = current, sets = listOf(current.fact(item, 1, weight = 12.0, reps = 10)), records = best)
        assertEquals(s, TrainJson.decodeFromString<Session>(TrainJson.encodeToString(s)))
        assertNull(TrainJson.decodeFromString<Session>("""{"userId":"${s.userId}"}""").records)
        assertFalse("volumeRecord" in TrainJson.encodeToString(s))
        val self = records(listOf(current), s.sets, catalog).without(current.id)
        assertEquals(Records(), self)
    }
}

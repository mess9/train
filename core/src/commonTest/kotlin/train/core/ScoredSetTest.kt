package train.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ScoredSetTest {
    private fun score(type: RecordType, weight: Double? = null, reps: Int? = null, extra: Double? = null, seconds: Int? = null,
                      factor: Double? = null, bodyweight: Double? = null): ScoredSet? {
        val set = DoneSet(1, type, weight = weight, reps = reps, extraWeight = extra, seconds = seconds)
        val scored = scoredSet(set, factor, bodyweight)
        assertEquals(setScore(set, factor, bodyweight), scored?.value)
        return scored
    }

    @Test
    fun weightedRepsEstimateOneRepButZeroWeightCountsReps() {
        assertEquals(ScoredSet(40.0, ScoreUnit.ESTIMATED_KG), score(RecordType.WEIGHT_REPS, 30.0, 10))
        assertEquals(ScoredSet(10.0, ScoreUnit.REPS), score(RecordType.WEIGHT_REPS, 0.0, 10))
        assertNull(score(RecordType.WEIGHT_REPS, reps = 10))
        assertNull(score(RecordType.WEIGHT_REPS, weight = 30.0))
    }

    @Test
    fun repsUseBodyweightOnlyWhenKnownAndOtherwiseExplainExtraLoad() {
        assertEquals(ScoredSet(120.0, ScoreUnit.ESTIMATED_KG), score(RecordType.REPS, reps = 10, extra = 10.0, factor = 1.0, bodyweight = 80.0))
        assertEquals(ScoreUnit.ESTIMATED_KG, score(RecordType.REPS, reps = 10, factor = 0.65, bodyweight = 80.0)?.unit)
        assertEquals(ScoredSet(10.0, ScoreUnit.REPS), score(RecordType.REPS, reps = 10, factor = 1.0))
        assertEquals(ScoredSet(15.0, ScoreUnit.REPS_PLUS_KG), score(RecordType.REPS, reps = 10, extra = 5.0))
        assertNull(score(RecordType.REPS))
    }

    @Test
    fun timeAndLoadedTimeHaveDifferentUnits() {
        assertEquals(ScoredSet(60.0, ScoreUnit.SECONDS), score(RecordType.TIME, seconds = 60))
        assertEquals(ScoredSet(600.0, ScoreUnit.KG_SECONDS), score(RecordType.WEIGHT_TIME, weight = 10.0, seconds = 60))
        assertEquals(ScoredSet(60.0, ScoreUnit.SECONDS), score(RecordType.WEIGHT_TIME, seconds = 60))
        assertEquals(ScoredSet(60.0, ScoreUnit.SECONDS), score(RecordType.WEIGHT_TIME, weight = 0.0, seconds = 60))
        assertNull(score(RecordType.TIME))
        assertNull(score(RecordType.WEIGHT_TIME, weight = 10.0))
    }
}

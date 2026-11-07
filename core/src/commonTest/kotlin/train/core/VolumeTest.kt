package train.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VolumeTest {
    private fun set(type: RecordType, weight: Double? = null, reps: Int? = null, extra: Double? = null, seconds: Int? = null, perSide: Boolean = false) =
        SetFact(newId(), newId(), newId(), newId(), 1, exerciseId = newId(), recordType = type, perSide = perSide,
            weight = weight, reps = reps, extraWeight = extra, seconds = seconds, done = true)

    @Test
    fun dumbbellsCountBoth() {
        val press = exercise("Жим гантелей", implements = 2)
        assertEquals(2 * 12.5 * 10, set(RecordType.WEIGHT_REPS, 12.5, 10).volume(press, null))
    }

    @Test
    fun perSideDoubles() {
        val lunge = exercise("Выпады", implements = 2, perSide = true)
        assertEquals(2 * 8.0 * 10 * 2, set(RecordType.WEIGHT_REPS, 8.0, 10, perSide = true).volume(lunge, null))
    }

    @Test
    fun bodyweightShare() {
        val pushUp = exercise("Отжимания", type = RecordType.REPS, bodyweightFactor = 0.65)
        assertEquals(0.65 * 80 * 20, set(RecordType.REPS, reps = 20).volume(pushUp, 80.0))
        // Жилет 10 кг поверх доли веса
        assertEquals((0.65 * 80 + 10) * 20, set(RecordType.REPS, reps = 20, extra = 10.0).volume(pushUp, 80.0))
        // Доля есть, веса нет — неизвестно
        assertNull(set(RecordType.REPS, reps = 20).volume(pushUp, null))
    }

    @Test
    fun weightedExerciseWithBodyweight() {
        val goblet = exercise("Гоблет-присед", bodyweightFactor = 0.7)
        assertEquals((16.0 + 0.7 * 80) * 10, set(RecordType.WEIGHT_REPS, 16.0, 10).volume(goblet, 80.0))
    }

    @Test
    fun nothingToCount() {
        // Повторы без веса и без доли — объём неизвестен, а не ноль
        assertNull(set(RecordType.REPS, reps = 20).volume(exercise("Скручивания", type = RecordType.REPS), 80.0))
        assertNull(set(RecordType.WEIGHT_REPS, reps = 10).volume(exercise("Тяга"), null))
        assertNull(set(RecordType.TIME, seconds = 60).volume(exercise("Скакалка", type = RecordType.TIME, bodyweightFactor = 0.1), 80.0))
    }

    @Test
    fun whoHasVolume() {
        val crunch = exercise("Скручивания", type = RecordType.REPS)
        val pushUp = exercise("Отжимания", type = RecordType.REPS, bodyweightFactor = 0.65)
        // Повторы без веса и без доли — объёма нет вовсе, не «неизвестен»
        assertEquals(false, set(RecordType.REPS, reps = 20).hasVolume(crunch))
        assertEquals(true, set(RecordType.REPS, reps = 20, extra = 5.0).hasVolume(crunch))
        assertEquals(true, set(RecordType.REPS, reps = 20).hasVolume(pushUp))
        assertEquals(true, set(RecordType.WEIGHT_REPS, reps = 10).hasVolume(exercise("Тяга")))
        assertEquals(false, set(RecordType.WEIGHT_TIME, weight = 20.0, seconds = 40).hasVolume(exercise("Фермер")))
    }

    @Test
    fun timeUnderLoad() {
        assertEquals(60, set(RecordType.TIME, seconds = 60).timeUnderLoad)
        assertEquals(60, set(RecordType.TIME, seconds = 30, perSide = true).timeUnderLoad)
        assertEquals(40, set(RecordType.WEIGHT_TIME, weight = 20.0, seconds = 40).timeUnderLoad)
        assertNull(set(RecordType.REPS, reps = 10, seconds = 30).timeUnderLoad)
    }
}

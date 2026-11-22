package train.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class TimersTest {
    @Test
    fun stopwatchResetStops() {
        // Замечание 19: «Сброс» обнулял, но секундомер шёл дальше
        val running = Tools().stopwatchToggle(T0)
        val reset = running.stopwatchReset()
        assertNull(reset.swStart)
        assertEquals(0, reset.stopwatchMs(T0 + 30.seconds))
        // Пауза, потом сброс — тоже ноль и стоит
        val paused = running.stopwatchToggle(T0 + 7.seconds)
        assertEquals(7000, paused.stopwatchMs(T0 + 60.seconds))
        assertEquals(0, paused.stopwatchReset().stopwatchMs(T0 + 90.seconds))
    }

    @Test
    fun timerStartPauseRestart() {
        var t = Tools().timerPreset(60).timerToggle(T0)
        assertEquals(60_000, t.timerLeftMs(T0))
        t = t.timerToggle(T0 + 20.seconds)
        assertNull(t.timerUntil)
        assertEquals(40, t.timerLeft)
        t = t.timerToggle(T0 + 100.seconds).timerShift(15, T0 + 100.seconds)
        assertEquals(55_000, t.timerLeftMs(T0 + 100.seconds))
        assertTrue(t.timerFinished(T0 + 200.seconds))
        assertEquals(60_000, t.timerToggle(T0 + 200.seconds).timerLeftMs(T0 + 200.seconds), "после нуля — заново на всю длину")
    }

    @Test
    fun timerPresetsParseSortAndReject() {
        // Пример владельца: строки «м:сс» и секунды числом — по возрастанию
        assertEquals(listOf(35, 60, 70, 300), parseTimerPresets("1:10\n1:00\n35\n5:00").getOrThrow())
        assertEquals(listOf(30, 90), parseTimerPresets(" 90, 0:30 ,1:30 ").getOrThrow(), "повтор 1:30 = 90 убран")
        assertEquals(listOf("1:10", "0:35"), listOf(70, 35).map(::presetText))
        for (bad in listOf("", "  \n ", "1:75", "abc", "0", "0:00", "100:00", "1.5", (1..11).joinToString { "$it" })) {
            assertTrue(parseTimerPresets(bad).isFailure, "«$bad» не принят")
        }
        assertEquals(DEFAULT_TIMER_PRESETS, Profile().presets)
        assertEquals(listOf(45), Profile(timerPresets = listOf(45)).presets)
    }

    private val press = exercise("Жим")
    private val pull = exercise("Подтягивания", type = RecordType.REPS)
    private val plank = exercise("Планка", type = RecordType.TIME)

    @Test
    fun circuitWithoutExerciseRestCountsUpBetweenExercises() {
        // Замечание 13: в плане 04.11 у упражнений круга отдыха нет, только после прохода
        val a = item(press, TargetSet(), TargetSet(), restSec = null)
        val b = item(pull, TargetSet(), TargetSet(), restSec = null)
        val circle = block(a, b, restAfterPassSec = 120)
        val w = workout(circle)
        val between = BlockPasses(circle, listOf(w.fact(a, 1))).restTimer(a, 1, T0)
        assertNotNull(between)
        assertNull(between.until, "отдыха в плане нет — счёт вверх, а не выдуманное число")
        assertEquals(T0, between.since)
        assertEquals("Отдых между упражнениями", between.label)
        // Проход закрыт — отдых после прохода по плану
        val pass = BlockPasses(circle, listOf(w.fact(a, 1), w.fact(b, 1))).restTimer(b, 1, T0)
        assertEquals(T0 + 120.seconds, pass?.until)
        assertEquals("Отдых после прохода по плану · 120 с", pass?.label)
    }

    @Test
    fun plannedExerciseRestCountsDown() {
        val a = item(press, TargetSet(), TargetSet(), restSec = 45)
        val b = item(plank, TargetSet(), TargetSet(), restSec = null)
        val circle = block(a, b, restAfterPassSec = 120)
        val w = workout(circle)
        val r = BlockPasses(circle, listOf(w.fact(a, 1))).restTimer(a, 1, T0)
        assertEquals(T0 + 45.seconds, r?.until)
        assertEquals("Отдых по плану · 45 с", r?.label)
    }

    @Test
    fun singleExerciseWithoutRestHasNoBar() {
        val a = item(press, TargetSet(), TargetSet(), restSec = null)
        val blk = block(a, restAfterPassSec = null)
        val w = workout(blk)
        assertNull(BlockPasses(blk, listOf(w.fact(a, 1))).restTimer(a, 1, T0))
    }

    @Test
    fun restShift() {
        val r = RestTimer(T0, T0 + 60.seconds, 60, "")
        assertEquals(T0 + 45.seconds, r.shift(-15, T0).until)
        // Отдых вышел — +15 от «сейчас»
        assertEquals(T0 + 95.seconds, r.shift(15, T0 + 80.seconds).until)
        val open = RestTimer(T0, null, 0, "")
        assertEquals(open, open.shift(15, T0))
    }
}

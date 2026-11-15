package train.core

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.asTimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class HealthTest {
    private val off = 4 * 3600
    private val zone = UtcOffset(seconds = off).asTimeZone()
    private val band = "com.xiaomi.wearable"

    private fun at(d: Int, h: Int, m: Int = 0, month: Int = 11): Instant = LocalDateTime(2026, month, d, h, m).toInstant(zone)

    private fun stage(a: Instant, min: Int, t: SleepStageType) = SleepStage(a, a + min.minutes, t)

    /** Сон фазами подряд с [start]: пары (минут, фаза). */
    private fun sleep(id: String, start: Instant, vararg parts: Pair<Int, SleepStageType>, source: String = band): HealthRecord {
        var t = start
        val stages = parts.map { (min, type) -> stage(t, min, type).also { t += min.minutes } }
        return HealthRecord(id, HealthType.SLEEP, source, start, t, off, stages = stages)
    }

    @Test
    fun nightGoesToWakeDateWithPhasesAndNap() {
        val night = sleep(
            "n1", at(14, 23, 30),
            60 to SleepStageType.LIGHT, 90 to SleepStageType.DEEP, 20 to SleepStageType.AWAKE,
            120 to SleepStageType.REM, 150 to SleepStageType.LIGHT,
        )
        // Дрёма днём — после перерыва больше SLEEP_GAP: в ночь не входит
        val nap = sleep("n2", at(15, 15), 40 to SleepStageType.LIGHT)
        val days = healthDays(listOf(night, nap), LocalDate(2026, 11, 14), LocalDate(2026, 11, 15), zone)
        assertNull(days[0].sleep)
        val s = days[1].sleep!!
        assertEquals(at(14, 23, 30), s.bedAt)
        assertEquals(60 + 90 + 120 + 150, s.asleepMin)
        assertEquals(90, s.deepMin)
        assertEquals(120, s.remMin)
        assertEquals(210, s.lightMin)
        assertEquals(20, s.awakeMin)
        assertEquals(40, s.napMin)
        assertEquals("15.11 вс сон 7:00 23:30–06:50 глуб 1:30 REM 2:00 лёгк 3:30 проб 0:20 +дрёма 0:40", days[1].toText())
    }

    @Test
    fun brokenNightJoinsAndDuplicateSourceIsNotDoubled() {
        // Mi Fitness режет ночь на два куска с перерывом 30 мин — это одна ночь
        val a = sleep("a", at(10, 20, 57), 180 to SleepStageType.LIGHT)
        val b = sleep("b", at(11, 0, 27), 300 to SleepStageType.DEEP)
        // Другое приложение записало ту же ночь короче — берём источник, у кого сна больше
        val other = sleep("c", at(10, 22), 300 to SleepStageType.SLEEPING, source = "com.sec.android.app.shealth")
        val s = healthDays(listOf(a, b, other), LocalDate(2026, 11, 11), LocalDate(2026, 11, 11), zone).single().sleep!!
        assertEquals(480, s.asleepMin)
        assertEquals(band, s.source)
        assertEquals(at(11, 5, 27), s.wakeAt)
    }

    @Test
    fun sleepWithoutStagesCountsWholeInterval() {
        val r = HealthRecord("s", HealthType.SLEEP, "x", at(1, 0), at(1, 7), off)
        val s = healthDays(listOf(r), LocalDate(2026, 11, 1), LocalDate(2026, 11, 1), zone).single().sleep!!
        assertEquals(420, s.asleepMin)
        assertNull(s.deepMin)
    }

    @Test
    fun nightHeartRateStepsSpo2AndExclusion() {
        val night = sleep("n", at(5, 0), 480 to SleepStageType.LIGHT)
        // Точки пульса раз в 10 минут ночью и одна днём — днём в пульс ночью не идёт
        val sec = (0 until 48).map { it * 600 }
        val bpm = sec.mapIndexed { i, _ -> 50 + i % 10 }
        val hr = HealthRecord("hr", HealthType.HEART_RATE, band, at(5, 0), at(5, 8), off, hrSec = sec, hrBpm = bpm)
        val dayHr = HealthRecord("hr2", HealthType.HEART_RATE, band, at(5, 15), at(5, 15, 1), off, hrSec = listOf(0), hrBpm = listOf(40))
        val steps = listOf(
            HealthRecord("st1", HealthType.STEPS, band, at(5, 9), at(5, 10), off, value = 3000.0),
            HealthRecord("st2", HealthType.STEPS, band, at(5, 18), at(5, 19), off, value = 2000.0),
            HealthRecord("ph", HealthType.STEPS, "android", at(5, 9), at(5, 19), off, value = 4000.0),
        )
        val spo2 = listOf(94.0, 96.0).mapIndexed { i, v -> HealthRecord("o$i", HealthType.SPO2, band, at(5, 2 + i), value = v) }
        val rhr = HealthRecord("r", HealthType.RESTING_HEART_RATE, band, at(5, 8), value = 55.0)
        val other = HealthRecord("x", HealthType.STEPS, band, at(6, 9), at(6, 10), off, value = 200.0)
        val ex = HealthExclusion(newId(), LocalDate(2026, 11, 6), LocalDate(2026, 11, 6), band, "часы на другом")
        val days = healthDays(listOf(night, hr, dayHr, rhr, other) + steps + spo2, LocalDate(2026, 11, 5), LocalDate(2026, 11, 6), zone, listOf(ex))
        val d = days[0]
        assertEquals(50, d.nightHrMin)
        assertEquals(54, d.nightHrMedian)
        assertEquals(5000, d.steps)
        assertEquals(95.0, d.spo2)
        assertEquals(94.0, d.spo2Min)
        assertEquals(55, d.restingHr)
        assertTrue(days[1].excluded)
        assertNull(days[1].steps)
        assertEquals("06.11 пт исключён", days[1].toText())
    }

    @Test
    fun recordDateUsesItsOwnOffset() {
        // 23:30 по UTC+4 — тот же день, хотя сервер в UTC
        val r = HealthRecord("s", HealthType.STEPS, band, at(5, 23, 30), value = 1.0, zoneOffset = off)
        assertEquals(LocalDate(2026, 11, 5), r.date(TimeZone.UTC))
        assertEquals(LocalDate(2026, 11, 5), r.copy(zoneOffset = null).date(zone))
    }

    @Test
    fun summaryAndReportText() {
        val nights = listOf(
            sleep("1", at(1, 23), 480 to SleepStageType.LIGHT),
            sleep("2", at(3, 1), 300 to SleepStageType.LIGHT),
            sleep("3", at(3, 20), 420 to SleepStageType.DEEP),
        )
        val days = healthDays(nights, LocalDate(2026, 11, 1), LocalDate(2026, 11, 4), zone)
        val sm = healthSummary(days)
        assertEquals(3, sm.nights)
        assertEquals(400, sm.sleepAvgMin)
        assertEquals(1, sm.shortNights)
        assertEquals("20:00", sm.bedEarliest)
        assertEquals("01:00", sm.bedLatest)
        val text = HealthReport(LocalDate(2026, 11, 1), LocalDate(2026, 11, 4), days, sources = listOf(band)).toText(recent = 2)
        assertEquals(
            """
            01.11–04.11 · источники: $band
            последние 2 дн.: сон ср 6:00 (мин 5:00), короче 6 ч: 1 из 2 · отбой 20:00–01:00, разброс ±2:30 · глуб ср 3:30
            01.11–02.11: сон ср 8:00 (мин 8:00), короче 6 ч: 0 из 1 · отбой 23:00–23:00 · глуб ср 0:00
            04.11 ср сон 7:00 20:00–03:00 глуб 7:00 REM 0:00 лёгк 0:00
            03.11 вт сон 5:00 01:00–06:00 глуб 0:00 REM 0:00 лёгк 5:00
            02.11 пн сон 8:00 23:00–07:00 глуб 0:00 REM 0:00 лёгк 8:00
            01.11 вс нет данных
            """.trimIndent(),
            text,
        )
    }
}

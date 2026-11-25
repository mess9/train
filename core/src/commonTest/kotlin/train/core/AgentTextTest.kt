package train.core

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class AgentTextTest {
    private fun done(weight: Double?, reps: Int, rating: Rating? = Rating.NORMAL, note: String? = null) =
        DoneSet(1, RecordType.WEIGHT_REPS, weight = weight, reps = reps, rating = rating, note = note)

    @Test
    fun numbersWithoutTrailingZeros() {
        assertEquals("12", num(12.0))
        assertEquals("12.5", num(12.5))
        assertEquals("59.85", num(59.849999))
    }

    @Test
    fun setsGroupedByLoad() {
        val sets = listOf(done(12.0, 10, Rating.HARD), done(12.0, 10), done(14.0, 8, Rating.EASY, "хват\nшире"), done(14.0, 7, null))
        assertEquals("12кг: 10✕ 10✓ · 14кг: 8↑ «хват / шире» 7", renderSets(sets))
        val time = DoneSet(1, RecordType.TIME, seconds = 55, rating = Rating.NORMAL)
        assertEquals("55с✓ 55с✓", renderSets(listOf(time, time)))
        val extra = DoneSet(1, RecordType.REPS, reps = 6, extraWeight = 5.0)
        assertEquals("+5кг: 6 · 8✓", renderSets(listOf(extra, DoneSet(2, RecordType.REPS, reps = 8, rating = Rating.NORMAL))))
    }

    @Test
    fun targetsWithMultiplier() {
        val t = TargetSet(weight = 12.0, repsMin = 8, repsMax = 10, rir = 3)
        assertEquals("3× 12кг 8–10 r3, 30с", renderTargets(listOf(t, t.copy(), t.copy(), TargetSet(seconds = 30))))
        assertEquals("свободно", renderTargets(listOf(TargetSet())))
    }

    @Test
    fun comparisonText() {
        val press = exercise("Жим гантелей", implements = 2)
        val oldRow = exercise("Тяга штанги")
        val row = exercise("Тяга гантели")
        val warm = exercise("Скакалка", type = RecordType.TIME)
        val exercises = listOf(press, oldRow, row, warm).associateBy { it.id }
        val target = TargetSet(weight = 10.0, repsMin = 8, repsMax = 10, rir = 2)
        val w0 = item(warm, TargetSet(seconds = 60))
        val a = item(press, target, target.copy()).copy(liked = true, comment = "плечо ок", note = "локти 45°")
        val c = item(row, TargetSet(weight = 20.0, repsMax = 10), TargetSet(weight = 20.0, repsMax = 10)).copy(replacedFrom = oldRow.id, disliked = true)
        val w = workout(block(w0, role = BlockRole.WARMUP), block(a, c).copy(title = "Круг"))
            .copy(finishedAt = T0 + 40.minutes, comment = "бодро", date = LocalDate(2026, 11, 3), planVersion = 4)
        val facts = listOf(
            w.fact(w0, 1, seconds = 60, minute = 5),
            w.fact(a, 1, weight = 10.0, reps = 10, minute = 10),
            w.fact(c, 1, weight = 40.0, reps = 10, minute = 12, exerciseId = oldRow.id),
            w.fact(a, 2, weight = 10.0, reps = 9, minute = 20),
            w.fact(a, 3, weight = 10.0, reps = 6, minute = 22),
        )
        val prev = workout(block(a)).let { p -> listOf(p.fact(a, 1, weight = 8.0, reps = 10)) }
        val text = compare(w, facts, exercises, prev, bodyweight = 60.0).toText({ exercises[it]?.nameRu }, planNotes = true)
        assertEquals(
            """
            вт 03.11.2026 · 40 мин · вес 60 · план v4 · тренировка ${w.id}
            подходов 4 из 5 · пропущено 1 · сверх плана 1 · рабочих 4 · объём 900 кг · под нагрузкой 60с
            комментарий: «бодро»
            [разминка] · 1 прох. · 0–5 мин
              Скакалка: 60с✓ | цель 60с
            [основной] Круг · 3 прох. · 5–22 мин (5/проход)
              Жим гантелей ♥: 10кг: 10✓ 9✓ +6✓ | цель 2× 10кг 8–10 r2 | было 8кг: 10✓
                владелец: «плечо ок»
                план: «локти 45°»
              Тяга гантели 👎 (замена: вместо Тяга штанги): до замены 40кг: 10✓ · 20кг: — | цель 2× 20кг 10
            """.trimIndent(),
            text,
        )
    }

    /** Пульс каждые 10 с с [fromMin] по [toMin] минуту от старта тренировки; [bpm] — от секунды от старта. */
    private fun hr(id: String, source: String, fromMin: Int, toMin: Int, bpm: (Int) -> Int): HealthRecord {
        val secs = (0..(toMin - fromMin) * 60 step 10).toList()
        return HealthRecord(id, HealthType.HEART_RATE, source, T0 + fromMin.minutes, T0 + toMin.minutes,
            hrSec = secs, hrBpm = secs.map { bpm(fromMin * 60 + it) })
    }

    @Test
    fun heartRateOfWorkoutAndBlocks() {
        val warm = exercise("Скакалка", type = RecordType.TIME)
        val press = exercise("Жим гантелей", implements = 2)
        val w0 = item(warm, TargetSet(seconds = 60))
        val a = item(press, TargetSet(weight = 10.0, repsMax = 10))
        val w = workout(block(w0, role = BlockRole.WARMUP), block(a)).copy(finishedAt = T0 + 40.minutes, date = LocalDate(2026, 11, 3))
        val facts = listOf(w.fact(w0, 1, seconds = 60, minute = 5), w.fact(a, 1, weight = 10.0, reps = 10, minute = 22))
        // Браслет запущен на 1-й минуте, остановлен на 30-й: разминка 100, основной 140 и пик 165 на 15-й
        val band = hr("b", "com.xiaomi.wearable", 1, 30) { s -> if (s <= 300) 100 else if (s == 900) 165 else 140 }
        // Другой источник с горсткой точек не смешивается, пульс до тренировки не входит
        val other = hr("o", "other.app", 10, 11) { 200 }
        val before = HealthRecord("p", HealthType.HEART_RATE, "com.xiaomi.wearable", T0 - 120.minutes, T0 - 60.minutes,
            hrSec = (0..600 step 10).toList(), hrBpm = List(61) { 60 })
        val c = compare(w, facts, listOf(warm, press).associateBy { it.id }, health = listOf(band, other, before))
        assertEquals(WorkoutHeartRate(134, 165, 175, 29, "com.xiaomi.wearable"), c.heartRate)
        val text = c.toText()
        assertTrue("пульс ср 134 · макс 165 · по браслету 29 мин" in text, text)
        assertTrue("[разминка] · 1 прох. · 0–5 мин · пульс 100/100" in text, text)
        assertTrue("[основной] · 1 прох. · 5–22 мин · пульс 140/165" in text, text)
        // Браслет не запускали — строк пульса нет
        assertNull(compare(w, facts, emptyMap(), health = listOf(before)).heartRate)
        assertTrue("пульс" !in compare(w, facts, emptyMap()).toText())
    }

    @Test
    fun journalText() {
        val ex = exercise("Жим")
        val id = newId()
        val old = newId()
        val next = newId()
        val j = JournalEntry(
            id, LocalDate(2026, 11, 11), "держать 15 кг\nдо 12 повторов", "11.11 два рабочих ✕ на 12-м", "три подхода ✓ подряд",
            exerciseIds = listOf(ex.id), revises = old, revisedBy = next, createdAt = T0,
        )
        assertEquals(
            """
            11.11.2026 [Жим] (пересмотрено) решение: «держать 15 кг / до 12 повторов»
              почему: «11.11 два рабочих ✕ на 12-м»
              пересмотр: «три подхода ✓ подряд»
              id $id · пересматривает $old · пересмотрено записью $next
            """.trimIndent(),
            listOf(j).toText { if (it == ex.id) "Жим" else null },
        )
    }
}

package train.core

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

class StatsTest {
    private val bench = exercise("Жим лёжа").copy(musclesPrimary = listOf("pectorals"), musclesSecondary = listOf("triceps", "shoulders"))
    private val squat = exercise("Присед").copy(musclesPrimary = listOf("quads"), musclesSecondary = listOf("glutes", "quadriceps"))
    private val run = exercise("Бег", type = RecordType.TIME)
        .copy(musclesPrimary = listOf("cardiovascular system"), musclesSecondary = listOf("quadriceps", "calves"))
    private val lunge = exercise("Выпад").copy(musclesPrimary = listOf("quads"), musclesSecondary = listOf("glutes", "hamstrings", "calves"))
    private val exercises = listOf(bench, squat, run, lunge).associateBy { it.id }

    private val day = LocalDate(2026, 11, 3)

    @Test
    fun musclesFromPrimaryAndSecondary() {
        val b = item(bench, TargetSet(), TargetSet())
        val s = item(squat, TargetSet())
        val w = workout(block(b, s)).copy(finishedAt = T0 + 50.minutes, date = day)
        val sets = listOf(w.fact(b, 1, 40.0, 10), w.fact(b, 2, 40.0, 8), w.fact(s, 1, 60.0, 5))

        val st = stats(day, day, listOf(w), sets, exercises)
        assertEquals(2.0, st.load(BodyRegion.CHEST))
        assertEquals(1.0, st.load(BodyRegion.TRICEPS))
        assertEquals(1.0, st.load(BodyRegion.SHOULDERS))
        // quads и quadriceps — один участок: подход не считается дважды
        assertEquals(1.0, st.load(BodyRegion.QUADS))
        assertEquals(0.5, st.load(BodyRegion.GLUTES))
        assertEquals(0.0, st.load(BodyRegion.CALVES))
        assertEquals(BodyRegion.entries.size, st.regions.size)
        assertEquals(1.0, st.load(BodyGroup.LEGS))
        assertEquals(0.5, st.load(BodyGroup.GLUTES))
        assertEquals(2.0, st.load(BodyGroup.CHEST))
        assertEquals(MuscleLoad("pectorals", 2, 0), st.muscles.first())
        assertEquals(3, st.sets)
        assertEquals(50, st.minutes)
        assertEquals(40.0 * 18 + 60.0 * 5, st.volumeKg)
        assertEquals(listOf(DayLoad(day, 1, 50, 3)), st.days)
    }

    /** Подход входит в луч розы один раз, сколько бы участков группы он ни задел. */
    @Test
    fun groupCountsSetOnce() {
        val l = item(lunge, TargetSet(), TargetSet())
        val w = workout(block(l)).copy(date = day)
        val st = stats(day, day, listOf(w), listOf(w.fact(l, 1, 12.0, 10), w.fact(l, 2, 12.0, 10)), exercises)
        assertEquals(2.0, st.load(BodyRegion.QUADS))
        assertEquals(1.0, st.load(BodyRegion.HAMSTRINGS))
        assertEquals(1.0, st.load(BodyRegion.CALVES))
        assertEquals(2.0, st.load(BodyGroup.LEGS))
        assertEquals(1.0, st.load(BodyGroup.GLUTES))
        assertEquals(BodyGroup.entries.size, st.groups.size)
    }

    @Test
    fun onlyDoneSetsOfWorkoutsInRange() {
        val b = item(bench, TargetSet(), TargetSet(), TargetSet())
        val inside = workout(block(b)).copy(finishedAt = T0 + 30.minutes, date = day)
        val before = workout(block(b)).copy(id = newId(), startedAt = T0 - 2.days, finishedAt = T0 - 2.days + 20.minutes, date = LocalDate(2026, 11, 1))
        val gone = workout(block(b)).copy(id = newId(), finishedAt = T0 + 30.minutes, date = day, deleted = true)
        val sets = listOf(
            inside.fact(b, 1, 40.0, 10),
            inside.fact(b, 2, 40.0, 10, done = false),
            inside.fact(b, 3, 40.0, 10, deleted = true),
            before.fact(b, 1, 40.0, 10),
            gone.fact(b, 1, 40.0, 10),
        )
        val st = stats(day, day, listOf(inside, before, gone), sets, exercises)
        assertEquals(1, st.workouts)
        assertEquals(1, st.sets)
        assertEquals(1.0, st.load(BodyRegion.CHEST))

        val wide = stats(LocalDate(2026, 11, 1), day, listOf(inside, before, gone), sets, exercises)
        assertEquals(2, wide.workouts)
        assertEquals(50, wide.minutes)
        assertEquals(listOf(LocalDate(2026, 11, 1), day), wide.days.map { it.date })
    }

    /** Дня с сервера нет — по смещению телефона: 02:30 по Еревану — ещё 3 ноября по UTC. */
    @Test
    fun dayByPhoneOffsetWithoutDate() {
        val b = item(bench, TargetSet())
        val w = workout(block(b)).copy(startedAt = kotlin.time.Instant.parse("2026-11-03T22:30:00Z"), utcOffset = UtcOffset(hours = 4))
        val st = stats(LocalDate(2026, 11, 4), LocalDate(2026, 11, 4), listOf(w), emptyList(), exercises, zone = TimeZone.UTC)
        assertEquals(1, st.workouts)
    }

    /** Незавершённая — до последней отметки; кардио не на карте, но в подходах. */
    @Test
    fun activeWorkoutAndCardio() {
        val r = item(run, TargetSet(seconds = 600))
        val w = workout(block(r)).copy(date = day)
        val st = stats(day, day, listOf(w), listOf(w.fact(r, 1, seconds = 600, minute = 12)), exercises)
        assertEquals(12, st.minutes)
        assertEquals(1, st.sets)
        // Вспомогательные ноги у кардио не красятся: основная не на карте
        assertEquals(0.0, st.regions.sumOf { it.sets })
        assertEquals(0.0, st.groups.sumOf { it.sets })
        assertEquals(MuscleLoad("cardiovascular system", 1, 0), st.muscles.first())
        assertNull(regionOf("cardiovascular system"))
        assertEquals(BodyRegion.UPPER_BACK, regionOf(" Lats "))
    }

    @Test
    fun bodyweightFromLinkedMeasurement() {
        val pushUp = exercise("Отжимания", type = RecordType.REPS, bodyweightFactor = 0.5).copy(musclesPrimary = listOf("pectorals"))
        val p = item(pushUp, TargetSet())
        val w = workout(block(p)).copy(date = day, finishedAt = T0 + 10.minutes)
        val m = Measurement(newId(), BODYWEIGHT, 80.0, "kg", T0 + 20.minutes, workoutId = w.id)
        val st = stats(day, day, listOf(w), listOf(w.fact(p, 1, reps = 20)), mapOf(pushUp.id to pushUp), listOf(m))
        assertEquals(0.5 * 80 * 20, st.volumeKg)
    }

    @Test
    fun bucketsOfPeriods() {
        val today = LocalDate(2026, 11, 5) // четверг
        val week = StatsPeriod.WEEK.buckets(today)
        assertEquals(7, week.size)
        assertEquals(Bucket(LocalDate(2026, 10, 30), LocalDate(2026, 10, 30)), week.first())
        assertEquals(Bucket(today, today), week.last())

        assertEquals(30, StatsPeriod.MONTH.buckets(today).size)
        assertEquals(LocalDate(2026, 10, 7), StatsPeriod.MONTH.buckets(today).first().from)

        val quarter = StatsPeriod.QUARTER.buckets(today)
        assertEquals(13, quarter.size)
        assertEquals(Bucket(LocalDate(2026, 11, 2), LocalDate(2026, 11, 8)), quarter.last())
        assertEquals(LocalDate(2026, 8, 10), quarter.first().from)

        val year = StatsPeriod.YEAR.buckets(today)
        assertEquals(12, year.size)
        assertEquals(Bucket(LocalDate(2025, 12, 1), LocalDate(2025, 12, 31)), year.first())
        assertEquals(Bucket(LocalDate(2026, 11, 1), LocalDate(2026, 11, 30)), year.last())
        assertEquals(Bucket(LocalDate(2026, 2, 1), LocalDate(2026, 2, 28)), year[2])
    }

    @Test
    fun minutesPerBucket() {
        val today = LocalDate(2026, 11, 5)
        val days = listOf(DayLoad(LocalDate(2026, 11, 2), 1, 60, 10), DayLoad(LocalDate(2026, 11, 4), 2, 45, 8), DayLoad(LocalDate(2026, 10, 1), 1, 30, 5))
        assertEquals(listOf(0, 0, 0, 60, 0, 45, 0), minutesBy(StatsPeriod.WEEK.buckets(today), days))
        assertEquals(105, minutesBy(StatsPeriod.QUARTER.buckets(today), days).last())
        assertEquals(listOf(30, 105), minutesBy(StatsPeriod.YEAR.buckets(today), days).takeLast(2))
    }
}

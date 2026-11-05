package train.server

import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.todayIn
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import train.core.ApiError
import train.core.BodyGroup
import train.core.BodyRegion
import train.core.Exercise
import train.core.ExerciseCreate
import train.core.Stats
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

/** `GET /api/stats`: мышцы и минуты за период — только свои сделанные подходы. */
@QuarkusTest
class StatsTest {
    @Inject
    lateinit var users: Users

    @Test
    fun musclesAndMinutesOfPeriod() {
        val api = users.newApi()
        val bench: Exercise = api.post(
            "/api/exercises",
            ExerciseCreate(nameRu = "Жим", musclesPrimary = listOf("pectorals"), musclesSecondary = listOf("triceps")),
        )
        val now = Clock.System.now()
        var w = api.startFrom(api.plan(bench, sets = 3), bench, startedAt = now - 50.minutes)
        api.putSet(api.fact(w, 1), baseRev = 0)
        api.putSet(api.fact(w, 2), baseRev = 0)
        api.putSet(api.fact(w, 3).copy(done = false), baseRev = 0)
        w = api.putWorkout(w.copy(finishedAt = w.startedAt + 45.minutes), baseRev = 1)

        // Старая тренировка — вне недели
        val old = api.startFrom(api.plan(bench), bench, startedAt = now - 20.days)
        api.putSet(api.fact(old, 1), baseRev = 0)

        // Чужие подходы не видны
        val other = users.newApi()
        val otherBench = other.exercise()
        val ow = other.startFrom(other.plan(otherBench), otherBench)
        other.putSet(other.fact(ow, 1), baseRev = 0)

        val today = w.date!!
        val st = api.get<Stats>("/api/stats?from=${today.minus(DatePeriod(days = 6))}&to=$today")
        assertEquals(1, st.workouts)
        assertEquals(2, st.sets)
        assertEquals(45, st.minutes)
        assertEquals(2.0, st.load(BodyRegion.CHEST))
        assertEquals(1.0, st.load(BodyRegion.TRICEPS))
        assertEquals(1.0, st.load(BodyGroup.ARMS))
        assertEquals(2 * 40.0 * 10, st.volumeKg)
        assertEquals(listOf(today), st.days.map { it.date })

        // Без дат — 30 дней: старая тоже
        assertEquals(2, api.get<Stats>("/api/stats").workouts)
        api.get<ApiError>("/api/stats?from=$today&to=${today.minus(DatePeriod(days = 1))}", status = 400)
        api.get<ApiError>("/api/stats?from=вчера", status = 400)
    }

    @Test
    fun emptyPeriod() {
        val api = users.newApi()
        val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
        val st = api.get<Stats>("/api/stats?from=$today&to=$today")
        assertEquals(0, st.workouts)
        assertEquals(BodyRegion.entries.size, st.regions.size)
        assertEquals(BodyGroup.entries.size, st.groups.size)
    }
}

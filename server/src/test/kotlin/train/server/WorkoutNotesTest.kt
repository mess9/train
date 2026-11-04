package train.server

import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import kotlinx.datetime.LocalDate
import kotlinx.datetime.UtcOffset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import train.core.ApiError
import train.core.BODYWEIGHT
import train.core.Comparison
import train.core.Measurement
import train.core.Profile
import train.core.SyncPut
import train.core.Workout
import train.core.WorkoutDetail
import train.core.newId
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Пометки владельца к тренировке: сердечко, заметка, рост, день по часам телефона, замеры тренировки. */
@QuarkusTest
class WorkoutNotesTest {
    @Inject
    lateinit var users: Users

    @Inject
    lateinit var db: Db

    private fun Workout.mark(liked: Boolean, comment: String?) =
        copy(layout = layout.copy(blocks = layout.blocks.map { b -> b.copy(items = b.items.map { it.copy(liked = liked, comment = comment) }) }))

    @Test
    fun likeAndCommentReachWorkoutAndCompare() {
        val api = users.newApi()
        val bench = api.exercise()
        var w = api.startFrom(api.plan(bench), bench)
        api.putSet(api.fact(w, 1), baseRev = 0)
        w = api.putWorkout(w.copy(finishedAt = w.startedAt + 40.minutes).mark(true, "локти уходили"), baseRev = 1)

        val item = api.get<WorkoutDetail>("/api/workouts/${w.id}").workout.layout.blocks.single().items.single()
        assertTrue(item.liked)
        assertEquals("локти уходили", item.comment)

        val c = api.get<Comparison>("/api/workouts/${w.id}/compare").blocks.single().items.single()
        assertTrue(c.liked)
        assertEquals("локти уходили", c.comment)

        // Снять сердечко и заметку
        w = api.putWorkout(w.mark(false, null), baseRev = 2)
        val off = api.get<Comparison>("/api/workouts/${w.id}/compare").blocks.single().items.single()
        assertFalse(off.liked)
        assertNull(off.comment)
    }

    @Test
    fun heightInProfileAndCompare() {
        val api = users.newApi()
        api.put<_, Profile>("/api/profile", Profile(heightCm = 182.0))
        assertEquals(182.0, api.get<Profile>("/api/profile").heightCm)
        api.put<_, ApiError>("/api/profile", Profile(heightCm = 1820.0), status = 400)
        api.put<_, ApiError>("/api/profile", Profile(heightCm = 18.2), status = 400)
        assertEquals(182.0, api.get<Profile>("/api/profile").heightCm)

        val bench = api.exercise()
        val w = api.startFrom(api.plan(bench), bench)
        assertEquals(182.0, api.get<Comparison>("/api/workouts/${w.id}/compare").heightCm)
    }

    /** Старт в 02:30 по Еревану — 22:30 UTC прошлого дня: день тренировки — по телефону. */
    @Test
    fun dateFollowsPhoneOffset() {
        val api = users.newApi()
        val bench = api.exercise()
        val plan = api.plan(bench)
        val started = Instant.parse("2026-11-04T22:30:00Z")
        val sent = Workout(
            id = newId(), planId = plan.id, planVersion = plan.version, startedAt = started,
            utcOffset = UtcOffset(hours = 4), date = LocalDate(2026, 1, 1), // дату считает сервер
            layout = api.startFrom(api.plan(bench), bench).layout,
        )
        val w = api.putWorkout(sent, baseRev = 0)
        assertEquals(UtcOffset(hours = 4), w.utcOffset)
        assertEquals(LocalDate(2026, 11, 5), w.date)
        assertEquals(LocalDate(2026, 11, 5), api.get<WorkoutDetail>("/api/workouts/${w.id}").workout.date)
        assertEquals(LocalDate(2026, 11, 5), api.get<Comparison>("/api/workouts/${w.id}/compare").date)

        // Фильтр по дню — по дню телефона, не по UTC и не по поясу сервера
        assertTrue(api.get<List<Workout>>("/api/workouts?from=2026-11-05&to=2026-11-05").any { it.id == w.id })
        assertTrue(api.get<List<Workout>>("/api/workouts?from=2026-11-04&to=2026-11-04").none { it.id == w.id })

        // Тот же момент с телефона в UTC-5 — ещё 4 ноября
        val west = api.putWorkout(sent.copy(id = newId(), utcOffset = UtcOffset(hours = -5)), baseRev = 0)
        assertEquals(LocalDate(2026, 11, 4), west.date)
        assertTrue(api.get<List<Workout>>("/api/workouts?from=2026-11-04&to=2026-11-04").any { it.id == west.id })
    }

    /** Старый клиент без смещения: день — в поясе сервера, как раньше. */
    @Test
    fun dateWithoutOffsetUsesServerZone() {
        val api = users.newApi()
        val bench = api.exercise()
        val w = api.startFrom(api.plan(bench), bench)
        assertNull(w.utcOffset)
        val day = db.one("select start_date from workout where id = ?", w.id) { it.string("start_date") }
        assertEquals(day, w.date.toString())
    }

    @Test
    fun measurementsOfWorkout() {
        val api = users.newApi()
        val bench = api.exercise()
        val now = Clock.System.now()
        val w = api.startFrom(api.plan(bench), bench, startedAt = now - 1.hours)
        api.putSet(api.fact(w, 1), baseRev = 0)

        val before = Measurement(newId(), BODYWEIGHT, 80.0, "kg", now - 2.days)
        val after = Measurement(newId(), BODYWEIGHT, 78.5, "kg", now, workoutId = w.id)
        val waist = Measurement(newId(), "waist", 84.0, "cm", now, workoutId = w.id)
        for (m in listOf(before, after, waist)) api.put<_, Measurement>("/api/measurements/${m.id}", SyncPut(m, 0, newId()))

        assertEquals(setOf(after.id, waist.id), api.get<WorkoutDetail>("/api/workouts/${w.id}").measurements.map { it.id }.toSet())
        assertEquals(setOf(after.id, waist.id), api.get<List<Measurement>>("/api/measurements?workout=${w.id}").map { it.id }.toSet())
        assertEquals(3, api.get<List<Measurement>>("/api/measurements").size)

        // Вес тела в объёме — взвешивание этой тренировки, хоть оно и после старта
        val c = api.get<Comparison>("/api/workouts/${w.id}/compare")
        assertEquals(78.5, c.bodyweightKg)
        assertEquals(setOf(after.id, waist.id), c.measurements.map { it.id }.toSet())

        // Удалённый замер из тренировки уходит, вес — снова последний до старта
        api.put<_, Measurement>("/api/measurements/${after.id}", SyncPut(after.copy(deleted = true), 1, newId()))
        assertEquals(listOf(waist.id), api.get<WorkoutDetail>("/api/workouts/${w.id}").measurements.map { it.id })
        assertEquals(80.0, api.get<Comparison>("/api/workouts/${w.id}/compare").bodyweightKg)
    }

    /** Ссылка на чужую тренировку не сохраняется: замер остаётся сам по себе. */
    @Test
    fun measurementCannotPointToForeignWorkout() {
        val a = users.newApi()
        val b = users.newApi()
        val bench = a.exercise()
        val w = a.startFrom(a.plan(bench), bench)
        val m = Measurement(newId(), BODYWEIGHT, 70.0, "kg", Clock.System.now(), workoutId = w.id)
        assertNull(b.put<_, Measurement>("/api/measurements/${m.id}", SyncPut(m, 0, newId())).workoutId)
        assertTrue(b.get<List<Measurement>>("/api/measurements?workout=${w.id}").isEmpty())
        assertTrue(a.get<WorkoutDetail>("/api/workouts/${w.id}").measurements.isEmpty())
    }
}

package train.server

import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import train.core.ApiError
import train.core.BODYWEIGHT
import train.core.Measurement
import train.core.Plan
import train.core.SetFact
import train.core.SyncPut
import train.core.Workout
import train.core.WorkoutVolume
import train.core.newId
import kotlin.time.Duration.Companion.minutes

/** `DELETE /api/workouts/{id}` — насовсем, мимо корзины; `GET /api/stats/volumes` — объём тренировок. */
@QuarkusTest
class WorkoutEraseTest {
    @Inject
    lateinit var users: Users

    @Inject
    lateinit var db: Db

    @Test
    fun eraseWipesWorkoutAndFreesPlan() {
        val api = users.newApi()
        val bench = api.exercise()
        val plan = api.plan(bench, sets = 2)
        var w = api.startFrom(plan, bench)
        val set = api.putSet(api.fact(w, 1), baseRev = 0)
        api.putSet(set.copy(reps = 8), baseRev = 1)
        w = api.putWorkout(w.copy(finishedAt = w.startedAt + 40.minutes), baseRev = 1)
        val weight = Measurement(id = newId(), type = BODYWEIGHT, value = 80.0, unit = "kg", measuredAt = w.startedAt + 41.minutes, workoutId = w.id)
        api.put<SyncPut<Measurement>, Measurement>("/api/measurements/${weight.id}", SyncPut(weight, 0, newId()))
        assertEquals(w.startedAt, api.get<Plan>("/api/plans/${plan.id}").startedAt)

        // Чужую не стереть
        assertEquals(404, users.newApi().spec().delete("/api/workouts/${w.id}").statusCode)

        assertEquals(204, api.spec().delete("/api/workouts/${w.id}").statusCode)
        assertEquals(404, api.status("/api/workouts/${w.id}"))
        assertEquals(emptyList<Workout>(), api.get<List<Workout>>("/api/workouts"))
        assertEquals(0, db.one("select count(*) as n from workout_set where workout_id = ?", w.id) { it.long("n") }!!.toInt())
        assertEquals(0, db.one("select count(*) as n from revision_log where entity_id = any(?)", UuidList(listOf(w.id, set.id))) { it.long("n") }!!.toInt())
        // Замер остался, без привязки; план снова не начат
        assertNull(api.get<List<Measurement>>("/api/measurements").single().workoutId)
        assertNull(api.get<Plan>("/api/plans/${plan.id}").startedAt)

        // Старая отправка с телефона не воскрешает ни тренировку, ни подход
        api.put<SyncPut<Workout>, ApiError>("/api/workouts/${w.id}", SyncPut(w.copy(comment = "поздно"), w.rev, newId()), status = 410)
        api.put<SyncPut<SetFact>, ApiError>("/api/workouts/${w.id}/sets/${set.id}", SyncPut(set.copy(reps = 6), 2, newId()), status = 404)
        assertEquals(404, api.spec().delete("/api/workouts/${w.id}").statusCode)
    }

    @Test
    fun volumesPerWorkout() {
        val api = users.newApi()
        val bench = api.exercise()
        val w = api.startFrom(api.plan(bench, sets = 2), bench)
        api.putSet(api.fact(w, 1, weight = 40.0, reps = 10), baseRev = 0)
        api.putSet(api.fact(w, 2, weight = 50.0, reps = 5), baseRev = 0)
        api.putSet(api.fact(w, 3).copy(done = false), baseRev = 0)
        val empty = api.startFrom(api.plan(bench), bench)

        val v = api.get<List<WorkoutVolume>>("/api/stats/volumes").associateBy { it.workoutId }
        // Снарядов у жима один: 40 × 10 + 50 × 5
        assertEquals(650.0, v.getValue(w.id).volumeKg)
        assertEquals(0.0, v.getValue(empty.id).volumeKg)
    }
}

package train.server

import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import train.core.ApiError
import train.core.DEVICE_APK
import train.core.STATUS_LOCKED
import train.core.SetFact
import train.core.SyncPut
import train.core.Workout
import train.core.WorkoutDetail
import train.core.newId
import kotlin.time.Duration.Companion.minutes

/** Замок одной тренировки: её ведёт одно устройство, другое может её забрать (`take`). */
@QuarkusTest
class WorkoutDeviceTest {
    @Inject
    lateinit var users: Users

    @Test
    fun takenWorkoutRejectsFormerDevice() {
        val user = users.newApi()
        val a = user.on("phone-a")
        val b = user.on("phone-b")
        val bench = a.exercise()
        val w = a.startFrom(a.plan(bench, sets = 3), bench)
        assertEquals("phone-a", w.device)
        assertEquals(DEVICE_APK, w.deviceKind)
        val first = a.putSet(a.fact(w, 1), baseRev = 0)

        // Пока не забрали — второе устройство писать не может
        b.locked(b.fact(w, 2), 0)

        val taken = b.post<Unit?, WorkoutDetail>("/api/workouts/${w.id}/take", null)
        assertEquals("phone-b", taken.workout.device)
        assertEquals(w.rev, taken.workout.rev)
        assertEquals(listOf(first.id), taken.sets.map { it.id })

        // Прежнее устройство: ни подход, ни правка подхода, ни финиш
        a.locked(a.fact(w, 2), 0)
        a.locked(first.copy(reps = 6), first.rev)
        a.put<SyncPut<Workout>, ApiError>("/api/workouts/${w.id}", SyncPut(w.copy(finishedAt = w.startedAt + 30.minutes), w.rev, newId()), status = STATUS_LOCKED)

        // Новое — пишет; тело с чужим устройством его не меняет
        b.putSet(b.fact(w, 2), baseRev = 0)
        val done = b.putWorkout(taken.workout.copy(finishedAt = w.startedAt + 40.minutes, device = "phone-a"))
        assertEquals("phone-b", done.device)

        // Агент (без заголовка) правит как прежде; завершённую не забрать
        user.putWorkout(done.copy(comment = "разобрано"))
        a.post<Unit?, ApiError>("/api/workouts/${w.id}/take", null, status = 409)
    }

    @Test
    fun workoutWithoutDeviceIsClaimedByFirstWriter() {
        val user = users.newApi()
        val bench = user.exercise()
        val w = user.startFrom(user.plan(bench), bench)
        assertEquals(null, w.device)
        // Старая тренировка без устройства: первое устройство с заголовком закрепляет её за собой
        val a = user.on("phone-a")
        assertEquals("phone-a", a.putWorkout(w.copy(comment = "с телефона")).device)
        user.on("phone-b").locked(user.fact(w, 1), 0)
        assertEquals(400, user.spec().post("/api/workouts/${w.id}/take").statusCode)
    }
}

/** Запись подхода отвергнута замком. */
private fun TestApi.locked(f: SetFact, baseRev: Long) =
    put<SyncPut<SetFact>, ApiError>("/api/workouts/${f.workoutId}/sets/${f.id}", SyncPut(f, baseRev, newId()), status = STATUS_LOCKED)

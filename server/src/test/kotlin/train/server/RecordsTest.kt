package train.server

import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import train.core.Comparison
import train.core.Exercise
import train.core.Records
import train.core.Workout
import train.core.newId
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

@QuarkusTest
class RecordsTest {
    @Inject
    lateinit var users: Users

    private fun completed(api: TestApi, ex: Exercise, weight: Double, at: Instant): Workout {
        val w = api.startFrom(api.plan(ex), ex, at)
        api.putSet(api.fact(w, 1, weight = weight, reps = 10))
        return api.putWorkout(w.copy(finishedAt = at + 20.minutes))
    }

    @Test
    fun allRecordsExcludeAndUserIsolation() {
        val api = users.newApi()
        val ex = api.exercise()
        val other = api.exercise(nameRu = "Другой жим")
        val now = Clock.System.now()
        val first = completed(api, ex, 10.0, now - 3.days)
        val second = completed(api, ex, 12.0, now - 2.days)
        val third = completed(api, other, 20.0, now - 1.days)
        val all = api.get<Records>("/api/records")
        assertEquals(setOf(ex.id, other.id), all.exercises.map { it.exerciseId }.toSet())
        assertEquals(second.id, all.exercises.single { it.exerciseId == ex.id }.workoutId)
        assertEquals(third.id, all.volume?.workoutId)
        val filtered = api.get<Records>("/api/records?ids=${ex.id}&exclude=${second.id}")
        assertEquals(first.id, filtered.exercises.single().workoutId)
        // Фильтр упражнений не сужает рекорд объёма.
        assertEquals(third.id, filtered.volume?.workoutId)
        val stranger = users.newApi().get<Records>("/api/records?ids=${ex.id}&exclude=${first.id}")
        assertTrue(stranger.exercises.isEmpty())
        assertNull(stranger.volume)
        assertEquals(400, api.status("/api/records?ids=ошибка"))
        assertEquals(400, api.status("/api/records?exclude=ошибка"))
        assertEquals(401, TestApi(null).status("/api/records"))
    }

    @Test
    fun comparisonIgnoresSelfAndFutureAndDeletedWorkouts() {
        val api = users.newApi()
        val ex = api.exercise()
        val now = Clock.System.now()
        val first = completed(api, ex, 10.0, now - 3.days)
        val second = completed(api, ex, 12.0, now - 2.days)
        val future = completed(api, ex, 20.0, now - 1.days)
        val comparison = api.get<Comparison>("/api/workouts/${second.id}/compare")
        val record = comparison.blocks.single().items.single().sets.first().record
        assertEquals(first.id, record?.workoutId)
        assertEquals(first.id, comparison.volumeRecord?.workoutId)
        val firstComparison = api.get<Comparison>("/api/workouts/${first.id}/compare")
        assertNull(firstComparison.blocks.single().items.single().sets.first().record)
        assertNull(firstComparison.volumeRecord)
        api.putWorkout(future.copy(deleted = true))
        assertEquals(second.id, api.get<Records>("/api/records").exercises.single().workoutId)
        val excluded = api.get<Records>("/api/records?exclude=${second.id}")
        assertEquals(first.id, excluded.exercises.single().workoutId)
        assertEquals(first.id, excluded.volume?.workoutId)
        assertTrue(api.get<Records>("/api/records?ids=${newId()}").exercises.isEmpty())
    }
}

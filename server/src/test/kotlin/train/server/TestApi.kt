package train.server

import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.Response
import io.restassured.specification.RequestSpecification
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlinx.serialization.serializer
import org.junit.jupiter.api.Assertions.assertEquals
import train.core.API_KEY_HEADER
import train.core.Block
import train.core.BlockItem
import train.core.Exercise
import train.core.ExerciseCreate
import train.core.Plan
import train.core.PlanBody
import train.core.PlanPut
import train.core.RecordType
import train.core.SetFact
import train.core.SyncPut
import train.core.TargetSet
import train.core.TrainJson
import train.core.Workout
import train.core.newId
import train.core.snapshot
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Клиент тестов: тело — через TrainJson, как у настоящих клиентов. */
class TestApi(private val key: String?) {
    fun spec(): RequestSpecification = given().contentType(ContentType.JSON).apply { key?.let { header(API_KEY_HEADER, it) } }

    inline fun <reified T> get(path: String, status: Int = 200): T = spec().get(path).decode(status, path)

    inline fun <reified B, reified T> put(path: String, body: B, status: Int = 200): T =
        spec().body(TrainJson.encodeToString(serializer<B>(), body)).put(path).decode(status, path)

    inline fun <reified B, reified T> post(path: String, body: B, status: Int = 200): T =
        spec().body(TrainJson.encodeToString(serializer<B>(), body)).post(path).decode(status, path)

    fun status(path: String): Int = spec().get(path).statusCode

    inline fun <reified T> Response.decode(status: Int, path: String): T {
        assertEquals(status, statusCode, "$path → ${body.asString()}")
        return TrainJson.decodeFromString(serializer<T>(), body.asString())
    }

    // Сценарий: справочник → план → тренировка → подходы

    fun exercise(sourceId: String = "0025", nameRu: String = "Жим лёжа"): Exercise =
        post("/api/exercises", ExerciseCreate(nameRu = nameRu, sourceId = sourceId))

    fun plan(exercise: Exercise, id: Uuid = newId(), sets: Int = 2, weight: Double = 40.0): Plan =
        put("/api/plans/$id", planPut(exercise, sets, weight))

    fun startFrom(plan: Plan, exercise: Exercise, startedAt: Instant = Clock.System.now()): Workout {
        val w = Workout(
            id = newId(),
            planId = plan.id,
            planVersion = plan.version,
            startedAt = startedAt,
            layout = plan.body.snapshot { if (it == exercise.id) exercise else null },
        )
        return putWorkout(w, baseRev = 0)
    }

    fun putWorkout(w: Workout, baseRev: Long = w.rev, opId: Uuid = newId(), status: Int = 200): Workout =
        put("/api/workouts/${w.id}", SyncPut(w, baseRev, opId), status)

    /** Сделанный подход `setNo` первого упражнения раскладки, по цели. */
    fun fact(w: Workout, setNo: Int, weight: Double = 40.0, reps: Int = 10, at: Instant = w.startedAt + setNo.minutes): SetFact {
        val block = w.layout.blocks.first()
        val item = block.items.first()
        return SetFact(
            id = newId(), workoutId = w.id, blockId = block.id, itemId = item.id, setNo = setNo,
            targetId = item.sets.getOrNull(setNo - 1)?.id, exerciseId = item.exerciseId, recordType = item.recordType,
            weight = weight, reps = reps, done = true, doneAt = at,
        )
    }

    fun putSet(s: SetFact, baseRev: Long = s.rev, opId: Uuid = newId(), status: Int = 200): SetFact =
        put("/api/workouts/${s.workoutId}/sets/${s.id}", SyncPut(s, baseRev, opId), status)

    companion object {
        fun planPut(exercise: Exercise, sets: Int = 2, weight: Double = 40.0) = PlanPut(
            date = Clock.System.todayIn(TimeZone.currentSystemDefault()),
            title = "Грудь",
            body = PlanBody(
                listOf(
                    Block(
                        items = listOf(
                            BlockItem(
                                exerciseId = exercise.id,
                                recordType = RecordType.WEIGHT_REPS,
                                sets = List(sets) { TargetSet(weight = weight, repsMin = 8, repsMax = 10) },
                                restSec = 90,
                            ),
                        ),
                    ),
                ),
            ),
        )
    }
}

/** Новый пользователь с ключом: тесты не видят данных друг друга. */
fun Users.newApi(): TestApi = TestApi(addKey(ensure("${newId()}@test"), "test").key)

/** Новый админ (ADR 0011): в тестах — правкой базы, как у владельца после V2. */
fun Users.newAdmin(db: Db): TestApi {
    val id = ensure("${newId()}@test")
    db.update("update app_user set admin = true where id = ?", id)
    return TestApi(addKey(id, "test").key)
}

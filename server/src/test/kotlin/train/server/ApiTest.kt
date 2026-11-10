package train.server

import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import train.core.ApiError
import train.core.BODYWEIGHT
import train.core.Comparison
import train.core.Exercise
import train.core.Me
import train.core.Measurement
import train.core.Plan
import train.core.PlanPut
import train.core.PlanStatus
import train.core.Profile
import train.core.Purged
import train.core.SetFact
import train.core.SourceExercise
import train.core.SyncPut
import train.core.Trash
import train.core.Workout
import train.core.WorkoutDetail
import train.core.OP_ID_HEADER
import train.core.TrainJson
import train.core.newId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Clock

@QuarkusTest
class ApiTest {
    @Inject
    lateinit var users: Users

    @Inject
    lateinit var db: Db

    /** Новый пользователь на каждый тест: тесты не видят данных друг друга. */
    private fun newUser(): TestApi {
        val id = users.ensure("${newId()}@test")
        return TestApi(users.addKey(id, "test").key)
    }

    @Test
    fun planWorkoutCompareCycle() {
        val api = newUser()
        val bench = api.exercise()
        assertEquals("barbell bench press", bench.nameEn)
        assertEquals(listOf("pectorals"), bench.musclesPrimary)
        assertEquals("0025-EIeI8Vf", bench.mediaKey)
        assertEquals(listOf("Лягте на скамью."), bench.instructions)

        val plan = api.plan(bench)
        assertEquals(1, plan.version)
        assertEquals(PlanStatus.PLANNED, plan.status)

        val now = Clock.System.now()
        val weighing = Measurement(newId(), BODYWEIGHT, 80.0, "kg", now - 1.days)
        api.put<_, ApiError>("/api/measurements/${newId()}", SyncPut(weighing, 0, newId()), status = 400)
        assertEquals(1, api.put<SyncPut<Measurement>, Measurement>("/api/measurements/${weighing.id}", SyncPut(weighing, 0, newId())).rev)

        var w = api.startFrom(plan, bench, startedAt = now - 1.hours)
        assertEquals(1, w.rev)
        val started = api.get<Plan>("/api/plans/${plan.id}")
        assertEquals(PlanStatus.STARTED, started.status)
        assertEquals(1, started.startedVersion)

        api.putSet(api.fact(w, 1), baseRev = 0)
        api.putSet(api.fact(w, 2, reps = 8), baseRev = 0)
        w = api.putWorkout(w.copy(finishedAt = now, comment = "тяжело"), baseRev = 1)
        assertEquals(2, w.rev)

        val detail = api.get<WorkoutDetail>("/api/workouts/${w.id}")
        assertEquals(2, detail.sets.size)
        assertEquals("тяжело", detail.workout.comment)

        val c = api.get<Comparison>("/api/workouts/${w.id}/compare")
        assertEquals(2, c.totals.setsDone)
        assertEquals(40.0 * 10 + 40.0 * 8, c.totals.volumeKg)
        assertEquals(80.0, c.bodyweightKg)
        assertTrue(c.previous.isEmpty())

        // Вторая тренировка видит первую как прошлый результат
        val next = api.startFrom(api.plan(bench), bench)
        val c2 = api.get<Comparison>("/api/workouts/${next.id}/compare")
        assertEquals(w.id, c2.previous.single().workoutId)
        assertEquals(listOf(10, 8), c2.previous.single().sets.map { it.reps })

        val last = api.get<List<SetFact>>("/api/exercises/last-sets?ids=${bench.id}&exclude=${next.id}")
        assertEquals(listOf(1, 2), last.map { it.setNo })

        val recent = api.get<List<Exercise>>("/api/exercises")
        assertEquals(bench.id, recent.first().id)
    }

    @Test
    fun sameOpIdChangesNothing() {
        val api = newUser()
        val bench = api.exercise()
        val w = api.startFrom(api.plan(bench), bench)
        val fact = api.fact(w, 1)
        val op = newId()

        val first = api.putSet(fact, baseRev = 0, opId = op)
        val again = api.putSet(fact.copy(reps = 99), baseRev = 0, opId = op)
        assertEquals(1, first.rev)
        assertEquals(first, again)
        assertEquals(10, api.get<WorkoutDetail>("/api/workouts/${w.id}").sets.single().reps)
        assertEquals(0, revisions(fact.id))
    }

    @Test
    fun staleBaseRevConflicts() {
        val api = newUser()
        val bench = api.exercise()
        val w = api.startFrom(api.plan(bench), bench)
        val fact = api.putSet(api.fact(w, 1), baseRev = 0)
        val edited = api.putSet(fact.copy(reps = 12), baseRev = 1)
        assertEquals(2, edited.rev)
        assertEquals(1, revisions(fact.id))

        // Вторая вкладка не видела правку
        val current = api.putSet(fact.copy(reps = 5), baseRev = 1, status = 409)
        assertEquals(12, current.reps)
        assertEquals(2, current.rev)
    }

    @Test
    fun oldSendDoesNotResurrectDeleted() {
        val api = newUser()
        val bench = api.exercise()
        var w = api.startFrom(api.plan(bench), bench)
        w = api.putWorkout(w.copy(deleted = true), baseRev = 1)

        val current = api.putWorkout(w.copy(deleted = false, comment = "старое"), baseRev = 1, status = 409)
        assertTrue(current.deleted)
        assertTrue(api.get<List<Workout>>("/api/workouts").none { it.id == w.id })
        assertEquals(w.id, api.get<Trash>("/api/trash").workouts.single().id)
    }

    @Test
    fun planWithUnknownFieldIsRejected() {
        val api = newUser()
        val bench = api.exercise()
        val json = """{"date":"2026-11-04","body":{"blocks":[{"items":[
            {"exerciseId":"${bench.id}","sets":[{"weight":10,"reps":20}]}]}]}}"""
        val r = api.spec().body(json).put("/api/plans/${newId()}")
        assertEquals(400, r.statusCode, r.body.asString())
        val error = TrainJson.decodeFromString(ApiError.serializer(), r.body.asString()).error
        assertTrue("'reps'" in error && "sets[0]" in error, error)
        assertTrue(api.get<List<Plan>>("/api/plans").isEmpty())
    }

    @Test
    fun startFromOldVersionIsAccepted() {
        val api = newUser()
        val bench = api.exercise()
        val v1 = api.plan(bench, sets = 2)
        // Агент поправил план, пока телефон был без сети
        val v2 = api.put<_, Plan>("/api/plans/${v1.id}", TestApi.planPut(bench, sets = 4, weight = 50.0))
        assertEquals(2, v2.version)

        val w = api.startFrom(v1, bench)
        assertEquals(1, w.planVersion)
        val plan = api.get<Plan>("/api/plans/${v1.id}")
        assertEquals(PlanStatus.STARTED, plan.status)
        assertEquals(2, plan.version)
        assertEquals(1, plan.startedVersion)

        // Начатый план не меняется
        val conflict = api.put<_, Plan>("/api/plans/${v1.id}", TestApi.planPut(bench, sets = 5), status = 409)
        assertEquals(2, conflict.version)

        api.putSet(api.fact(w, 1), baseRev = 0)
        val c = api.get<Comparison>("/api/workouts/${w.id}/compare")
        assertEquals(1, c.planVersion)
        assertEquals(2, c.totals.setsPlanned)
        assertEquals(40.0, c.blocks.single().items.single().sets.first().target?.weight)
    }

    @Test
    fun phoneReorderDoesNotOverwriteAgentEdit() {
        val api = newUser()
        val bench = api.exercise()
        val v1 = api.plan(bench)
        val reorder = PlanPut(v1.date, v1.title, v1.body, baseVersion = 1)
        assertEquals(2, api.put<_, Plan>("/api/plans/${v1.id}", reorder).version)

        // Телефон держал версию 1, а план уже версии 2 — его правка не проходит
        val stale = api.put<_, Plan>("/api/plans/${v1.id}", reorder, status = 409)
        assertEquals(2, stale.version)
    }

    @Test
    fun foreignKeySeesNothing() {
        val a = newUser()
        val b = newUser()
        val bench = a.exercise()
        val plan = a.plan(bench)
        val w = a.startFrom(plan, bench)
        a.putSet(a.fact(w, 1), baseRev = 0)
        val m = Measurement(newId(), BODYWEIGHT, 80.0, "kg", Clock.System.now())
        a.put<_, Measurement>("/api/measurements/${m.id}", SyncPut(m, 0, newId()))

        assertEquals(404, b.status("/api/plans/${plan.id}"))
        assertEquals(404, b.status("/api/workouts/${w.id}"))
        assertEquals(404, b.status("/api/workouts/${w.id}/compare"))
        assertEquals(404, b.status("/api/measurements/${m.id}"))
        assertEquals(404, b.status("/api/exercises/${bench.id}"))
        assertTrue(b.get<List<Plan>>("/api/plans").isEmpty())
        assertTrue(b.get<List<Workout>>("/api/workouts").isEmpty())
        assertTrue(b.get<List<Measurement>>("/api/measurements").isEmpty())
        assertTrue(b.get<List<Exercise>>("/api/exercises").none { it.id == bench.id })
        assertTrue(b.get<List<SetFact>>("/api/exercises/last-sets?ids=${bench.id}").isEmpty())

        // Чужой ID не перезаписать и не занять
        b.put<_, ApiError>("/api/plans/${plan.id}", TestApi.planPut(bench), status = 400) // чужое упражнение
        b.put<_, ApiError>("/api/workouts/${w.id}", SyncPut(w.copy(comment = "чужое"), 1, newId()), status = 404)
        b.put<_, ApiError>("/api/measurements/${m.id}", SyncPut(m, 1, newId()), status = 404)
        assertNull(a.get<WorkoutDetail>("/api/workouts/${w.id}").workout.comment)
    }

    @Test
    fun keyIsRequired() {
        assertEquals(401, TestApi(null).status("/api/plans"))
        assertEquals(401, TestApi("wrong").status("/api/plans"))
        assertEquals(401, TestApi(null).status("/media/0025-EIeI8Vf.jpg"))
        assertEquals(200, TestApi(null).status("/api/info"))

        val api = newUser()
        val me = api.get<Me>("/api/me")
        val phone = api.post<_, train.core.KeyCreated>("/api/keys", train.core.KeyCreate("телефон"))
        assertEquals(me.userId, TestApi(phone.key).get<Me>("/api/me").userId)
    }

    @Test
    fun revokedKeyIsRejected() {
        val id = users.ensure("${newId()}@test")
        val laptop = TestApi(users.addKey(id, "ноутбук").key)
        val phoneKey = laptop.post<_, train.core.KeyCreated>("/api/keys", train.core.KeyCreate("телефон"))
        val phone = TestApi(phoneKey.key)

        val keys = laptop.get<List<train.core.ApiKey>>("/api/keys")
        assertEquals(listOf("ноутбук", "телефон"), keys.map { it.label })
        assertEquals(listOf(true, false), keys.map { it.current })
        assertTrue(keys.none { it.revokedAt != null })

        // Телефон потерян
        laptop.spec().delete("/api/keys/${phoneKey.id}").then().statusCode(204)
        assertEquals(401, phone.status("/api/me"))
        assertEquals(401, phone.status("/api/plans"))
        assertEquals(200, laptop.status("/api/me"))
        assertTrue(laptop.get<List<train.core.ApiKey>>("/api/keys").single { it.id == phoneKey.id }.revokedAt != null)

        // Чужой ключ не отозвать
        val other = newUser()
        val otherKey = other.get<List<train.core.ApiKey>>("/api/keys").single()
        assertEquals(404, laptop.spec().delete("/api/keys/${otherKey.id}").statusCode)
        assertEquals(200, other.status("/api/me"))
    }

    @Test
    fun sourceAndMedia() {
        val api = newUser()
        assertEquals(listOf("0025"), api.get<List<SourceExercise>>("/api/source/exercises?q=bench chest").map { it.id })
        assertEquals(listOf("0001"), api.get<List<SourceExercise>>("/api/source/exercises?q=lower back").map { it.id })
        assertEquals(2, api.get<List<SourceExercise>>("/api/source/exercises").size)
        api.post<_, ApiError>("/api/exercises", train.core.ExerciseCreate(nameRu = "x", sourceId = "9999"), status = 400)

        api.spec().get("/media/0025-EIeI8Vf.jpg").then().statusCode(200)
            .header("Content-Type", "image/jpeg")
            .header("Cache-Control", org.hamcrest.CoreMatchers.containsString("immutable"))
        assertEquals(404, api.status("/media/0001-2gPfomN.jpg"))
        assertEquals(404, api.status("/media/..%2Fexercises.json"))
    }

    @Test
    fun profileAndTrash() {
        val api = newUser()
        assertEquals(Profile(), api.get<Profile>("/api/profile"))
        val p = Profile(name = "Я", equipment = "гантели до 24 кг", vibration = true)
        api.put<_, Profile>("/api/profile", p)
        assertEquals(p, api.get<Profile>("/api/profile"))

        val bench = api.exercise()
        val plan = api.plan(bench)
        api.spec().delete("/api/plans/${plan.id}").then().statusCode(204)
        assertTrue(api.get<List<Plan>>("/api/plans").isEmpty())
        assertEquals(plan.id, api.get<Trash>("/api/trash").plans.single().id)

        // Запись в корзину возвращает план
        val restored = api.put<_, Plan>("/api/plans/${plan.id}", TestApi.planPut(bench))
        assertNull(restored.deletedAt)
        api.spec().delete("/api/plans/${plan.id}").then().statusCode(204)

        var w = api.startFrom(api.plan(bench), bench)
        api.putSet(api.fact(w, 1), baseRev = 0)
        w = api.putWorkout(w.copy(deleted = true), baseRev = 1)

        assertEquals(Purged(plans = 1, workouts = 1, measurements = 0), api.post<_, Purged>("/api/trash/purge", Unit))
        assertEquals(Trash(), api.get<Trash>("/api/trash"))
        assertEquals(404, api.status("/api/workouts/${w.id}"))
    }

    @Test
    fun invalidPlanIsRejected() {
        val api = newUser()
        val bench = api.exercise()
        val put = TestApi.planPut(bench)
        val item = put.body.blocks.single().items.single()
        val dup = put.copy(body = put.body.copy(blocks = put.body.blocks.map { it.copy(items = listOf(item, item)) }))
        api.put<_, ApiError>("/api/plans/${newId()}", dup, status = 400)
        val unknown = put.copy(body = put.body.copy(blocks = put.body.blocks.map { b -> b.copy(items = b.items.map { it.copy(exerciseId = newId()) }) }))
        api.put<_, ApiError>("/api/plans/${newId()}", unknown, status = 400)
        assertEquals(400, api.spec().body("{\"date\": \"завтра\"}").put("/api/plans/${newId()}").statusCode)
    }

    /** `409` называет `opId` версии сервера: телефон узнаёт свою отправку, ответ на которую потерялся. */
    @Test
    fun conflictNamesServerOp() {
        val api = newUser()
        val m = Measurement(newId(), BODYWEIGHT, 80.0, "kg", Clock.System.now())
        val op = newId()
        api.put<_, Measurement>("/api/measurements/${m.id}", SyncPut(m, 0, op))
        val r = api.spec().body(TrainJson.encodeToString(SyncPut.serializer(Measurement.serializer()), SyncPut(m.copy(value = 81.0), 0, newId())))
            .put("/api/measurements/${m.id}")
        assertEquals(409, r.statusCode)
        assertEquals(op.toString(), r.header(OP_ID_HEADER))
    }

    /** Первые записи одного ID разом: одна создаёт, остальные — `409` или `404`, ничего не перезаписано. */
    @Test
    fun concurrentFirstPutsDoNotOverwrite() {
        val a = newUser()
        val b = newUser()
        repeat(5) {
            val m = Measurement(newId(), BODYWEIGHT, 80.0, "kg", Clock.System.now())
            val start = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(8)
            val results = (0 until 8).map { i ->
                val api = if (i % 2 == 0) a else b
                val body = SyncPut(m.copy(value = 80.0 + i), 0, newId())
                pool.submit<Pair<Int, Double>> {
                    start.await()
                    val r = api.spec().body(TrainJson.encodeToString(SyncPut.serializer(Measurement.serializer()), body))
                        .put("/api/measurements/${m.id}")
                    r.statusCode to body.data.value
                }
            }
            start.countDown()
            val codes = results.map { it.get(30, TimeUnit.SECONDS) }
            pool.shutdown()

            val won = codes.filter { it.first == 200 }
            assertEquals(1, won.size, "$codes")
            assertTrue(codes.all { it.first in setOf(200, 404, 409) }, "$codes")
            val owner = if (codes.indexOf(won.single()) % 2 == 0) a else b
            val stored = owner.get<Measurement>("/api/measurements/${m.id}")
            assertEquals(1, stored.rev)
            assertEquals(won.single().second, stored.value)
            assertEquals(0, revisions(m.id))
        }
    }

    private fun revisions(id: kotlin.uuid.Uuid) =
        db.one("select count(*) as n from revision_log where entity_id = ?", id) { it.int("n") }
}

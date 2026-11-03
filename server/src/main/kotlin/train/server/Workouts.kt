package train.server

import jakarta.transaction.Transactional
import jakarta.ws.rs.GET
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.QueryParam
import org.jboss.resteasy.reactive.RestResponse
import train.core.API_PREFIX
import train.core.Comparison
import train.core.SetFact
import train.core.SyncPut
import train.core.Workout
import train.core.WorkoutDetail
import train.core.WorkoutLayout
import train.core.compare

@Path("$API_PREFIX/workouts")
class WorkoutResource(
    private val db: Db,
    private val user: CurrentUser,
    private val store: Store,
    private val days: Days,
) {
    @GET
    fun list(@QueryParam("from") from: String?, @QueryParam("to") to: String?): List<Workout> {
        val start = parseDate(from)?.let(days::start)
        val end = parseDate(to)?.let(days::end)
        return db.list(
            "select * from workout where user_id = ? and not deleted " +
                "and (cast(? as timestamptz) is null or started_at >= ?) and (cast(? as timestamptz) is null or started_at < ?) " +
                "order by started_at desc",
            user.id, start, start, end, end,
        ) { it.workout() }
    }

    /** Подходы — включая удалённые: на телефоне они прячут строки раскладки. */
    @GET
    @Path("{id}")
    fun get(@PathParam("id") id: String): WorkoutDetail {
        val w = store.workout(user.id, parseId(id)) ?: throw notFound()
        return WorkoutDetail(w, store.sets(w.id))
    }

    /**
     * Старт, раскладка, финиш, комментарий — всё одной записью (ADR 0007). Первая запись с
     * планом фиксирует его старт и версию; если версия старая, план это покажет
     * (`startedVersion < version`). Вторую активную тренировку сервер не отвергает.
     */
    @PUT
    @Path("{id}")
    @Transactional
    fun put(@PathParam("id") id: String, body: SyncPut<Workout>): RestResponse<Workout> {
        val workoutId = parseId(id)
        if (body.data.id != workoutId) throw badRequest("id в пути и в теле не совпадают")
        val row = store.workoutRow(workoutId, forUpdate = true)
        if (row != null && row.userId != user.id) throw notFound()
        // План могли окончательно удалить, пока телефон был без сети, — тренировку всё равно принять
        val planId = body.data.planId?.takeIf { store.plan(user.id, it, forUpdate = true) != null }
        val put = body.copy(data = body.data.copy(planId = planId))

        return sync("workout", Workout.serializer(), put, row?.stored, db, { store.workoutRow(workoutId)!!.stored.record }) { w, rev ->
            db.update(
                """
                insert into workout (id, user_id, plan_id, plan_version, started_at, finished_at, comment, layout, received_at, rev, op_id, deleted)
                values (?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), now(), ?, ?, ?)
                on conflict (id) do update set plan_id = excluded.plan_id, plan_version = excluded.plan_version,
                  started_at = excluded.started_at, finished_at = excluded.finished_at, comment = excluded.comment,
                  layout = excluded.layout, received_at = now(), rev = excluded.rev, op_id = excluded.op_id, deleted = excluded.deleted
                """.trimIndent(),
                w.id, user.id, w.planId, w.planVersion, w.startedAt, w.finishedAt, w.comment,
                Json.of(WorkoutLayout.serializer(), w.layout), rev, put.opId, w.deleted,
            )
            if (w.planId != null && !w.deleted) {
                db.update(
                    "update plan set started_at = ?, started_version = ? where id = ? and started_at is null",
                    w.startedAt, w.planVersion, w.planId,
                )
            }
        }
    }

    @PUT
    @Path("{id}/sets/{setId}")
    @Transactional
    fun putSet(@PathParam("id") id: String, @PathParam("setId") setId: String, body: SyncPut<SetFact>): RestResponse<SetFact> {
        val workoutId = parseId(id)
        val s = body.data
        if (s.id != parseId(setId) || s.workoutId != workoutId) throw badRequest("id в пути и в теле не совпадают")
        // Телефон отправляет тренировку раньше её подходов
        store.workout(user.id, workoutId) ?: throw notFound("нет тренировки $workoutId")
        if (store.exercises(user.id, listOf(s.exerciseId)).isEmpty()) throw badRequest("нет в справочнике: ${s.exerciseId}")

        val read = { lock: Boolean ->
            db.one("select * from workout_set where id = ?${if (lock) " for update" else ""}", s.id) {
                Owned(it.uuidReq("user_id"), Stored(it.setFact(), it.uuidReq("op_id")))
            }
        }
        val row = read(true)
        if (row != null && (row.userId != user.id || row.stored.record.workoutId != workoutId)) throw notFound()

        return sync("set", SetFact.serializer(), body, row?.stored, db, { read(false)!!.stored.record }) { f, rev ->
            db.update(
                """
                insert into workout_set (id, workout_id, user_id, block_id, item_id, set_no, target_id, exercise_id, record_type, per_side,
                  weight, reps, extra_weight, seconds, rating, note, done, done_at, received_at, rev, op_id, deleted)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), ?, ?, ?)
                on conflict (id) do update set block_id = excluded.block_id, item_id = excluded.item_id, set_no = excluded.set_no,
                  target_id = excluded.target_id, exercise_id = excluded.exercise_id, record_type = excluded.record_type,
                  per_side = excluded.per_side, weight = excluded.weight, reps = excluded.reps, extra_weight = excluded.extra_weight,
                  seconds = excluded.seconds, rating = excluded.rating, note = excluded.note, done = excluded.done,
                  done_at = excluded.done_at, received_at = now(), rev = excluded.rev, op_id = excluded.op_id, deleted = excluded.deleted
                """.trimIndent(),
                f.id, f.workoutId, user.id, f.blockId, f.itemId, f.setNo, f.targetId, f.exerciseId, f.recordType, f.perSide,
                f.weight, f.reps, f.extraWeight, f.seconds, f.rating, f.note, f.done, f.doneAt, rev, body.opId, f.deleted,
            )
        }
    }

    /** План против факта по снимку в раскладке; прошлое и вес тела — на момент старта. */
    @GET
    @Path("{id}/compare")
    fun compare(@PathParam("id") id: String): Comparison {
        val w = store.workout(user.id, parseId(id)) ?: throw notFound()
        val facts = store.sets(w.id)
        val exerciseIds = w.layout.blocks.flatMap { b -> b.items.flatMap { listOfNotNull(it.exerciseId, it.replacedFrom) } } +
            facts.map { it.exerciseId }
        return compare(
            workout = w,
            facts = facts,
            exercises = store.exercises(user.id, exerciseIds.toSet()),
            previous = store.lastSets(user.id, exerciseIds.toSet(), before = w.startedAt, exclude = w.id),
            bodyweight = store.bodyweight(user.id, w.startedAt),
        )
    }
}

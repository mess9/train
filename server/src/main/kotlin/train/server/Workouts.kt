package train.server

import jakarta.transaction.Transactional
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.jboss.resteasy.reactive.RestResponse
import train.core.API_PREFIX
import train.core.ApiError
import train.core.Comparison
import train.core.DEVICE_HEADER
import train.core.DEVICE_KIND_HEADER
import train.core.STATUS_LOCKED
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
    /** `from`, `to` — по дню тренировки: дате старта по часам телефона. */
    @GET
    fun list(@QueryParam("from") from: String?, @QueryParam("to") to: String?): List<Workout> {
        val start = parseDate(from)
        val end = parseDate(to)
        return db.list(
            "select * from workout where user_id = ? and not deleted " +
                "and (cast(? as date) is null or start_date >= ?) and (cast(? as date) is null or start_date <= ?) " +
                "order by started_at desc",
            user.id, start, start, end, end,
        ) { it.workout() }
    }

    /** Подходы — включая удалённые: на телефоне они прячут строки раскладки. И замеры тренировки. */
    @GET
    @Path("{id}")
    fun get(@PathParam("id") id: String): WorkoutDetail {
        val w = store.workout(user.id, parseId(id)) ?: throw notFound()
        return WorkoutDetail(w, store.sets(w.id), store.measurementsOf(user.id, w.id))
    }

    /**
     * Старт, раскладка, финиш, комментарий — всё одной записью (ADR 0007). Первая запись с
     * планом фиксирует его старт и версию; если версия старая, план это покажет
     * (`startedVersion < version`). Вторую активную тренировку сервер не отвергает.
     *
     * Первая запись с [device] закрепляет тренировку за этим устройством; запись с другого — `423`
     * (её забрали, [take]). Без [device] (CLI, агент) замок не проверяется.
     */
    @PUT
    @Path("{id}")
    @Transactional
    fun put(
        @PathParam("id") id: String,
        body: SyncPut<Workout>,
        @HeaderParam(DEVICE_HEADER) device: String?,
        @HeaderParam(DEVICE_KIND_HEADER) kind: String?,
    ): RestResponse<Workout> {
        val workoutId = parseId(id)
        if (body.data.id != workoutId) throw badRequest("id в пути и в теле не совпадают")
        // Правка записи, которой нет, — тренировку стёрли (erase), а телефон отправляет старое: не воскрешать
        if (body.baseRev > 0 && store.workoutRow(workoutId) == null) throw apiError(Response.Status.GONE, "тренировка $workoutId удалена")
        val load = {
            store.workoutRow(workoutId, forUpdate = true)?.also { if (it.userId != user.id) throw notFound() }?.stored
                ?.also { checkDevice(it.record, device) }
        }
        // План могли окончательно удалить, пока телефон был без сети, — тренировку всё равно принять
        val planId = body.data.planId?.takeIf { store.plan(user.id, it, forUpdate = true) != null }
        // День тренировки считает сервер: по смещению телефона, без него — по своему поясу
        val put = body.copy(data = body.data.copy(planId = planId, date = body.data.localDate(days.zone)))

        return sync("workout", Workout.serializer(), put, load, db, { store.workoutRow(workoutId)!!.stored.record }) { w, rev ->
            val n = db.update(
                """
                insert into workout (id, user_id, plan_id, plan_version, started_at, utc_offset_sec, start_date, finished_at, comment,
                  layout, received_at, rev, op_id, deleted, device, device_kind)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), now(), ?, ?, ?, ?, ?)
                on conflict (id) do update set plan_id = excluded.plan_id, plan_version = excluded.plan_version,
                  started_at = excluded.started_at, utc_offset_sec = excluded.utc_offset_sec, start_date = excluded.start_date,
                  finished_at = excluded.finished_at, comment = excluded.comment,
                  layout = excluded.layout, received_at = now(), rev = excluded.rev, op_id = excluded.op_id, deleted = excluded.deleted,
                  device = coalesce(workout.device, excluded.device),
                  device_kind = case when workout.device is null then excluded.device_kind else workout.device_kind end
                where workout.rev = excluded.rev - 1 and workout.user_id = excluded.user_id
                """.trimIndent(),
                w.id, user.id, w.planId, w.planVersion, w.startedAt, w.utcOffset?.totalSeconds, w.date, w.finishedAt, w.comment,
                Json.of(WorkoutLayout.serializer(), w.layout), rev, put.opId, w.deleted, device, device?.let { kind },
            )
            if (n > 0 && w.planId != null && !w.deleted) {
                db.update(
                    "update plan set started_at = ?, started_version = ? where id = ? and started_at is null",
                    w.startedAt, w.planVersion, w.planId,
                )
            }
            n
        }
    }

    /**
     * Стереть тренировку насовсем, мимо корзины: подходы (каскадом) и прежние версии тренировки и
     * подходов. Замеры, фото и журнал остаются без привязки (`on delete set null`). План, который
     * она начала, снова не начат, если других тренировок по нему нет. Повторная отправка старой
     * версии с телефона после этого отвергается ([put], 410), подхода — 404: тренировки нет.
     */
    @DELETE
    @Path("{id}")
    @Transactional
    fun erase(@PathParam("id") id: String) {
        val workoutId = parseId(id)
        val w = store.workoutRow(workoutId, forUpdate = true)?.takeIf { it.userId == user.id }?.stored?.record ?: throw notFound()
        val setIds = db.list("select id from workout_set where workout_id = ?", workoutId) { it.uuidReq("id") }
        if (setIds.isNotEmpty()) db.update("delete from revision_log where entity = 'set' and entity_id = any(?)", UuidList(setIds))
        db.update("delete from revision_log where entity = 'workout' and entity_id = ?", workoutId)
        db.update("delete from workout where id = ?", workoutId)
        if (w.planId != null) {
            db.update(
                "update plan set started_at = null, started_version = null where id = ? and user_id = ? " +
                    "and not exists (select 1 from workout where plan_id = plan.id)",
                w.planId, user.id,
            )
        }
    }

    /**
     * Забрать активную тренировку на это устройство: дальше её ведёт оно, записи прежнего — `423`.
     * Версия тренировки не меняется: устройство — не содержимое. Ответ — как у [get]: из него
     * новое устройство собирает тренировку у себя.
     */
    @POST
    @Path("{id}/take")
    @Transactional
    fun take(
        @PathParam("id") id: String,
        @HeaderParam(DEVICE_HEADER) device: String?,
        @HeaderParam(DEVICE_KIND_HEADER) kind: String?,
    ): WorkoutDetail {
        if (device.isNullOrBlank()) throw badRequest("нет заголовка $DEVICE_HEADER")
        val workoutId = parseId(id)
        val w = store.workoutRow(workoutId, forUpdate = true)?.takeIf { it.userId == user.id }?.stored?.record ?: throw notFound()
        if (!w.active) throw apiError(Response.Status.CONFLICT, "тренировка уже завершена")
        db.update("update workout set device = ?, device_kind = ? where id = ?", device, kind, workoutId)
        return get(id)
    }

    @PUT
    @Path("{id}/sets/{setId}")
    @Transactional
    fun putSet(
        @PathParam("id") id: String,
        @PathParam("setId") setId: String,
        body: SyncPut<SetFact>,
        @HeaderParam(DEVICE_HEADER) device: String?,
    ): RestResponse<SetFact> {
        val workoutId = parseId(id)
        val s = body.data
        if (s.id != parseId(setId) || s.workoutId != workoutId) throw badRequest("id в пути и в теле не совпадают")
        // Телефон отправляет тренировку раньше её подходов. Строка тренировки — под замком: подход
        // не проскочит, пока её забирают
        val w = store.workoutRow(workoutId, forUpdate = true)?.takeIf { it.userId == user.id }?.stored?.record
            ?: throw notFound("нет тренировки $workoutId")
        checkDevice(w, device)
        if (store.exercises(user.id, listOf(s.exerciseId)).isEmpty()) throw badRequest("нет в справочнике: ${s.exerciseId}")

        val read = { lock: Boolean ->
            db.one("select * from workout_set where id = ?${if (lock) " for update" else ""}", s.id) {
                Owned(it.uuidReq("user_id"), Stored(it.setFact(), it.uuidReq("op_id")))
            }
        }
        val load = {
            read(true)?.also { if (it.userId != user.id || it.stored.record.workoutId != workoutId) throw notFound() }?.stored
        }

        return sync("set", SetFact.serializer(), body, load, db, { read(false)!!.stored.record }) { f, rev ->
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
                where workout_set.rev = excluded.rev - 1 and workout_set.user_id = excluded.user_id
                """.trimIndent(),
                f.id, f.workoutId, user.id, f.blockId, f.itemId, f.setNo, f.targetId, f.exerciseId, f.recordType, f.perSide,
                f.weight, f.reps, f.extraWeight, f.seconds, f.rating, f.note, f.done, f.doneAt, rev, body.opId, f.deleted,
            )
        }
    }

    /** Тренировку ведёт другое устройство — её забрали: запись с этого отвергается. */
    private fun checkDevice(w: Workout, device: String?) {
        if (device != null && w.device != null && w.device != device) {
            throw WebApplicationException(
                Response.status(STATUS_LOCKED).entity(ApiError("тренировку ведёт другое устройство")).type(MediaType.APPLICATION_JSON).build(),
            )
        }
    }

    /**
     * План против факта по снимку в раскладке; прошлое — до старта, вес тела — взвешивание на
     * тренировке или последнее до старта, рост — из профиля.
     */
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
            bodyweight = store.bodyweight(user.id, w),
            heightCm = store.profile(user.id).heightCm,
            measurements = store.measurementsOf(user.id, w.id),
            best = store.records(user.id, exerciseIds.toSet(), exclude = w.id, before = w.startedAt),
            health = (w.finishedAt ?: facts.mapNotNull { it.doneAt }.maxOrNull())
                ?.let { store.heartRate(user.id, w.startedAt, it) }.orEmpty(),
        )
    }
}

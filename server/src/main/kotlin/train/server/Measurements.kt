package train.server

import jakarta.transaction.Transactional
import jakarta.ws.rs.GET
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.QueryParam
import org.jboss.resteasy.reactive.RestResponse
import train.core.API_PREFIX
import train.core.Measurement
import train.core.Profile
import train.core.Purged
import train.core.SyncPut
import train.core.Trash

@Path("$API_PREFIX/measurements")
class MeasurementResource(
    private val db: Db,
    private val user: CurrentUser,
    private val days: Days,
) {
    /** `workout` — только замеры этой тренировки. */
    @GET
    fun list(
        @QueryParam("type") type: String?,
        @QueryParam("from") from: String?,
        @QueryParam("to") to: String?,
        @QueryParam("workout") workout: String?,
    ): List<Measurement> {
        val start = parseDate(from)?.let(days::start)
        val end = parseDate(to)?.let(days::end)
        val workoutId = workout?.let(::parseIdParam)
        return db.list(
            "select * from measurement where user_id = ? and not deleted and (cast(? as text) is null or type = ?) " +
                "and (cast(? as timestamptz) is null or measured_at >= ?) and (cast(? as timestamptz) is null or measured_at < ?) " +
                "and (cast(? as uuid) is null or workout_id = ?) order by measured_at desc",
            user.id, type, type, start, start, end, end, workoutId, workoutId,
        ) { it.measurement() }
    }

    @GET
    @Path("{id}")
    fun get(@PathParam("id") id: String): Measurement =
        db.one("select * from measurement where id = ? and user_id = ?", parseId(id), user.id) { it.measurement() } ?: throw notFound()

    @PUT
    @Path("{id}")
    @Transactional
    fun put(@PathParam("id") id: String, body: SyncPut<Measurement>): RestResponse<Measurement> {
        val m = body.data
        if (m.id != parseId(id)) throw badRequest("id в пути и в теле не совпадают")
        val read = { lock: Boolean ->
            db.one("select * from measurement where id = ?${if (lock) " for update" else ""}", m.id) {
                Owned(it.uuidReq("user_id"), Stored(it.measurement(), it.uuidReq("op_id")))
            }
        }
        val load = { read(true)?.also { if (it.userId != user.id) throw notFound() }?.stored }
        // Ссылка на чужую или удалённую насовсем тренировку не нужна — замер остаётся сам по себе
        val workoutId = m.workoutId?.takeIf {
            db.one("select 1 from workout where id = ? and user_id = ?", it, user.id) { true } != null
        }
        val put = body.copy(data = m.copy(workoutId = workoutId))

        return sync("measurement", Measurement.serializer(), put, load, db, { read(false)!!.stored.record }) { r, rev ->
            db.update(
                """
                insert into measurement (id, user_id, type, value, unit, measured_at, workout_id, received_at, rev, op_id, deleted)
                values (?, ?, ?, ?, ?, ?, ?, now(), ?, ?, ?)
                on conflict (id) do update set type = excluded.type, value = excluded.value, unit = excluded.unit,
                  measured_at = excluded.measured_at, workout_id = excluded.workout_id, received_at = now(),
                  rev = excluded.rev, op_id = excluded.op_id, deleted = excluded.deleted
                where measurement.rev = excluded.rev - 1 and measurement.user_id = excluded.user_id
                """.trimIndent(),
                r.id, user.id, r.type, r.value, r.unit, r.measuredAt, r.workoutId, rev, put.opId, r.deleted,
            )
        }
    }
}

@Path("$API_PREFIX/profile")
class ProfileResource(private val db: Db, private val user: CurrentUser) {
    @GET
    fun get(): Profile = db.one("select profile from app_user where id = ?", user.id) { it.json("profile", Profile.serializer()) }!!

    @PUT
    @Transactional
    fun put(body: Profile): Profile {
        body.heightCm?.let { if (it !in 50.0..260.0) throw badRequest("heightCm — рост в сантиметрах, от 50 до 260") }
        db.update("update app_user set profile = cast(? as jsonb) where id = ?", Json.of(Profile.serializer(), body), user.id)
        return body
    }
}

@Path("$API_PREFIX/trash")
class TrashResource(private val db: Db, private val user: CurrentUser, private val days: Days, private val photos: PhotoFiles) {
    @GET
    fun get(): Trash {
        val today = days.today()
        return Trash(
            plans = db.list("${Store.PLAN_SELECT} where p.user_id = ? and p.deleted_at is not null order by p.deleted_at desc", user.id) { it.plan(today) },
            workouts = db.list("select * from workout where user_id = ? and deleted order by started_at desc", user.id) { it.workout() },
            measurements = db.list("select * from measurement where user_id = ? and deleted order by measured_at desc", user.id) { it.measurement() },
            photos = db.list("select * from photo where user_id = ? and deleted_at is not null order by deleted_at desc", user.id) { it.photo() },
        )
    }

    /**
     * Окончательно удалить корзину. Удалённые подходы живых тренировок остаются: без них
     * старая отправка с телефона воскресила бы подход. Файлы фото удаляются с диска.
     */
    @Path("purge")
    @jakarta.ws.rs.POST
    @Transactional
    fun purge(): Purged {
        val measurements = db.update("delete from measurement where user_id = ? and deleted", user.id)
        val workouts = db.update("delete from workout where user_id = ? and deleted", user.id)
        val plans = db.update("delete from plan where user_id = ? and deleted_at is not null", user.id)
        val photoIds = db.list("delete from photo where user_id = ? and deleted_at is not null returning id", user.id) { it.uuidReq("id") }
        photoIds.forEach { photos.delete(user.id, it) }
        return Purged(plans, workouts, measurements, photoIds.size)
    }
}

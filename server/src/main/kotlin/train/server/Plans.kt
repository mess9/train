package train.server

import jakarta.transaction.Transactional
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.jboss.resteasy.reactive.RestResponse
import train.core.API_PREFIX
import train.core.Plan
import train.core.PlanBody
import train.core.PlanPut
import train.core.decodeStrict

@Path("$API_PREFIX/plans")
class PlanResource(
    private val db: Db,
    private val user: CurrentUser,
    private val store: Store,
    private val days: Days,
) {
    /** Планы в корзине сюда не попадают — они в `/trash`. */
    @GET
    fun list(@QueryParam("from") from: String?, @QueryParam("to") to: String?): List<Plan> {
        val today = days.today()
        return db.list(
            "${Store.PLAN_SELECT} where p.user_id = ? and p.deleted_at is null " +
                "and (cast(? as date) is null or p.date >= ?) and (cast(? as date) is null or p.date <= ?) order by p.date, p.updated_at",
            user.id, parseDate(from), parseDate(from), parseDate(to), parseDate(to),
        ) { it.plan(today) }
    }

    @GET
    @Path("{id}")
    fun get(@PathParam("id") id: String): Plan = store.plan(user.id, parseId(id)) ?: throw notFound()

    /**
     * Создать или заменить план: каждая запись — новая неизменяемая версия. Начатый план не
     * меняется — `409` с текущим состоянием. Запись из корзины возвращается.
     * Тело — [PlanPut] строго: незнакомое поле — `400` с его путём, а не молча потерянная цель.
     */
    @PUT
    @Path("{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    fun put(@PathParam("id") id: String, text: String): RestResponse<Plan> {
        val planId = parseId(id)
        val body = decodeStrict(PlanPut.serializer(), text)
        validate(body.body)
        val owner = db.one("select user_id from plan where id = ? for update", planId) { it.uuidReq("user_id") }
        if (owner != null && owner != user.id) throw notFound()
        val old = if (owner != null) store.plan(user.id, planId) else null
        if (old?.startedAt != null) return RestResponse.status(Response.Status.CONFLICT, old)

        val version = (old?.version ?: 0) + 1
        if (old == null) {
            db.update("insert into plan (id, user_id, date, title, version) values (?, ?, ?, ?, ?)", planId, user.id, body.date, body.title, version)
        } else {
            db.update(
                "update plan set date = ?, title = ?, version = ?, updated_at = now(), deleted_at = null where id = ?",
                body.date, body.title, version, planId,
            )
        }
        db.update(
            "insert into plan_revision (plan_id, version, body) values (?, ?, cast(? as jsonb))",
            planId, version, Json.of(PlanBody.serializer(), body.body),
        )
        return RestResponse.ok(store.plan(user.id, planId)!!)
    }

    @DELETE
    @Path("{id}")
    @Transactional
    fun delete(@PathParam("id") id: String) {
        val n = db.update(
            "update plan set deleted_at = coalesce(deleted_at, now()) where id = ? and user_id = ?",
            parseId(id), user.id,
        )
        if (n == 0) throw notFound()
    }

    /** Упражнения — из справочника пользователя; ID блоков, упражнений и целей не повторяются. */
    private fun validate(body: PlanBody) {
        val items = body.blocks.flatMap { it.items }
        val missing = items.map { it.exerciseId }.toSet() - store.exercises(user.id, items.map { it.exerciseId }.toSet()).keys
        if (missing.isNotEmpty()) throw badRequest("нет в справочнике: ${missing.joinToString()}")
        val ids = body.blocks.map { it.id } + items.map { it.id } + items.flatMap { it.sets }.map { it.id }
        if (ids.size != ids.toSet().size) throw badRequest("ID блоков, упражнений и подходов должны быть разными")
        if (items.any { it.sets.isEmpty() }) throw badRequest("у упражнения нет подходов")
    }
}

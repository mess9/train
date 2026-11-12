package train.server

import jakarta.transaction.Transactional
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.QueryParam
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.minus
import train.core.API_PREFIX
import train.core.BODYWEIGHT
import train.core.ExerciseHistory
import train.core.HISTORY_RECENT
import train.core.JournalEntry
import train.core.JournalPut
import train.core.history
import train.core.mainExercises
import kotlin.uuid.Uuid

/** Сколько дней назад искать упражнения программы, если `history` позвали без id. */
private const val PROGRAM_DAYS = 30

/** Последняя живая запись, которая пересматривает запись `j`. */
private const val REVISED_BY =
    "(select r.id from journal_entry r where r.revises = j.id and r.deleted_at is null order by r.created_at desc limit 1)"

/** Записи журнала с вычисленным `revised_by`; алиас таблицы — `j`. */
const val JOURNAL_SELECT = "select j.*, $REVISED_BY as revised_by from journal_entry j"

/**
 * Журнал тренера (ADR 0014): решение, обоснование, условие пересмотра. Создание — `PUT` с id от
 * клиента: повтор не дублирует запись; тот же id — исправить опечатку. Решение не переписывают:
 * пересмотр — новая запись с `revises`. Удаление — в корзину.
 */
@Path("$API_PREFIX/journal")
class JournalResource(private val db: Db, private val user: CurrentUser) {
    /**
     * Записи, новые сначала. `from`, `to` — по дню записи; `exercise` — про это упражнение;
     * `active` — только действующие (не пересмотренные); `deleted` — только из корзины.
     */
    @GET
    fun list(
        @QueryParam("from") from: String?,
        @QueryParam("to") to: String?,
        @QueryParam("exercise") exercise: String?,
        @QueryParam("active") active: Boolean?,
        @QueryParam("deleted") deleted: Boolean?,
        @QueryParam("limit") limit: Int?,
    ): List<JournalEntry> {
        val start = parseDate(from)
        val end = parseDate(to)
        val ex = exercise?.let(::parseIdParam)
        val trash = if (deleted == true) "j.deleted_at is not null" else "j.deleted_at is null"
        val onlyActive = if (active == true) " and $REVISED_BY is null" else ""
        return db.list(
            "$JOURNAL_SELECT where j.user_id = ? and $trash$onlyActive " +
                "and (cast(? as date) is null or j.date >= ?) and (cast(? as date) is null or j.date <= ?) " +
                "and (cast(? as uuid) is null or ? = any(j.exercise_ids)) order by j.date desc, j.created_at desc limit ?",
            user.id, start, start, end, end, ex, ex, (limit ?: 1000).coerceIn(1, 1000),
        ) { it.journalEntry() }
    }

    @GET
    @Path("{id}")
    fun get(@PathParam("id") id: String): JournalEntry = find(parseId(id)) ?: throw notFound()

    /**
     * Создать запись или исправить опечатку в ней; день создания остаётся прежним. Решение,
     * обоснование и условие пересмотра обязательны; `revises` — своя запись, не эта же.
     */
    @PUT
    @Path("{id}")
    @Transactional
    fun put(@PathParam("id") id: String, body: JournalPut): JournalEntry {
        val entryId = parseId(id)
        val decision = body.decision.required("решения")
        val reason = body.reason.required("обоснования")
        val review = body.review.required("условия пересмотра")
        body.workoutId?.let { own("workout", it, "нет тренировки $it") }
        body.planId?.let { own("plan", it, "нет плана $it") }
        body.revises?.let {
            if (it == entryId) throw badRequest("запись не пересматривает саму себя")
            own("journal_entry", it, "нет записи журнала $it")
        }
        val owner = db.one("select user_id from journal_entry where id = ? for update", entryId) { it.uuidReq("user_id") }
        if (owner != null && owner != user.id) throw notFound()
        val exercises = UuidList(body.exerciseIds.distinct())
        if (owner == null) db.update(
            "insert into journal_entry (id, user_id, date, decision, reason, review, workout_id, plan_id, exercise_ids, revises) " +
                "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            entryId, user.id, body.date, decision, reason, review, body.workoutId, body.planId, exercises, body.revises,
        ) else db.update(
            "update journal_entry set date = ?, decision = ?, reason = ?, review = ?, workout_id = ?, plan_id = ?, " +
                "exercise_ids = ?, revises = ?, deleted_at = null where id = ?",
            body.date, decision, reason, review, body.workoutId, body.planId, exercises, body.revises, entryId,
        )
        return find(entryId)!!
    }

    /** В корзину; повторное удаление ничего не меняет. Решение, которое она пересматривала, снова действует. */
    @DELETE
    @Path("{id}")
    @Transactional
    fun delete(@PathParam("id") id: String) {
        val n = db.update("update journal_entry set deleted_at = coalesce(deleted_at, now()) where id = ? and user_id = ?", parseId(id), user.id)
        if (n == 0) throw notFound()
    }

    /** Вернуть из корзины. */
    @POST
    @Path("{id}/restore")
    @Transactional
    fun restore(@PathParam("id") id: String): JournalEntry {
        val entryId = parseId(id)
        if (db.update("update journal_entry set deleted_at = null where id = ? and user_id = ?", entryId, user.id) == 0) throw notFound()
        return find(entryId)!!
    }

    private fun find(id: Uuid): JournalEntry? =
        db.one("$JOURNAL_SELECT where j.id = ? and j.user_id = ?", id, user.id) { it.journalEntry() }

    private fun own(table: String, id: Uuid, error: String) {
        db.one("select 1 from $table where id = ? and user_id = ?", id, user.id) { true } ?: throw badRequest(error)
    }

    private fun String.required(what: String) = trim().ifEmpty { throw badRequest("нет $what") }
}

/**
 * История упражнений для агента ([history]): последние тренировки целиком, ранние — лучшая за
 * месяц, рекорд и плато, решения журнала. Без `ids` — упражнения основных блоков за месяц.
 */
@Path("$API_PREFIX/history")
class HistoryResource(
    private val db: Db,
    private val user: CurrentUser,
    private val store: Store,
    private val days: Days,
) {
    /**
     * `ids` — через запятую; `recent` — сколько последних тренировок целиком; `since` — вместо
     * него все тренировки с этого дня целиком.
     */
    @GET
    fun get(
        @QueryParam("ids") ids: String?,
        @QueryParam("recent") recent: Int?,
        @QueryParam("since") since: String?,
    ): List<ExerciseHistory> {
        val from = parseDate(since)
        val exerciseIds = ids?.split(',')?.filter { it.isNotBlank() }?.map(::parseIdParam)?.takeIf { it.isNotEmpty() }
            ?: program()
        if (exerciseIds.isEmpty()) return emptyList()
        val sets = db.list(
            "select s.* from workout_set s join workout w on w.id = s.workout_id and not w.deleted " +
                "where s.user_id = ? and s.exercise_id = any(?) and s.done and not s.deleted",
            user.id, UuidList(exerciseIds),
        ) { it.setFact() }
        val workouts = if (sets.isEmpty()) emptyList()
        else db.list("select * from workout where id = any(?)", UuidList(sets.map { it.workoutId }.toSet())) { it.workout() }
        val weights = db.list(
            "select * from measurement where user_id = ? and type = ? and not deleted order by measured_at", user.id, BODYWEIGHT,
        ) { it.measurement() }
        val journal = db.list(
            "$JOURNAL_SELECT where j.user_id = ? and j.deleted_at is null and j.exercise_ids && ?", user.id, UuidList(exerciseIds),
        ) { it.journalEntry() }
        return history(
            exerciseIds, workouts, sets, store.exercises(user.id, exerciseIds), weights, journal,
            (recent ?: HISTORY_RECENT).coerceIn(1, 100), days.zone, from,
        )
    }

    private fun program(): List<Uuid> {
        val workouts = db.list(
            "select * from workout where user_id = ? and not deleted and start_date >= ?",
            user.id, days.today().minus(DatePeriod(days = PROGRAM_DAYS - 1)),
        ) { it.workout() }
        if (workouts.isEmpty()) return emptyList()
        val sets = db.list(
            "select * from workout_set where workout_id = any(?) and done and not deleted", UuidList(workouts.map { it.id }),
        ) { it.setFact() }
        return mainExercises(workouts, sets)
    }
}

/** Строка [JOURNAL_SELECT]. */
fun Row.journalEntry() = JournalEntry(
    id = uuidReq("id"),
    date = date("date")!!,
    decision = string("decision")!!,
    reason = string("reason")!!,
    review = string("review")!!,
    workoutId = uuid("workout_id"),
    planId = uuid("plan_id"),
    exerciseIds = uuids("exercise_ids"),
    revises = uuid("revises"),
    revisedBy = uuid("revised_by"),
    createdAt = instantReq("created_at"),
    deletedAt = instant("deleted_at"),
)

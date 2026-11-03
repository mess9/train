package train.server

import jakarta.enterprise.context.ApplicationScoped
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.todayIn
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import org.eclipse.microprofile.config.inject.ConfigProperty
import train.core.BODYWEIGHT
import train.core.Exercise
import train.core.Measurement
import train.core.Plan
import train.core.PlanBody
import train.core.Rating
import train.core.RecordType
import train.core.SetFact
import train.core.Workout
import train.core.WorkoutLayout
import train.core.planStatus
import java.util.Optional
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

private val strings = ListSerializer(String.serializer())

/** Часовой пояс владельца: от него зависят «сегодня» и границы дней в фильтрах. */
@ApplicationScoped
class Days(@ConfigProperty(name = "train.zone") zone: Optional<String>) {
    val zone: TimeZone = zone.map { TimeZone.of(it) }.orElse(TimeZone.currentSystemDefault())

    fun today(): LocalDate = Clock.System.todayIn(zone)

    fun start(d: LocalDate): Instant = d.atStartOfDayIn(zone)

    /** Начало следующего дня: фильтр `to` включает сам день. */
    fun end(d: LocalDate): Instant = d.plus(DatePeriod(days = 1)).atStartOfDayIn(zone)
}

/** Запросы, которые нужны нескольким ресурсам. */
@ApplicationScoped
class Store(private val db: Db, private val days: Days) {
    fun exercises(userId: Uuid, ids: Collection<Uuid>): Map<Uuid, Exercise> =
        if (ids.isEmpty()) emptyMap()
        else db.list("select * from exercise where id = any(?) and $VISIBLE", UuidList(ids), userId) { it.exercise() }
            .associateBy { it.id }

    fun plan(userId: Uuid, id: Uuid, forUpdate: Boolean = false): Plan? = db.one(
        "$PLAN_SELECT where p.id = ? and p.user_id = ?${if (forUpdate) " for update of p" else ""}", id, userId,
    ) { it.plan(days.today()) }

    /** Запись тренировки с владельцем — чтобы отличить «нет» от «чужая». */
    fun workoutRow(id: Uuid, forUpdate: Boolean = false): Owned<Workout>? = db.one(
        "select * from workout where id = ?${if (forUpdate) " for update" else ""}", id,
    ) { Owned(it.uuidReq("user_id"), Stored(it.workout(), it.uuidReq("op_id"))) }

    fun workout(userId: Uuid, id: Uuid): Workout? =
        workoutRow(id)?.takeIf { it.userId == userId }?.stored?.record

    fun sets(workoutId: Uuid): List<SetFact> =
        db.list("select * from workout_set where workout_id = ? order by block_id, item_id, set_no", workoutId) { it.setFact() }

    /**
     * Последние сделанные подходы по упражнениям: все подходы последней тренировки, где упражнение
     * было. `before` и `exclude` — чтобы не взять саму тренировку, для которой ищем прошлое.
     */
    fun lastSets(userId: Uuid, exerciseIds: Collection<Uuid>, before: Instant? = null, exclude: Uuid? = null): List<SetFact> {
        if (exerciseIds.isEmpty()) return emptyList()
        val sql = buildString {
            append("with last as (select distinct on (s.exercise_id) s.exercise_id, s.workout_id from workout_set s ")
            append("join workout w on w.id = s.workout_id and not w.deleted ")
            append("where s.user_id = ? and s.exercise_id = any(?) and s.done and not s.deleted")
            if (before != null) append(" and s.done_at < ?")
            if (exclude != null) append(" and s.workout_id <> ?")
            append(" order by s.exercise_id, s.done_at desc nulls last) ")
            append("select s.* from workout_set s join last l on l.exercise_id = s.exercise_id and l.workout_id = s.workout_id ")
            append("where s.done and not s.deleted order by s.exercise_id, s.set_no")
        }
        val args = listOfNotNull(userId, UuidList(exerciseIds), before, exclude)
        return db.list(sql, *args.toTypedArray()) { it.setFact() }
    }

    /** Последний замер веса тела до момента — для объёма. */
    fun bodyweight(userId: Uuid, at: Instant): Double? = db.one(
        "select value from measurement where user_id = ? and type = ? and not deleted and measured_at <= ? order by measured_at desc limit 1",
        userId, BODYWEIGHT, at,
    ) { it.double("value") }

    companion object {
        /** Справочник пользователя: общие упражнения и его собственные. Параметр — id пользователя. */
        const val VISIBLE = "(owner_user_id is null or owner_user_id = ?) and deleted_at is null"
        const val PLAN_SELECT = "select p.*, r.body from plan p join plan_revision r on r.plan_id = p.id and r.version = p.version"
    }
}

data class Stored<T>(val record: T, val opId: Uuid)

data class Owned<T>(val userId: Uuid, val stored: Stored<T>)

fun Row.exercise() = Exercise(
    id = uuidReq("id"),
    nameRu = string("name_ru")!!,
    nameEn = string("name_en"),
    sourceId = string("source_id"),
    musclesPrimary = json("muscles_primary", strings),
    musclesSecondary = json("muscles_secondary", strings),
    equipment = string("equipment"),
    implements = int("implements")!!,
    recordType = enum<RecordType>("record_type")!!,
    perSide = bool("per_side"),
    bodyweightFactor = double("bodyweight_factor"),
    mediaKey = string("media_key"),
    instructions = json("instructions", strings),
)

fun Row.plan(today: LocalDate): Plan {
    val date = date("date")!!
    val startedAt = instant("started_at")
    return Plan(
        id = uuidReq("id"),
        date = date,
        title = string("title"),
        version = int("version")!!,
        body = json("body", PlanBody.serializer()),
        status = planStatus(date, startedAt, today),
        startedAt = startedAt,
        startedVersion = int("started_version"),
        deletedAt = instant("deleted_at"),
    )
}

fun Row.workout() = Workout(
    id = uuidReq("id"),
    planId = uuid("plan_id"),
    planVersion = int("plan_version"),
    startedAt = instantReq("started_at"),
    finishedAt = instant("finished_at"),
    comment = string("comment"),
    layout = json("layout", WorkoutLayout.serializer()),
    rev = long("rev")!!,
    deleted = bool("deleted"),
)

fun Row.setFact() = SetFact(
    id = uuidReq("id"),
    workoutId = uuidReq("workout_id"),
    blockId = uuidReq("block_id"),
    itemId = uuidReq("item_id"),
    setNo = int("set_no")!!,
    targetId = uuid("target_id"),
    exerciseId = uuidReq("exercise_id"),
    recordType = enum<RecordType>("record_type")!!,
    perSide = bool("per_side"),
    weight = double("weight"),
    reps = int("reps"),
    extraWeight = double("extra_weight"),
    seconds = int("seconds"),
    rating = enum<Rating>("rating"),
    note = string("note"),
    done = bool("done"),
    doneAt = instant("done_at"),
    rev = long("rev")!!,
    deleted = bool("deleted"),
)

fun Row.measurement() = Measurement(
    id = uuidReq("id"),
    type = string("type")!!,
    value = double("value")!!,
    unit = string("unit")!!,
    measuredAt = instantReq("measured_at"),
    workoutId = uuid("workout_id"),
    rev = long("rev")!!,
    deleted = bool("deleted"),
)

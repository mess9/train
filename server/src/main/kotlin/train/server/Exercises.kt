package train.server

import jakarta.transaction.Transactional
import jakarta.ws.rs.GET
import jakarta.ws.rs.PATCH
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.QueryParam
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import train.core.API_PREFIX
import train.core.Exercise
import train.core.ExerciseCreate
import train.core.ExercisePatch
import train.core.RecordType
import train.core.SetFact
import train.core.newId

private val strings = ListSerializer(String.serializer())

@Path("$API_PREFIX/exercises")
class ExerciseResource(
    private val db: Db,
    private val user: CurrentUser,
    private val source: Source,
    private val store: Store,
) {
    /** Справочник: сначала недавно сделанные, потом по названию. `q` — подстрока в ru или en названии. */
    @GET
    fun list(@QueryParam("q") q: String?): List<Exercise> {
        val pattern = q?.takeIf { it.isNotBlank() }?.let { "%${it.trim().lowercase()}%" }
        return db.list(
            """
            select e.*, (select max(s.done_at) from workout_set s where s.exercise_id = e.id and s.user_id = ? and s.done and not s.deleted) as last_done
            from exercise e
            where ${Store.VISIBLE} and (cast(? as text) is null or lower(e.name_ru) like ? or lower(coalesce(e.name_en, '')) like ?)
            order by last_done desc nulls last, e.name_ru
            """.trimIndent(),
            user.id, user.id, pattern, pattern, pattern,
        ) { it.exercise() }
    }

    @GET
    @Path("{id}")
    fun get(@PathParam("id") id: String): Exercise =
        store.exercises(user.id, listOf(parseId(id))).values.firstOrNull() ?: throw notFound()

    /**
     * Новое упражнение: у админа — в общий справочник, у остальных — своё (ADR 0011). Из источника
     * берутся английское название, мышцы, снаряд, медиа и шаги.
     */
    @POST
    @Transactional
    fun create(body: ExerciseCreate): Exercise {
        if (body.nameRu.isBlank()) throw badRequest("nameRu пустое")
        val shared = body.shared ?: user.admin
        if (shared) user.requireAdmin()
        val src = body.sourceId?.let { source[it] ?: throw badRequest("нет в источнике: $it") }
        val e = Exercise(
            id = newId(),
            nameRu = body.nameRu.trim(),
            nameEn = body.nameEn ?: src?.info?.name,
            sourceId = body.sourceId,
            musclesPrimary = body.musclesPrimary ?: listOfNotNull(src?.info?.target),
            musclesSecondary = body.musclesSecondary ?: src?.info?.secondaryMuscles.orEmpty(),
            equipment = body.equipment ?: src?.info?.equipment,
            implements = body.implements ?: 1,
            recordType = body.recordType ?: RecordType.WEIGHT_REPS,
            perSide = body.perSide ?: false,
            bodyweightFactor = body.bodyweightFactor,
            mediaKey = src?.info?.mediaKey,
            instructions = src?.stepsRu.orEmpty(),
            shared = shared,
        )
        validate(e)
        db.update(
            """
            insert into exercise (id, owner_user_id, source_id, name_ru, name_en, muscles_primary, muscles_secondary,
              equipment, implements, bodyweight_factor, record_type, per_side, media_key, instructions)
            values (?, ?, ?, ?, ?, cast(? as jsonb), cast(? as jsonb), ?, ?, ?, ?, ?, ?, cast(? as jsonb))
            """.trimIndent(),
            e.id, if (shared) null else user.id, e.sourceId, e.nameRu, e.nameEn, Json.of(strings, e.musclesPrimary), Json.of(strings, e.musclesSecondary),
            e.equipment, e.implements, e.bodyweightFactor, e.recordType, e.perSide, e.mediaKey, Json.of(strings, e.instructions),
        )
        return e
    }

    /** Свои упражнения правит владелец, общий справочник — админ. */
    @PATCH
    @Path("{id}")
    @Transactional
    fun patch(@PathParam("id") id: String, body: ExercisePatch): Exercise {
        val exId = parseId(id)
        val old = db.one("select * from exercise where id = ? and ${Store.VISIBLE} for update", exId, user.id) { it.exercise() }
            ?: throw notFound()
        if (old.shared) user.requireAdmin()
        val e = old.copy(
            nameRu = body.nameRu?.trim() ?: old.nameRu,
            nameEn = body.nameEn ?: old.nameEn,
            recordType = body.recordType ?: old.recordType,
            implements = body.implements ?: old.implements,
            perSide = body.perSide ?: old.perSide,
            bodyweightFactor = body.bodyweightFactor ?: old.bodyweightFactor,
            equipment = body.equipment ?: old.equipment,
            musclesPrimary = body.musclesPrimary ?: old.musclesPrimary,
            musclesSecondary = body.musclesSecondary ?: old.musclesSecondary,
        )
        validate(e)
        db.update(
            """
            update exercise set name_ru = ?, name_en = ?, record_type = ?, implements = ?, per_side = ?, bodyweight_factor = ?,
              equipment = ?, muscles_primary = cast(? as jsonb), muscles_secondary = cast(? as jsonb)
            where id = ?
            """.trimIndent(),
            e.nameRu, e.nameEn, e.recordType, e.implements, e.perSide, e.bodyweightFactor,
            e.equipment, Json.of(strings, e.musclesPrimary), Json.of(strings, e.musclesSecondary), exId,
        )
        return e
    }

    /**
     * Подходы последней тренировки с каждым упражнением — для «прошлого» и предзаполнения.
     * `exclude` — текущая тренировка, чтобы не взять её саму.
     */
    @GET
    @Path("last-sets")
    fun lastSets(@QueryParam("ids") ids: String?, @QueryParam("exclude") exclude: String?): List<SetFact> {
        val exerciseIds = ids.orEmpty().split(',').filter { it.isNotBlank() }.map { parseIdParam(it) }
        return store.lastSets(user.id, exerciseIds, exclude = exclude?.let(::parseIdParam))
    }

    private fun validate(e: Exercise) {
        if (e.nameRu.isBlank()) throw badRequest("nameRu пустое")
        if (e.implements !in 1..2) throw badRequest("implements — 1 или 2")
        e.bodyweightFactor?.let { if (it !in 0.0..1.5) throw badRequest("bodyweightFactor — от 0 до 1.5") }
    }
}

/** ID в query: кривой — ошибка запроса, а не «нет записи». */
fun parseIdParam(s: String) = runCatching { kotlin.uuid.Uuid.parse(s.trim()) }.getOrElse { throw badRequest("не UUID: $s") }

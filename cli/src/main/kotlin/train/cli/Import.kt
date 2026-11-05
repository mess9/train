package train.cli

import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import train.client.ApiException
import train.client.PutResult
import train.core.Measurement
import train.core.PlanPut
import train.core.SetFact
import train.core.SyncPut
import train.core.SyncRecord
import train.core.TrainJson
import train.core.Workout
import train.core.WorkoutDetail
import train.core.decodeStrict
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.uuid.Uuid

/**
 * Пространство имён UUIDv5 для импорта (ADR 0013): ID записи из чужого источника —
 * `uuid5(IMPORT_NAMESPACE, "<источник>:<исходный ID>")`, повторная заливка попадает в те же записи.
 */
val IMPORT_NAMESPACE: Uuid = Uuid.parse("fde5d64b-7ed4-44f6-82c5-8df8aea58b88")

/** UUID версии 5 (RFC 9562): SHA-1 от пространства имён и имени в UTF-8. */
fun uuid5(namespace: Uuid, name: String): Uuid {
    val sha = MessageDigest.getInstance("SHA-1")
    sha.update(namespace.toByteArray())
    val h = sha.digest(name.encodeToByteArray()).copyOf(16)
    h[6] = ((h[6].toInt() and 0x0f) or 0x50).toByte()
    h[8] = ((h[8].toInt() and 0x3f) or 0x80).toByte()
    return Uuid.fromByteArray(h)
}

fun importId(source: String, ref: String): Uuid = uuid5(IMPORT_NAMESPACE, "$source:$ref")

/** Что импорт сделал с записью; в `--dry-run` — что сделал бы. */
@Serializable
enum class ImportAction {
    /** Записи не было — создана. */
    create,
    /** На сервере другая — перезаписана (`--update`). */
    update,
    /** Уже такая же — не тронута. */
    same,
    /** На сервере другая — не тронута: без `--update` или план уже начат. */
    differs,
}

@Serializable
data class ImportRecord(val kind: String, val id: Uuid, val action: ImportAction, val note: String? = null)

@Serializable
data class ImportReport(
    val dryRun: Boolean,
    val created: Int,
    val updated: Int,
    val same: Int,
    val differs: Int,
    val records: List<ImportRecord>,
)

/** Разобранный каталог импорта: всё читается и проверяется до первой записи. */
class ImportSet(val plans: List<Pair<Uuid, PlanPut>>, val workouts: List<WorkoutDetail>, val measurements: List<Measurement>)

class ImportCmd : ApiCommand(
    "import",
    """
    Залить историю из каталога JSON — тот же формат, что выдают `plan get`, `workout get`, `measure list`:
    plans/*.json — план {id, date, title?, body}; workouts/*.json — {workout, sets, measurements?};
    measurements/*.json — замер или массив замеров. ID задаёт файл (для чужого источника — UUIDv5, ADR 0013).
    Каждую запись сначала читает с сервера: такая же — не трогает, нет — создаёт, другая — оставляет
    и сообщает (код выхода 1), с --update — перезаписывает. Повтор безопасен. Порядок: планы, тренировки
    с подходами, замеры. Фото — отдельно: `photo add --id`.
    """.trimIndent(),
) {
    private val dir by argument(help = "каталог с plans/, workouts/, measurements/")
    private val dryRun by option("--dry-run", help = "только прочитать и сравнить, ничего не записывать").flag()
    private val update by option("--update", help = "перезаписать записи, которые на сервере отличаются").flag()

    private val records = mutableListOf<ImportRecord>()

    override suspend fun exec() {
        val set = readImport(Paths.get(dir))
        checkExercises(set)

        val planVersions = mutableMapOf<Uuid, Int>()
        set.plans.forEach { (id, put) -> importPlan(id, put)?.let { planVersions[id] = it } }
        set.workouts.forEach { importWorkout(it, planVersions) }
        set.measurements.forEach { importMeasurement(it) }

        val report = ImportReport(
            dryRun = dryRun,
            created = records.count { it.action == ImportAction.create },
            updated = records.count { it.action == ImportAction.update },
            same = records.count { it.action == ImportAction.same },
            differs = records.count { it.action == ImportAction.differs },
            records = records,
        )
        out(report)
        if (!dryRun && report.differs > 0) {
            fail("${report.differs} записей на сервере отличаются от файлов и не тронуты — см. differs; --update перезапишет")
        }
    }

    private suspend fun checkExercises(set: ImportSet) {
        val used = set.plans.flatMap { (_, p) -> p.body.blocks.flatMap { b -> b.items.map { it.exerciseId } } } +
            set.workouts.flatMap { d ->
                d.workout.layout.blocks.flatMap { b -> b.items.flatMap { listOfNotNull(it.exerciseId, it.replacedFrom) } } +
                    d.sets.map { it.exerciseId }
            }
        val missing = used.toSet() - api.exercises().map { it.id }.toSet()
        if (missing.isNotEmpty()) fail("нет в справочнике: ${missing.joinToString()} — добавьте `exercise add` до импорта")
    }

    /** Версия плана на сервере после импорта — для `planVersion` тренировки; `null`, если плана нет. */
    private suspend fun importPlan(id: Uuid, put: PlanPut): Int? {
        val current = getOrNull { api.plan(id) }
        val started = "план уже начат — не меняется"
        val (action, note) = when {
            current == null -> ImportAction.create to null
            current.deletedAt != null -> (if (update) ImportAction.update else ImportAction.differs) to "в корзине"
            current.date == put.date && current.title == put.title && current.body == put.body -> ImportAction.same to null
            current.startedAt != null -> ImportAction.differs to started
            update -> ImportAction.update to null
            else -> ImportAction.differs to null
        }
        if (dryRun || action == ImportAction.same || action == ImportAction.differs) {
            record("plan", id, action, note)
            return current?.version ?: 1
        }
        return when (val r = api.putPlan(id, put)) {
            is PutResult.Accepted -> r.record.version.also { record("plan", id, action, note) }
            is PutResult.Conflict -> r.current.version.also { record("plan", id, ImportAction.differs, started) }
        }
    }

    private suspend fun importWorkout(detail: WorkoutDetail, planVersions: Map<Uuid, Int>) {
        val w = detail.workout.let { w -> if (w.planVersion == null && w.planId != null) w.copy(planVersion = planVersions[w.planId]) else w }
        val current = getOrNull { api.workout(w.id) }
        syncRecord("workout", w, current?.workout, Workout.serializer(), { it.copy(rev = 0, date = null) }) { api.putWorkout(it) }

        val currentSets = current?.sets.orEmpty().associateBy { it.id }
        detail.sets.forEach { s ->
            syncRecord("set", s, currentSets[s.id], SetFact.serializer(), { it.copy(rev = 0) }) { api.putSet(it) }
        }
        detail.measurements.forEach { importMeasurement(if (it.workoutId == null) it.copy(workoutId = w.id) else it) }
    }

    private suspend fun importMeasurement(m: Measurement) {
        val current = getOrNull { api.measurement(m.id) }
        syncRecord("measurement", m, current, Measurement.serializer(), { it.copy(rev = 0) }) { api.putMeasurement(it) }
    }

    /**
     * Запись по протоколу ADR 0007: новая — `baseRev = 0`, перезапись — `baseRev` текущей версии.
     * `opId` выводится из содержимого: повтор той же отправки сервер узнаёт и не пишет второй раз.
     * [normal] убирает то, что считает сервер (`rev`, день тренировки), перед сравнением.
     */
    private suspend fun <T : SyncRecord> syncRecord(
        kind: String,
        record: T,
        current: T?,
        serializer: KSerializer<T>,
        normal: (T) -> T,
        put: suspend (SyncPut<T>) -> PutResult<T>,
    ) {
        val id = record.id
        val action = when {
            current == null -> ImportAction.create
            normal(current) == normal(record) -> ImportAction.same
            update -> ImportAction.update
            else -> ImportAction.differs
        }
        if (dryRun || action == ImportAction.same || action == ImportAction.differs) return record(kind, id, action)

        val baseRev = current?.rev ?: 0
        val opId = uuid5(IMPORT_NAMESPACE, "op:$kind:$baseRev:${TrainJson.encodeToString(serializer, record)}")
        when (put(SyncPut(record, baseRev, opId))) {
            is PutResult.Accepted -> record(kind, id, action)
            is PutResult.Conflict -> record(kind, id, ImportAction.differs, "изменилась на сервере во время импорта")
        }
    }

    private fun record(kind: String, id: Uuid, action: ImportAction, note: String? = null) {
        records += ImportRecord(kind, id, action, note)
    }

    private suspend fun <T> getOrNull(read: suspend () -> T): T? = try {
        read()
    } catch (e: ApiException) {
        if (e.status == 404) null else throw e
    }

    /** Прочитать и проверить каталог целиком: ошибка в любом файле — до первой записи на сервер. */
    private fun readImport(root: Path): ImportSet {
        if (!root.isDirectory()) fail("нет каталога $root")
        val plans = files(root, "plans").map { (path, text) ->
            parse(path) {
                val json = TrainJson.parseToJsonElement(text).jsonObject
                val id = (json["id"] as? JsonPrimitive)?.content?.let(Uuid::parse) ?: error("нет поля id")
                id to decodeStrict(PlanPut.serializer(), JsonObject(json - PLAN_OUTPUT_ONLY).toString())
            }
        }
        val workouts = files(root, "workouts").map { (path, text) ->
            parse(path) { decodeStrict(WorkoutDetail.serializer(), text) }.also { d -> checkWorkout(path, d) }
        }
        val measurements = files(root, "measurements").flatMap { (path, text) ->
            parse(path) {
                if (TrainJson.parseToJsonElement(text) is JsonArray) {
                    decodeStrict(ListSerializer(Measurement.serializer()), text)
                } else {
                    listOf(decodeStrict(Measurement.serializer(), text))
                }
            }
        }
        val ids = plans.map { it.first } + workouts.flatMap { d -> listOf(d.workout.id) + d.sets.map { it.id } + d.measurements.map { it.id } } +
            measurements.map { it.id }
        ids.groupBy { it }.filter { it.value.size > 1 }.keys.firstOrNull()?.let { fail("ID $it встречается в импорте дважды") }
        if (plans.isEmpty() && workouts.isEmpty() && measurements.isEmpty()) fail("в $root нет plans/, workouts/, measurements/ с файлами .json")
        return ImportSet(plans, workouts, measurements)
    }

    private fun checkWorkout(path: Path, d: WorkoutDetail) {
        d.sets.firstOrNull { it.workoutId != d.workout.id }?.let { fail("$path: подход ${it.id} — от другой тренировки ${it.workoutId}") }
        val items = d.workout.layout.blocks.flatMap { b -> b.items.map { b.id to it.id } }.toSet()
        d.sets.firstOrNull { (it.blockId to it.itemId) !in items }?.let { fail("$path: подход ${it.id} — нет такого блока и упражнения в раскладке") }
        d.measurements.firstOrNull { it.workoutId != null && it.workoutId != d.workout.id }
            ?.let { fail("$path: замер ${it.id} — от другой тренировки ${it.workoutId}") }
    }

    private fun files(root: Path, sub: String): List<Pair<Path, String>> {
        val d = root.resolve(sub)
        if (!d.isDirectory()) return emptyList()
        return Files.list(d).use { s -> s.filter { it.extension == "json" }.sorted(compareBy { it.name }).toList() }
            .map { it to runCatching { Files.readString(it) }.getOrElse { e -> fail("не прочитать файл $it: ${e::class.simpleName}") } }
    }

    private fun <T> parse(path: Path, decode: () -> T): T = runCatching(decode).getOrElse { fail("$path не разобран: ${it.message}") }
}

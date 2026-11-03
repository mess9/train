package train.cli

import com.github.ajalt.clikt.core.BadParameterValue
import com.github.ajalt.clikt.parameters.arguments.ProcessedArgument
import com.github.ajalt.clikt.parameters.arguments.RawArgument
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.convert
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.NullableOption
import com.github.ajalt.clikt.parameters.options.RawOption
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.nullableFlag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.int
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import train.client.PutResult
import train.core.ExerciseCreate
import train.core.ExercisePatch
import train.core.Measurement
import train.core.PlanPut
import train.core.Profile
import train.core.RecordType
import train.core.SyncPut
import train.core.TrainJson
import train.core.newId
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

// Разбор аргументов

private fun parseUuid(s: String): Uuid = runCatching { Uuid.parse(s) }.getOrElse { throw BadParameterValue("не UUID: $s") }

private fun RawArgument.uuid(): ProcessedArgument<Uuid, Uuid> = convert { parseUuid(it) }

private fun RawOption.date(): NullableOption<LocalDate, LocalDate> =
    convert("YYYY-MM-DD") { runCatching { LocalDate.parse(it) }.getOrElse { fail("дата не в формате YYYY-MM-DD: $it") } }

private fun RawOption.recordType() = choice(RecordType.entries.associateBy { it.name.lowercase() }, ignoreCase = true)

/** Файл или `-` — stdin. */
private fun ApiCommand.readInput(path: String): String =
    if (path == "-") {
        generateSequence(::readLine).joinToString("\n")
    } else {
        runCatching { Files.readString(Paths.get(path)) }.getOrElse { fail("не прочитать файл $path: ${it::class.simpleName}") }
    }

private inline fun <reified T> ApiCommand.accepted(r: PutResult<T>, conflict: (T) -> String): T = when (r) {
    is PutResult.Accepted -> r.record
    is PutResult.Conflict -> failConflict(conflict(r.current))
}

private fun ApiCommand.failConflict(message: String): Nothing = fail(message, 409)

// Планы

class PlanPutCmd : ApiCommand(
    "put",
    """
    Создать или заменить план — каждая запись новая версия. JSON: {date, title?, body: {blocks, note?}}.
    ID плана — --id, поле "id" в файле (вывод `plan get` можно править и отправлять обратно) или новый.
    Начатый план не меняется: ошибка 409, нужен новый план.
    """.trimIndent(),
) {
    private val file by argument(help = "файл JSON или - для stdin")
    private val id by option("--id", help = "ID плана").convert { parseUuid(it) }

    override suspend fun exec() {
        val text = readInput(file)
        val json = runCatching { TrainJson.parseToJsonElement(text).jsonObject }
            .getOrElse { fail("не JSON-объект: ${it.message}") }
        val planId = id ?: (json["id"] as? JsonPrimitive)?.content?.let(::parseUuid) ?: newId()
        val body = runCatching { TrainJson.decodeFromJsonElement(PlanPut.serializer(), JsonObject(json - "id")) }
            .getOrElse { fail("план не разобран: ${it.message}") }
        out(accepted(api.putPlan(planId, body)) { "план уже начат (${it.startedAt}) — меняется только новый план" })
    }
}

class PlanList : ApiCommand("list", "Планы, по дате; удалённые — в `trash list`") {
    private val from by option("--from", help = "с даты, YYYY-MM-DD").date()
    private val to by option("--to", help = "по дату включительно").date()
    override suspend fun exec() = out(api.plans(from, to))
}

class PlanGet : ApiCommand("get", "План с телом текущей версии") {
    private val id by argument().uuid()
    override suspend fun exec() = out(api.plan(id))
}

class PlanRm : ApiCommand("rm", "Удалить план в корзину") {
    private val id by argument().uuid()
    override suspend fun exec() {
        api.deletePlan(id)
        out(api.plan(id))
    }
}

// Тренировки

class WorkoutList : ApiCommand("list", "Тренировки, новые сначала") {
    private val from by option("--from", help = "с даты, YYYY-MM-DD").date()
    private val to by option("--to", help = "по дату включительно").date()
    override suspend fun exec() = out(api.workouts(from, to))
}

class WorkoutGet : ApiCommand("get", "Тренировка с подходами, включая удалённые") {
    private val id by argument().uuid()
    override suspend fun exec() = out(api.workout(id))
}

class CompareCmd : ApiCommand("compare", "План против факта по тренировке: подходы, итоги, прошлые результаты") {
    private val id by argument("workoutId").uuid()
    override suspend fun exec() = out(api.compare(id))
}

// Справочник

class ExerciseList : ApiCommand("list", "Справочник: сначала недавние") {
    private val q by option("--q", help = "подстрока в названии")
    override suspend fun exec() = out(api.exercises(q))
}

class ExerciseGet : ApiCommand("get", "Упражнение справочника") {
    private val id by argument().uuid()
    override suspend fun exec() = out(api.exercise(id))
}

class ExerciseSearch : ApiCommand("search", "Поиск по всему датасету (английский): каждое слово — в названии, мышцах, снаряде") {
    private val words by argument("q").multiple(required = true)
    private val limit by option("--limit").int()
    override suspend fun exec() = out(api.searchSource(words.joinToString(" "), limit))
}

/** Поля справочника, общие для `add` и `edit`. */
abstract class ExerciseFields(name: String, about: String) : ApiCommand(name, about) {
    protected val en by option("--en", help = "английское название")
    protected val type by option("--type", help = "тип записи").recordType()
    protected val implements by option("--implements", help = "снарядов: 1 или 2 (две гантели)").int()
    protected val perSide by option("--per-side", help = "повторы на каждую сторону").nullableFlag("--no-per-side")
    protected val bodyweightFactor by option("--bodyweight-factor", help = "доля веса тела в объёме: отжимания 0.65, подтягивания 1").double()
    protected val equipment by option("--equipment")
    protected val primary by option("--primary", help = "основные мышцы через запятую").convert { it.split(',').map(String::trim) }
    protected val secondary by option("--secondary", help = "вспомогательные мышцы через запятую").convert { it.split(',').map(String::trim) }
}

class ExerciseAdd : ExerciseFields(
    "add",
    "Добавить в справочник: из датасета (sourceId из `exercise search`) — с мышцами, медиа и шагами — или своё",
) {
    private val sourceId by argument().optional()
    private val ru by option("--ru", help = "русское название").required()
    override suspend fun exec() = out(
        api.createExercise(
            ExerciseCreate(
                nameRu = ru, sourceId = sourceId, nameEn = en, recordType = type, implements = implements, perSide = perSide,
                bodyweightFactor = bodyweightFactor, equipment = equipment, musclesPrimary = primary, musclesSecondary = secondary,
            ),
        ),
    )
}

class ExerciseEdit : ExerciseFields("edit", "Поправить своё упражнение: меняются только переданные поля") {
    private val id by argument().uuid()
    private val ru by option("--ru", help = "русское название")
    override suspend fun exec() = out(
        api.patchExercise(
            id,
            ExercisePatch(
                nameRu = ru, nameEn = en, recordType = type, implements = implements, perSide = perSide,
                bodyweightFactor = bodyweightFactor, equipment = equipment, musclesPrimary = primary, musclesSecondary = secondary,
            ),
        ),
    )
}

class ExerciseLast : ApiCommand("last", "Подходы последней тренировки с каждым упражнением — чтобы подобрать веса") {
    private val ids by argument("id").uuid().multiple(required = true)
    private val exclude by option("--exclude", help = "не брать эту тренировку").convert { parseUuid(it) }
    override suspend fun exec() = out(api.lastSets(ids, exclude))
}

// Замеры

class MeasureList : ApiCommand("list", "Замеры, новые сначала") {
    private val type by option("--type", help = "например weight, waist")
    private val from by option("--from").date()
    private val to by option("--to").date()
    override suspend fun exec() = out(api.measurements(type, from, to))
}

class MeasureAdd : ApiCommand("add", "Записать замер, например `measure add weight 81.5`") {
    private val type by argument()
    private val value by argument().convert { it.replace(',', '.').toDoubleOrNull() ?: throw BadParameterValue("не число: $it") }
    private val unit by option("--unit").default("kg")
    private val at by option("--at", help = "время, ISO 8601; по умолчанию сейчас")
        .convert { runCatching { Instant.parse(it) }.getOrElse { fail("время не в формате ISO 8601: $it") } }

    override suspend fun exec() {
        val m = Measurement(newId(), type, value, unit, at ?: Clock.System.now())
        out(accepted(api.putMeasurement(SyncPut(m, baseRev = 0, opId = newId()))) { "замер ${it.id} уже есть" })
    }
}

// Профиль

class ProfileGet : ApiCommand("get", "Профиль") {
    override suspend fun exec() = out(api.profile())
}

class ProfilePut : ApiCommand("put", "Заменить профиль. JSON: {name?, equipment?, notes?, vibration?}") {
    private val file by argument(help = "файл JSON или - для stdin")
    override suspend fun exec() {
        val text = readInput(file)
        val p = runCatching { TrainJson.decodeFromString(Profile.serializer(), text) }
            .getOrElse { fail("профиль не разобран: ${it.message}") }
        out(api.putProfile(p))
    }
}

// Корзина

class TrashList : ApiCommand("list", "Удалённые планы, тренировки и замеры") {
    override suspend fun exec() = out(api.trash())
}

class TrashPurge : ApiCommand("purge", "Удалить корзину насовсем. Без --yes ничего не делает") {
    private val yes by option("--yes", help = "да, удалить насовсем").flag()
    override suspend fun exec() {
        if (!yes) fail("окончательное удаление: повторите с --yes")
        out(api.purgeTrash())
    }
}

// Ключи

@Serializable
data class KeyLink(val id: Uuid, val key: String, val label: String? = null, val link: String)

class AdminKey : ApiCommand(
    "key",
    "Новый ключ этого же пользователя. --qr — QR со ссылкой …/#key=… для телефона (в stderr). Ключ показывается один раз",
) {
    private val label by option("--label", help = "например, телефон")
    private val qr by option("--qr").flag()

    override suspend fun exec() {
        val k = api.createKey(label)
        // Фрагмент URL не уходит на сервер и не остаётся в логах (ADR 0008)
        val link = "${session.config.url.trimEnd('/')}/#key=${k.key}"
        if (qr) echo(Qr.render(link), err = true)
        out(KeyLink(k.id, k.key, k.label, link))
    }
}

class AdminKeys : ApiCommand("keys", "Ключи пользователя: метка, создан, отозван; current — этот") {
    override suspend fun exec() = out(api.keys())
}

class AdminRevoke : ApiCommand("revoke", "Отозвать ключ — например, потерянного телефона") {
    private val id by argument().uuid()
    override suspend fun exec() {
        api.revokeKey(id)
        out(api.keys().firstOrNull { it.id == id } ?: fail("ключ отозван, но не найден в списке"))
    }
}

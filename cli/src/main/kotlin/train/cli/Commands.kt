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
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import train.client.PutResult
import train.core.ExerciseCreate
import train.core.ExercisePatch
import train.core.Measurement
import train.core.PhotoPatch
import train.core.Plan
import train.core.PlanPut
import train.core.Profile
import train.core.RecordType
import train.core.SyncPut
import train.core.TrainJson
import train.core.decodeStrict
import train.core.User
import train.core.UserCreate
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

private fun RawOption.uuid(): NullableOption<Uuid, Uuid> = convert("UUID") { parseUuid(it) }

/** ISO 8601 или `YYYY-MM-DD` — полдень того дня в поясе этой машины. */
private fun parseMoment(s: String): Instant? =
    runCatching { Instant.parse(s) }.getOrNull()
        ?: runCatching { LocalDate.parse(s).atTime(12, 0).toInstant(TimeZone.currentSystemDefault()) }.getOrNull()

private fun RawOption.moment(): NullableOption<Instant, Instant> =
    convert("ISO|YYYY-MM-DD") { parseMoment(it) ?: fail("время — ISO 8601 или YYYY-MM-DD: $it") }

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
        val body = runCatching { decodeStrict(PlanPut.serializer(), JsonObject(json - PLAN_OUTPUT_ONLY).toString()) }
            .getOrElse { fail("план не разобран: ${it.message}") }
        out(accepted(api.putPlan(planId, body)) { "план уже начат (${it.startedAt}) — меняется только новый план" })
    }
}

/**
 * Поля вывода `plan get`, которых нет во вводе (`id`, `version`, `status`…): их отбрасываем,
 * остальное разбирается строго — опечатка в имени поля даёт ошибку, а не теряется.
 */
private val PLAN_OUTPUT_ONLY: Set<String> = names(Plan.serializer().descriptor) - names(PlanPut.serializer().descriptor)

private fun names(d: SerialDescriptor) = (0 until d.elementsCount).map(d::getElementName).toSet()

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
    "Добавить в справочник: из датасета (sourceId из `exercise search`) — с мышцами, медиа и шагами — или без него. У админа — в общий",
) {
    private val sourceId by argument().optional()
    private val ru by option("--ru", help = "русское название").required()
    private val shared by option(
        "--shared",
        help = "в общий справочник (только админ); --own — только себе. По умолчанию у админа — общий",
    ).nullableFlag("--own")
    override suspend fun exec() = out(
        api.createExercise(
            ExerciseCreate(
                nameRu = ru, sourceId = sourceId, nameEn = en, recordType = type, implements = implements, perSide = perSide,
                bodyweightFactor = bodyweightFactor, equipment = equipment, musclesPrimary = primary, musclesSecondary = secondary,
                shared = shared,
            ),
        ),
    )
}

class ExerciseEdit : ExerciseFields("edit", "Поправить упражнение — своё или, админу, общее: меняются только переданные поля") {
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
    private val workout by option("--workout", help = "только замеры этой тренировки").uuid()
    override suspend fun exec() = out(api.measurements(type, from, to, workout))
}

class MeasureAdd : ApiCommand("add", "Записать замер, например `measure add weight 81.5`") {
    private val type by argument()
    private val value by argument().convert { it.replace(',', '.').toDoubleOrNull() ?: throw BadParameterValue("не число: $it") }
    private val unit by option("--unit").default("kg")
    private val at by option("--at", help = "время, ISO 8601; по умолчанию сейчас")
        .convert { runCatching { Instant.parse(it) }.getOrElse { fail("время не в формате ISO 8601: $it") } }

    private val workout by option("--workout", help = "замер сделан на этой тренировке, например вес после неё").uuid()

    override suspend fun exec() {
        val m = Measurement(newId(), type, value, unit, at ?: Clock.System.now(), workoutId = workout)
        out(accepted(api.putMeasurement(SyncPut(m, baseRev = 0, opId = newId()))) { "замер ${it.id} уже есть" })
    }
}

// Фото прогресса (ADR 0010)

class PhotoList : ApiCommand("list", "Фото, новые сначала; удалённые — в `trash list`") {
    private val from by option("--from", help = "по дню съёмки").date()
    private val to by option("--to").date()
    private val workout by option("--workout", help = "только фото этой тренировки").uuid()
    override suspend fun exec() = out(api.photos(from, to, workout))
}

class PhotoAdd : ApiCommand("add", "Загрузить фото (JPEG, PNG, WebP, HEIC). --at — когда снято, можно задним числом") {
    private val file by argument(help = "файл фото")
    private val at by option("--at", help = "когда снято: ISO 8601 или YYYY-MM-DD; по умолчанию — сейчас").moment()
    private val pose by option("--pose", help = "ракурс: front, side, back или свой")
    private val note by option("--note")
    private val workout by option("--workout", help = "снято на этой тренировке").uuid()
    private val id by option("--id", help = "свой id — повтор с тем же файлом ничего не дублирует").uuid()

    override suspend fun exec() {
        val bytes = runCatching { Files.readAllBytes(Paths.get(file)) }.getOrElse { fail("не прочитать файл $file: ${it::class.simpleName}") }
        out(api.uploadPhoto(id ?: newId(), bytes, at?.toString(), pose, note, workout))
    }
}

class PhotoGet : ApiCommand("get", "Описание фото; --out — сохранить и сам файл") {
    private val id by argument().uuid()
    private val outFile by option("--out", help = "куда сохранить файл")
    override suspend fun exec() {
        val photo = api.photo(id)
        outFile?.let { path ->
            val bytes = api.photoFile(id)
            runCatching { Files.write(Paths.get(path), bytes) }.getOrElse { fail("не записать файл $path: ${it::class.simpleName}") }
        }
        out(photo)
    }
}

class PhotoEdit : ApiCommand("edit", "Поправить дату съёмки, ракурс, заметку, тренировку; пустая строка стирает") {
    private val id by argument().uuid()
    private val at by option("--at", help = "ISO 8601 или YYYY-MM-DD").moment()
    private val pose by option("--pose")
    private val note by option("--note")
    private val workout by option("--workout").uuid()
    override suspend fun exec() = out(api.patchPhoto(id, PhotoPatch(takenAt = at, pose = pose, note = note, workoutId = workout)))
}

class PhotoRm : ApiCommand("rm", "Удалить фото в корзину; файл стирает `trash purge`") {
    private val id by argument().uuid()
    override suspend fun exec() {
        api.deletePhoto(id)
        out(api.photo(id))
    }
}

class PhotoRestore : ApiCommand("restore", "Вернуть фото из корзины") {
    private val id by argument().uuid()
    override suspend fun exec() = out(api.restorePhoto(id))
}

// Профиль

class ProfileGet : ApiCommand("get", "Профиль") {
    override suspend fun exec() = out(api.profile())
}

class ProfilePut : ApiCommand("put", "Заменить профиль. JSON: {name?, equipment?, notes?, vibration?, heightCm?}") {
    private val file by argument(help = "файл JSON или - для stdin")
    override suspend fun exec() {
        val text = readInput(file)
        val p = runCatching { decodeStrict(Profile.serializer(), text) }
            .getOrElse { fail("профиль не разобран: ${it.message}") }
        out(api.putProfile(p))
    }
}

// Корзина

class TrashList : ApiCommand("list", "Удалённые планы, тренировки, замеры и фото") {
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

/** Новый пользователь и ссылка для его телефона: ключ показывается один раз. */
@Serializable
data class UserLink(val user: User, val key: KeyLink)

class UserAdd : ApiCommand(
    "add",
    "Завести пользователя (только админ) с первым ключом. --qr — QR со ссылкой …/#key=… для его телефона (в stderr)",
) {
    private val email by argument(help = "почта — та же, что пустит Cloudflare Access")
    private val name by option("--name")
    private val label by option("--label", help = "метка первого ключа, по умолчанию «первый»")
    private val qr by option("--qr").flag()

    override suspend fun exec() {
        val created = api.createUser(UserCreate(email, name, label))
        val k = created.key
        val link = "${session.config.url.trimEnd('/')}/#key=${k.key}"
        if (qr) echo(Qr.render(link), err = true)
        out(UserLink(created.user, KeyLink(k.id, k.key, k.label, link)))
    }
}

class UserList : ApiCommand("list", "Пользователи (только админ)") {
    override suspend fun exec() = out(api.users())
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

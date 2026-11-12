package train.cli

import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.command.main
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.findOrSetObject
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import train.client.ApiClient
import train.client.ApiException
import train.core.TrainJson
import java.io.IOException

/** Что нужно командам: клиент, настройки и формат вывода. */
class Session(val api: ApiClient, val config: Config, val json: Json)

private val PrettyJson = Json(TrainJson) { prettyPrint = true }

// Вывод — JSON (ADR 0005): агент читает его как есть
class Gym(private val http: () -> HttpClient = { HttpClient(Java) { followRedirects = false } }) : SuspendingCliktCommand("gym") {
    private val url by option("--url", help = "адрес сервера; иначе GYM_URL или файл конфига")
    private val pretty by option("--pretty", help = "JSON с отступами").flag()

    override fun help(context: Context) = """
        Журнал тренировок: планы, тренировки, сравнение. Вывод — JSON.

        Ключ — GYM_API_KEY или файл ~/.config/gym/config (url=…, key=…, cf_client_id=…, cf_client_secret=…).
        Ошибка — JSON {"error", "status"} в stderr и код выхода 1.
    """.trimIndent()

    override suspend fun run() {
        val config = Config.resolve(url) { currentContext.readEnvvar(it) }
        currentContext.findOrSetObject {
            Session(ApiClient(http(), config.url, config.key, config.headers), config, if (pretty) PrettyJson else TrainJson)
        }
    }
}

/** Команда, которая ходит в API. Ошибки API и сети — одной строкой JSON в stderr. */
abstract class ApiCommand(name: String, private val about: String) : SuspendingCliktCommand(name) {
    protected val session by requireObject<Session>()
    protected val api get() = session.api

    override fun help(context: Context) = about

    abstract suspend fun exec()

    override suspend fun run() {
        try {
            exec()
        } catch (e: ApiException) {
            fail(e.message ?: "ошибка", e.status)
        } catch (e: IOException) {
            fail("нет связи с ${session.config.url}: ${e.message ?: e::class.simpleName}")
        } catch (e: SerializationException) {
            fail("ответ сервера не разобран: ${e.message}")
        }
    }

    protected inline fun <reified T> out(value: T) = out(serializer<T>(), value)

    protected fun <T> out(serializer: KSerializer<T>, value: T) = echo(session.json.encodeToString(serializer, value))

    fun fail(message: String, status: Int? = null): Nothing {
        val err = buildJsonObject {
            put("error", message)
            status?.let { put("status", it) }
        }
        echo(TrainJson.encodeToString(err), err = true)
        throw ProgramResult(1)
    }
}

/** Группа подкоманд без своего действия. */
class Group(name: String, private val about: String) : SuspendingCliktCommand(name) {
    override fun help(context: Context) = about
    override suspend fun run() = Unit
}

class Info : ApiCommand("info", "Проверить связь с сервером") {
    override suspend fun exec() = out(api.info())
}

class MeCmd : ApiCommand("me", "Владелец ключа") {
    override suspend fun exec() = out(api.me())
}

fun gym(http: (() -> HttpClient)? = null): Gym = (http?.let(::Gym) ?: Gym()).subcommands(
    Info(),
    MeCmd(),
    Group("plan", "Планы: создать, заменить, посмотреть, удалить").subcommands(PlanPutCmd(), PlanList(), PlanGet(), PlanRm()),
    Group("workout", "Тренировки с подходами").subcommands(WorkoutList(), WorkoutGet()),
    CompareCmd(),
    ImportCmd(),
    Group("exercise", "Справочник упражнений и поиск по датасету").subcommands(
        ExerciseList(), ExerciseGet(), ExerciseSearch(), ExerciseAdd(), ExerciseEdit(), ExerciseMedia(), ExerciseImage(), ExerciseLast(),
    ),
    Group("measure", "Замеры: вес тела и другие").subcommands(MeasureList(), MeasureAdd()),
    Group("photo", "Фото прогресса").subcommands(PhotoList(), PhotoAdd(), PhotoGet(), PhotoEdit(), PhotoRm(), PhotoRestore()),
    Group("profile", "Профиль: оборудование и заметки для агента").subcommands(ProfileGet(), ProfilePut()),
    Group("trash", "Корзина").subcommands(TrashList(), TrashPurge()),
    Group("admin", "Ключи API и пользователи").subcommands(
        AdminKey(), AdminKeys(), AdminRevoke(),
        Group("user", "Пользователи: завести с первым ключом, список (только админ)").subcommands(UserAdd(), UserList()),
    ),
)

suspend fun main(args: Array<String>) = gym().main(args)

package train.cli

import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.command.main
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.findOrSetObject
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import train.client.ApiClient
import train.core.ServerInfo
import train.core.TrainJson

// Вывод — JSON (ADR 0005): агент читает его как есть
class Gym : SuspendingCliktCommand("gym") {
    private val url by option("--url", envvar = "GYM_URL", help = "адрес сервера").default("http://localhost:7313")
    override fun help(context: Context) = "Журнал тренировок: планы, тренировки, сравнение"

    override suspend fun run() {
        currentContext.findOrSetObject { ApiClient(HttpClient(Java), url) }
    }
}

class Info : SuspendingCliktCommand("info") {
    private val api by requireObject<ApiClient>()
    override fun help(context: Context) = "Проверить связь с сервером"

    override suspend fun run() {
        echo(TrainJson.encodeToString(ServerInfo.serializer(), api.info()))
    }
}

suspend fun main(args: Array<String>) = Gym().subcommands(Info()).main(args)

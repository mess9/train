package train.cli

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Куда и с чем ходить. Порядок: флаг → переменная окружения → файл `~/.config/gym/config`
 * (другой файл — `GYM_CONFIG`). Файл — строки `ключ=значение`, `#` — комментарий:
 *
 * ```
 * url=https://train.example.org
 * key=…
 * # сервисный токен Cloudflare Access — для агента на другой машине
 * cf_client_id=…
 * cf_client_secret=…
 * ```
 */
data class Config(
    val url: String,
    val key: String?,
    val cfClientId: String?,
    val cfClientSecret: String?,
) {
    /** Заголовки сервисного токена Access; без пары id + secret — ничего. */
    val headers: Map<String, String>
        get() = if (cfClientId != null && cfClientSecret != null) {
            mapOf("CF-Access-Client-Id" to cfClientId, "CF-Access-Client-Secret" to cfClientSecret)
        } else {
            emptyMap()
        }

    companion object {
        const val DEFAULT_URL = "http://localhost:7313"

        fun defaultPath(): Path = Paths.get(System.getProperty("user.home"), ".config", "gym", "config")

        fun resolve(urlFlag: String?, env: (String) -> String?): Config {
            val file = read(env("GYM_CONFIG")?.let { Paths.get(it) } ?: defaultPath())
            fun pick(envName: String, fileKey: String) = env(envName)?.takeIf { it.isNotBlank() } ?: file[fileKey]
            return Config(
                url = urlFlag ?: pick("GYM_URL", "url") ?: DEFAULT_URL,
                key = pick("GYM_API_KEY", "key"),
                cfClientId = pick("GYM_CF_CLIENT_ID", "cf_client_id"),
                cfClientSecret = pick("GYM_CF_CLIENT_SECRET", "cf_client_secret"),
            )
        }

        fun read(path: Path): Map<String, String> {
            if (!Files.isRegularFile(path)) return emptyMap()
            return Files.readAllLines(path).asSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") && '=' in it }
                .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
                .filterValues { it.isNotEmpty() }
        }
    }
}

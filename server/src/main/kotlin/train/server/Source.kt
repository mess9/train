package train.server

import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.Response
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import train.core.API_PREFIX
import train.core.SourceExercise
import train.core.TrainJson
import java.nio.file.Files
import java.util.Optional
import java.nio.file.Paths
import kotlin.io.path.inputStream
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/** Запись датасета с тем, что нужно для поиска и добавления в справочник. */
class SourceRecord(
    val info: SourceExercise,
    val muscleGroup: String?,
    val stepsRu: List<String>,
) {
    /** Строка для поиска: каждое слово запроса должно в ней встретиться. */
    val haystack = listOfNotNull(info.name, info.bodyPart, info.target, info.equipment, muscleGroup)
        .plus(info.secondaryMuscles).joinToString(" ").lowercase()
}

/** exercises-dataset: `exercises.json` в памяти. Нет файла — поиск пустой, справочник работает. */
@ApplicationScoped
class Source(@ConfigProperty(name = "train.source.path") path: Optional<String>) {
    private val log = Logger.getLogger(Source::class.java)

    private val records: Map<String, SourceRecord> by lazy {
        val file = path.map { Paths.get(it) }.orElse(null)
        if (file == null || !file.isRegularFile()) {
            log.warn("Источник упражнений не найден (train.source.path) — поиск по датасету пустой; как подключить — docs/dataset.md")
            return@lazy emptyMap()
        }
        TrainJson.parseToJsonElement(file.readText()).jsonArray.map { parse(it.jsonObject) }
            .associateBy { it.info.id }
            .also { log.info("Источник: ${it.size} упражнений") }
    }

    operator fun get(id: String): SourceRecord? = records[id]

    /** Записей в датасете; 0 — не подключён. */
    val size: Int get() = records.size

    fun search(q: String?, limit: Int = 50): List<SourceExercise> {
        val words = q.orEmpty().lowercase().split(' ').filter { it.isNotBlank() }
        return records.values.asSequence()
            .filter { r -> words.all { it in r.haystack } }
            .take(limit).map { it.info }.toList()
    }

    private fun parse(o: JsonObject): SourceRecord {
        fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
        fun strings(e: JsonElement?) = (e as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
        return SourceRecord(
            info = SourceExercise(
                id = str("id")!!,
                name = str("name")!!,
                bodyPart = str("body_part"),
                target = str("target"),
                secondaryMuscles = strings(o["secondary_muscles"]),
                equipment = str("equipment"),
                // "images/0001-2gPfomN.jpg" → "0001-2gPfomN": одно имя для картинки и гифки
                mediaKey = str("image")?.substringAfterLast('/')?.substringBeforeLast('.'),
            ),
            muscleGroup = str("muscle_group"),
            stepsRu = strings((o["instruction_steps"] as? JsonObject)?.get("ru")),
        )
    }
}

@Path("$API_PREFIX/source/exercises")
class SourceResource(private val source: Source) {
    @GET
    fun search(@QueryParam("q") q: String?, @QueryParam("limit") limit: Int?): List<SourceExercise> =
        source.search(q, (limit ?: 50).coerceIn(1, 500))

    @GET
    @Path("{id}")
    fun get(@PathParam("id") id: String): SourceExercise = source[id]?.info ?: throw notFound()
}

/** Префикс `mediaKey` своей картинки упражнения (`PUT /api/exercises/{id}/media`). */
const val OWN_MEDIA_PREFIX = "own-"

/**
 * Медиа-пакет: `0001-2gPfomN.jpg` и `.gif` из датасета. Файлы ищутся в корне папки и в `images/`,
 * `videos/` — как лежат в датасете. Своя картинка `own-…` — один файл в хранилище фото: и на `.jpg`,
 * и на `.gif` отдаётся он, с типом по содержимому. Имена не меняются, поэтому кэш вечный.
 */
@Path("/media")
class MediaResource(
    @ConfigProperty(name = "train.media.path") private val dir: Optional<String>,
    private val files: PhotoFiles,
) {
    @GET
    @Path("{name}")
    fun get(@PathParam("name") name: String): Response {
        OWN.matchEntire(name)?.let { return own(it.groupValues[1]) }
        val m = NAME.matchEntire(name) ?: throw notFound()
        val root = dir.map { Paths.get(it) }.orElse(null) ?: throw notFound()
        val sub = if (m.groupValues[1] == "jpg") "images" else "videos"
        val file = listOf(root.resolve(name), root.resolve(sub).resolve(name)).firstOrNull { it.isRegularFile() }
            ?: throw notFound()
        return Response.ok(file.toFile(), if (m.groupValues[1] == "jpg") "image/jpeg" else "image/gif")
            .header("Cache-Control", "private, max-age=31536000, immutable")
            .header("Content-Length", Files.size(file))
            .build()
    }

    private fun own(key: String): Response {
        val file = runCatching { files.exerciseMedia(key) }.getOrNull()?.takeIf { it.isRegularFile() } ?: throw notFound()
        val type = file.inputStream().use { PhotoFiles.sniffMedia(it.readNBytes(16)) } ?: throw notFound()
        return Response.ok(file.toFile(), type)
            .header("Cache-Control", "private, max-age=31536000, immutable")
            .header("Content-Length", Files.size(file))
            .build()
    }

    private companion object {
        val NAME = Regex("""\d{4}-[A-Za-z0-9]+\.(jpg|gif)""")
        val OWN = Regex("""($OWN_MEDIA_PREFIX[a-z0-9]{12})\.(jpg|gif)""")
    }
}

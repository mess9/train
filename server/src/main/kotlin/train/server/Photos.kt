package train.server

import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.PATCH
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import kotlinx.datetime.LocalDate
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import train.core.API_PREFIX
import train.core.Photo
import train.core.PhotoPatch
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path as FsPath
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat
import java.util.Optional
import kotlin.io.path.deleteIfExists
import kotlin.io.path.isRegularFile
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Файлы фото на диске (ADR 0010): `<train.photos.path>/<user_id>/<photo_id>`, без расширения —
 * тип хранится в базе. Пишется во временный файл рядом и переносится целиком: оборванная
 * загрузка не оставляет половину фото. Не задан путь — API фото отвечает `503`.
 */
@ApplicationScoped
class PhotoFiles(
    @ConfigProperty(name = "train.photos.path") path: Optional<String>,
    @ConfigProperty(name = "train.photos.max-bytes", defaultValue = "15728640") val maxBytes: Long,
) {
    private val root: FsPath? = path.map { Paths.get(it).toAbsolutePath() }.orElse(null)

    private fun root(): FsPath = root ?: throw apiError(Response.Status.SERVICE_UNAVAILABLE, "хранилище фото не настроено (TRAIN_PHOTOS_PATH)")

    fun file(userId: Uuid, id: Uuid): FsPath = root().resolve(userId.toString()).resolve(id.toString())

    /** Принятый, но ещё не перенесённый файл: [type] определён по содержимому. */
    class Upload(val tmp: FsPath, val size: Long, val sha256: String, val type: String)

    /** Прочитать тело во временный файл: не больше [maxBytes], тип — по первым байтам. */
    fun receive(body: InputStream): Upload {
        val dir = root().resolve(".tmp").also { Files.createDirectories(it) }
        val tmp = Files.createTempFile(dir, "upload", "")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val head = ByteArray(HEAD)
            var headLen = 0
            var size = 0L
            Files.newOutputStream(tmp).use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = body.read(buf)
                    if (n < 0) break
                    size += n
                    if (size > maxBytes) throw apiError(Response.Status.REQUEST_ENTITY_TOO_LARGE, "фото больше ${maxBytes / (1024 * 1024)} МБ")
                    if (headLen < HEAD) {
                        val k = minOf(n, HEAD - headLen)
                        System.arraycopy(buf, 0, head, headLen, k)
                        headLen += k
                    }
                    digest.update(buf, 0, n)
                    out.write(buf, 0, n)
                }
            }
            if (size == 0L) throw badRequest("пустое тело: нужен файл фото")
            val type = sniff(head.copyOf(headLen))
                ?: throw apiError(Response.Status.UNSUPPORTED_MEDIA_TYPE, "не фото: принимаются JPEG, PNG, WebP, HEIC")
            return Upload(tmp, size, HexFormat.of().formatHex(digest.digest()), type)
        } catch (e: Throwable) {
            tmp.deleteIfExists()
            throw e
        }
    }

    fun store(u: Upload, userId: Uuid, id: Uuid) {
        val target = file(userId, id)
        Files.createDirectories(target.parent)
        Files.move(u.tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    fun discard(u: Upload) {
        u.tmp.deleteIfExists()
    }

    fun delete(userId: Uuid, id: Uuid) {
        runCatching { file(userId, id).deleteIfExists() }
            .onFailure { log.warn("не удалить файл фото $id: ${it.message}") }
    }

    companion object {
        private val log = Logger.getLogger(PhotoFiles::class.java)
        private const val HEAD = 16

        /** Тип по сигнатуре файла; `null` — не фото, которое мы принимаем. */
        fun sniff(b: ByteArray): String? {
            fun at(i: Int, vararg xs: Int) = b.size >= i + xs.size && xs.indices.all { (b[i + it].toInt() and 0xff) == xs[it] }
            fun ascii(i: Int, s: String) = b.size >= i + s.length && s.indices.all { b[i + it].toInt() == s[it].code }
            return when {
                at(0, 0xff, 0xd8, 0xff) -> "image/jpeg"
                at(0, 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) -> "image/png"
                ascii(0, "RIFF") && ascii(8, "WEBP") -> "image/webp"
                ascii(4, "ftyp") && listOf("heic", "heix", "hevc", "heim", "heis").any { ascii(8, it) } -> "image/heic"
                ascii(4, "ftyp") && listOf("mif1", "msf1").any { ascii(8, it) } -> "image/heif"
                else -> null
            }
        }
    }
}

/**
 * Фото прогресса (ADR 0010). Загрузка — `PUT` с id от клиента и файлом в теле: повтор после
 * обрыва связи с тем же файлом ничего не дублирует. Удаление — в корзину, насовсем — `trash purge`.
 */
@Path("$API_PREFIX/photos")
class PhotoResource(
    private val db: Db,
    private val user: CurrentUser,
    private val days: Days,
    private val files: PhotoFiles,
) {
    /** Фото не в корзине, новые сначала; `from`, `to` — по дню съёмки, `workout` — фото тренировки. */
    @GET
    fun list(@QueryParam("from") from: String?, @QueryParam("to") to: String?, @QueryParam("workout") workout: String?): List<Photo> {
        val start = parseDate(from)?.let(days::start)
        val end = parseDate(to)?.let(days::end)
        val workoutId = workout?.let(::parseIdParam)
        return db.list(
            "select * from photo where user_id = ? and deleted_at is null " +
                "and (cast(? as timestamptz) is null or taken_at >= ?) and (cast(? as timestamptz) is null or taken_at < ?) " +
                "and (cast(? as uuid) is null or workout_id = ?) order by taken_at desc",
            user.id, start, start, end, end, workoutId, workoutId,
        ) { it.photo() }
    }

    @GET
    @Path("{id}")
    fun get(@PathParam("id") id: String): Photo = find(parseId(id)) ?: throw notFound()

    /**
     * Загрузить фото: тело — файл (JPEG, PNG, WebP, HEIC; тип — по содержимому). `takenAt` — когда
     * снято: ISO 8601 или `YYYY-MM-DD` (полдень того дня), по умолчанию сейчас. Тот же id с тем же
     * файлом — `200` без изменений, с другим — `409`.
     */
    @PUT
    @Path("{id}")
    @Consumes(MediaType.WILDCARD)
    @Transactional
    fun upload(
        @PathParam("id") id: String,
        @QueryParam("takenAt") takenAt: String?,
        @QueryParam("pose") pose: String?,
        @QueryParam("note") note: String?,
        @QueryParam("workoutId") workoutId: String?,
        body: InputStream,
    ): Photo {
        val photoId = parseId(id)
        val at = parseTakenAt(takenAt) ?: Clock.System.now()
        // Сначала файл, потом база: соединение не держится, пока идёт загрузка
        val upload = files.receive(body)
        try {
            val workout = workoutId?.let(::parseIdParam)?.let(::ownWorkout)
            val existing = db.one("select * from photo where id = ? for update", photoId) { it.uuidReq("user_id") to it.photo() }
            if (existing != null) {
                val (owner, photo) = existing
                if (owner != user.id) throw notFound()
                if (photo.sha256 != upload.sha256) throw apiError(Response.Status.CONFLICT, "под id $photoId уже другое фото")
                return photo
            }
            db.update(
                "insert into photo (id, user_id, taken_at, content_type, size, sha256, pose, note, workout_id) values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                photoId, user.id, at, upload.type, upload.size, upload.sha256, pose.clean(), note.clean(), workout,
            )
            files.store(upload, user.id, photoId)
        } finally {
            files.discard(upload)
        }
        return find(photoId)!!
    }

    /** Файл фото. Содержимое под id не меняется, поэтому кэш вечный. */
    @GET
    @Path("{id}/file")
    @Produces(MediaType.WILDCARD)
    fun file(@PathParam("id") id: String): Response {
        val photo = find(parseId(id)) ?: throw notFound()
        val file = files.file(user.id, photo.id).takeIf { it.isRegularFile() } ?: throw notFound("файл фото не найден")
        return Response.ok(file.toFile(), photo.contentType)
            .header("Cache-Control", "private, max-age=31536000, immutable")
            .header("ETag", "\"${photo.sha256}\"")
            .header("Content-Length", photo.size)
            .build()
    }

    /** Поменять дату съёмки (задним числом), ракурс, заметку, тренировку. */
    @PATCH
    @Path("{id}")
    @Transactional
    fun patch(@PathParam("id") id: String, body: PhotoPatch): Photo {
        val old = find(parseId(id), forUpdate = true) ?: throw notFound()
        val p = old.copy(
            takenAt = body.takenAt ?: old.takenAt,
            pose = if (body.pose != null) body.pose.clean() else old.pose,
            note = if (body.note != null) body.note.clean() else old.note,
            workoutId = body.workoutId?.let(::ownWorkout) ?: old.workoutId,
        )
        db.update(
            "update photo set taken_at = ?, pose = ?, note = ?, workout_id = ? where id = ?",
            p.takenAt, p.pose, p.note, p.workoutId, p.id,
        )
        return find(p.id)!!
    }

    /** В корзину; повторное удаление ничего не меняет. */
    @DELETE
    @Path("{id}")
    @Transactional
    fun delete(@PathParam("id") id: String) {
        val n = db.update("update photo set deleted_at = coalesce(deleted_at, now()) where id = ? and user_id = ?", parseId(id), user.id)
        if (n == 0) throw notFound()
    }

    /** Вернуть из корзины. */
    @POST
    @Path("{id}/restore")
    @Transactional
    fun restore(@PathParam("id") id: String): Photo {
        val photoId = parseId(id)
        if (db.update("update photo set deleted_at = null where id = ? and user_id = ?", photoId, user.id) == 0) throw notFound()
        return find(photoId)!!
    }

    private fun find(id: Uuid, forUpdate: Boolean = false): Photo? =
        db.one("select * from photo where id = ? and user_id = ?${if (forUpdate) " for update" else ""}", id, user.id) { it.photo() }

    /** Ссылка на чужую или несуществующую тренировку — ошибка запроса. */
    private fun ownWorkout(id: Uuid): Uuid =
        id.takeIf { db.one("select 1 from workout where id = ? and user_id = ?", it, user.id) { true } != null }
            ?: throw badRequest("нет тренировки $id")

    private fun parseTakenAt(s: String?): Instant? = s?.takeIf { it.isNotBlank() }?.let {
        runCatching { Instant.parse(it) }.getOrNull()
            ?: runCatching { LocalDate.parse(it).atTime(12, 0).toInstant(days.zone) }.getOrNull()
            ?: throw badRequest("takenAt — ISO 8601 или YYYY-MM-DD: $it")
    }
}

private fun String?.clean(): String? = this?.trim()?.ifEmpty { null }

fun Row.photo() = Photo(
    id = uuidReq("id"),
    takenAt = instantReq("taken_at"),
    contentType = string("content_type")!!,
    size = long("size")!!,
    sha256 = string("sha256")!!,
    pose = string("pose"),
    note = string("note"),
    workoutId = uuid("workout_id"),
    createdAt = instantReq("created_at"),
    deletedAt = instant("deleted_at"),
)

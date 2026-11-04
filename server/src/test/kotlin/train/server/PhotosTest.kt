package train.server

import io.restassured.response.Response
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import train.core.ApiError
import train.core.Me
import train.core.Photo
import train.core.PhotoPatch
import train.core.Purged
import train.core.Trash
import train.core.newId
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.io.path.exists
import kotlin.random.Random
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Фото прогресса (ADR 0010): файл на диске, описание в базе, удаление через корзину. */
@QuarkusTest
class PhotosTest {
    @Inject
    lateinit var users: Users

    @Inject
    lateinit var files: PhotoFiles

    private fun jpeg(size: Int = 2048) = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte()) + Random.nextBytes(size)

    private fun png() = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) + Random.nextBytes(512)

    private fun TestApi.upload(id: Uuid, bytes: ByteArray, vararg query: Pair<String, String>, type: String = "image/jpeg"): Response =
        spec().contentType(type).queryParams(query.toMap()).body(bytes).put("/api/photos/$id")

    private inline fun <reified T> TestApi.uploadOk(id: Uuid, bytes: ByteArray, vararg query: Pair<String, String>, status: Int = 200): T =
        upload(id, bytes, *query).decode(status, "/api/photos/$id")

    private fun sha(b: ByteArray) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b))

    @Test
    fun uploadGetFileAndList() {
        val api = users.newApi()
        val bench = api.exercise()
        val w = api.startFrom(api.plan(bench), bench)
        val bytes = jpeg()
        val id = newId()

        val p = api.uploadOk<Photo>(id, bytes, "takenAt" to "2026-10-01T09:30:00Z", "pose" to " front ", "note" to "утро", "workoutId" to w.id.toString())
        assertEquals(id, p.id)
        assertEquals(Instant.parse("2026-10-01T09:30:00Z"), p.takenAt)
        assertEquals("image/jpeg", p.contentType)
        assertEquals(bytes.size.toLong(), p.size)
        assertEquals(sha(bytes), p.sha256)
        assertEquals("front", p.pose)
        assertEquals("утро", p.note)
        assertEquals(w.id, p.workoutId)
        assertNull(p.deletedAt)
        assertEquals(p, api.get<Photo>("/api/photos/$id"))

        val file = api.spec().get("/api/photos/$id/file")
        assertEquals(200, file.statusCode)
        assertEquals("image/jpeg", file.contentType)
        assertTrue(file.header("Cache-Control").contains("immutable"))
        assertEquals("\"${p.sha256}\"", file.header("ETag"))
        assertArrayEquals(bytes, file.body.asByteArray())

        // Тип — по содержимому, не по заголовку
        val png = api.uploadOk<Photo>(newId(), png(), "takenAt" to "2026-09-01")
        assertEquals("image/png", png.contentType)

        assertEquals(listOf(id, png.id), api.get<List<Photo>>("/api/photos").map { it.id })
        assertEquals(listOf(id), api.get<List<Photo>>("/api/photos?from=2026-10-01&to=2026-10-01").map { it.id })
        assertEquals(listOf(png.id), api.get<List<Photo>>("/api/photos?to=2026-09-30").map { it.id })
        assertEquals(listOf(id), api.get<List<Photo>>("/api/photos?workout=${w.id}").map { it.id })
    }

    /** Повтор после обрыва связи — тот же ответ; под тем же id другое фото не записать. */
    @Test
    fun uploadIsIdempotent() {
        val api = users.newApi()
        val bytes = jpeg()
        val id = newId()
        val first = api.uploadOk<Photo>(id, bytes)
        assertEquals(first, api.uploadOk<Photo>(id, bytes))
        api.uploadOk<ApiError>(id, jpeg(), status = 409)
        assertEquals(first, api.get<Photo>("/api/photos/$id"))
        assertEquals(1, api.get<List<Photo>>("/api/photos").size)
    }

    @Test
    fun rejectsWhatIsNotAPhoto() {
        val api = users.newApi()
        assertEquals(415, api.upload(newId(), "%PDF-1.7 не фото".toByteArray(), type = "image/jpeg").statusCode)
        assertEquals(400, api.upload(newId(), ByteArray(0)).statusCode)
        // Предел в тестах — 1 МБ (%test.train.photos.max-bytes)
        assertEquals(413, api.upload(newId(), jpeg(1024 * 1024 + 1)).statusCode)
        assertEquals(200, api.upload(newId(), jpeg(1000 * 1000)).statusCode)
        assertEquals(400, api.upload(newId(), jpeg(), "takenAt" to "вчера").statusCode)
        assertEquals(400, api.upload(newId(), jpeg(), "workoutId" to newId().toString()).statusCode)
        assertEquals(1, api.get<List<Photo>>("/api/photos").size)
    }

    @Test
    fun patchBackdatesAndLinks() {
        val api = users.newApi()
        val bench = api.exercise()
        val w = api.startFrom(api.plan(bench), bench)
        val p = api.uploadOk<Photo>(newId(), jpeg(), "pose" to "side", "note" to "x")

        val moved = api.spec().body("""{"takenAt":"2025-12-31T18:00:00Z","note":"","workoutId":"${w.id}"}""").patch("/api/photos/${p.id}")
            .let { with(api) { it.decode<Photo>(200, "patch") } }
        assertEquals(Instant.parse("2025-12-31T18:00:00Z"), moved.takenAt)
        assertEquals("side", moved.pose)
        assertNull(moved.note) // пустая строка стирает
        assertEquals(w.id, moved.workoutId)
        assertEquals(p.sha256, moved.sha256)

        val other = users.newApi()
        val foreign = other.startFrom(other.plan(other.exercise()), other.exercise())
        assertEquals(400, api.spec().body(train.core.TrainJson.encodeToString(PhotoPatch.serializer(), PhotoPatch(workoutId = foreign.id))).patch("/api/photos/${p.id}").statusCode)
    }

    @Test
    fun deleteGoesToTrashThenPurgeRemovesFile() {
        val api = users.newApi()
        val me = api.get<Me>("/api/me")
        val keep = api.uploadOk<Photo>(newId(), jpeg())
        val p = api.uploadOk<Photo>(newId(), jpeg())
        val file = files.file(me.userId, p.id)
        assertTrue(file.exists())

        api.spec().delete("/api/photos/${p.id}").then().statusCode(204)
        api.spec().delete("/api/photos/${p.id}").then().statusCode(204)
        assertEquals(listOf(keep.id), api.get<List<Photo>>("/api/photos").map { it.id })
        assertNotNull(api.get<Photo>("/api/photos/${p.id}").deletedAt)
        assertEquals(listOf(p.id), api.get<Trash>("/api/trash").photos.map { it.id })
        assertTrue(file.exists(), "в корзине файл ещё на месте")

        val restored = api.post<_, Photo>("/api/photos/${p.id}/restore", Unit)
        assertNull(restored.deletedAt)
        assertTrue(api.get<Trash>("/api/trash").photos.isEmpty())

        api.spec().delete("/api/photos/${p.id}").then().statusCode(204)
        assertEquals(Purged(plans = 0, workouts = 0, measurements = 0, photos = 1), api.post<_, Purged>("/api/trash/purge", Unit))
        assertFalse(file.exists())
        assertEquals(404, api.status("/api/photos/${p.id}"))
        assertEquals(404, api.status("/api/photos/${p.id}/file"))
        assertEquals(200, api.status("/api/photos/${keep.id}/file"))
    }

    @Test
    fun foreignPhotoIsInvisible() {
        val a = users.newApi()
        val b = users.newApi()
        val bytes = jpeg()
        val p = a.uploadOk<Photo>(newId(), bytes)

        assertTrue(b.get<List<Photo>>("/api/photos").isEmpty())
        assertEquals(404, b.status("/api/photos/${p.id}"))
        assertEquals(404, b.status("/api/photos/${p.id}/file"))
        assertEquals(404, b.spec().body("{}").patch("/api/photos/${p.id}").statusCode)
        assertEquals(404, b.spec().delete("/api/photos/${p.id}").statusCode)
        assertEquals(404, b.spec().post("/api/photos/${p.id}/restore").statusCode)
        // Чужой id не занять, даже тем же файлом
        assertEquals(404, b.upload(p.id, bytes).statusCode)
        assertEquals(0, b.post<_, Purged>("/api/trash/purge", Unit).photos)
        assertEquals(p, a.get<Photo>("/api/photos/${p.id}"))
        assertEquals(401, TestApi(null).status("/api/photos/${p.id}/file"))
    }
}

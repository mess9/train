package train.server

import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.context.RequestScoped
import jakarta.enterprise.event.Observes
import jakarta.transaction.Transactional
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import org.jboss.resteasy.reactive.server.ServerRequestFilter
import train.core.API_KEY_HEADER
import train.core.API_PREFIX
import train.core.ApiKey
import train.core.KeyCreate
import train.core.KeyCreated
import train.core.Me
import train.core.newId
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.HexFormat
import java.util.Optional
import kotlin.uuid.Uuid

/** Владелец ключа текущего запроса. Все запросы к данным фильтруются по [id]. */
@RequestScoped
class CurrentUser {
    var me: Me? = null
    var keyId: Uuid? = null

    val id: Uuid get() = me?.userId ?: throw apiError(Response.Status.UNAUTHORIZED, "нет ключа")
}

object Keys {
    private val random = SecureRandom()

    fun generate(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))

    /** Ключи случайные и длинные — соль и медленный хеш не нужны. */
    fun hash(key: String): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.toByteArray()))
}

@ApplicationScoped
class Users(
    private val db: Db,
    @ConfigProperty(name = "train.bootstrap.email") private val bootstrapEmail: Optional<String>,
    @ConfigProperty(name = "train.bootstrap.key") private val bootstrapKey: Optional<String>,
) {
    private val log = Logger.getLogger(Users::class.java)

    /** Владелец действующего ключа и ID ключа. */
    fun byKey(key: String): Pair<Me, Uuid>? = db.one(
        "select u.id, u.email, u.name, k.id as key_id from api_key k join app_user u on u.id = k.user_id " +
            "where k.key_hash = ? and k.revoked_at is null",
        Keys.hash(key),
    ) { Me(it.uuidReq("id"), it.string("email")!!, it.string("name")) to it.uuidReq("key_id") }

    /** Пользователь с этой почтой — найти или создать. */
    @Transactional
    fun ensure(email: String, name: String? = null): Uuid =
        db.one("select id from app_user where email = ?", email) { it.uuidReq("id") }
            ?: newId().also { db.update("insert into app_user (id, email, name) values (?, ?, ?)", it, email, name) }

    @Transactional
    fun addKey(userId: Uuid, label: String?, key: String = Keys.generate()): KeyCreated {
        val id = newId()
        db.update("insert into api_key (id, user_id, key_hash, label) values (?, ?, ?, ?)", id, userId, Keys.hash(key), label)
        return KeyCreated(id, key, label)
    }

    fun keys(userId: Uuid, currentKey: Uuid?): List<ApiKey> = db.list(
        "select id, label, created_at, revoked_at from api_key where user_id = ? order by created_at",
        userId,
    ) { ApiKey(it.uuidReq("id"), it.string("label"), it.instantReq("created_at"), it.instant("revoked_at"), it.uuid("id") == currentKey) }

    /** Отозвать свой ключ; повторный отзыв ничего не меняет. `false` — такого ключа у пользователя нет. */
    @Transactional
    fun revoke(userId: Uuid, keyId: Uuid): Boolean =
        db.update("update api_key set revoked_at = coalesce(revoked_at, now()) where id = ? and user_id = ?", keyId, userId) > 0

    /**
     * Первый пользователь и ключ — из окружения: `TRAIN_BOOTSTRAP_EMAIL` и `TRAIN_BOOTSTRAP_KEY`.
     * Повторный старт ничего не дублирует.
     */
    @Transactional
    fun onStart(@Observes event: StartupEvent) {
        val email = bootstrapEmail.orElse(null) ?: return
        val key = bootstrapKey.orElse(null) ?: return log.warn("train.bootstrap.email задан без train.bootstrap.key — пропускаю")
        val userId = ensure(email)
        // Отозванный bootstrap-ключ не возвращается: хеш уникален, нужен новый ключ в окружении
        if (db.one("select 1 from api_key where key_hash = ?", Keys.hash(key)) { true } == null) addKey(userId, "bootstrap", key)
        log.info("Пользователь $email готов")
    }
}

/** Ключ из `X-Api-Key` (ADR 0008). Открыты только `/api/info` и оболочка приложения. */
class AuthFilter(private val users: Users, private val current: CurrentUser) {
    @ServerRequestFilter
    fun auth(ctx: ContainerRequestContext): Response? {
        val path = ctx.uriInfo.path
        if (!(path.startsWith("$API_PREFIX/") || path.startsWith("/media/")) || path == "$API_PREFIX/info") return null
        val key = ctx.getHeaderString(API_KEY_HEADER) ?: return apiError(Response.Status.UNAUTHORIZED, "нет ключа").response
        val (me, keyId) = users.byKey(key) ?: return apiError(Response.Status.UNAUTHORIZED, "ключ не принят").response
        current.me = me
        current.keyId = keyId
        return null
    }
}

@Path(API_PREFIX)
class MeResource(private val user: CurrentUser, private val users: Users) {
    @GET
    @Path("me")
    fun me(): Me = user.me!!

    /** Новый ключ того же пользователя — например, для телефона. */
    @POST
    @Path("keys")
    fun createKey(body: KeyCreate?): KeyCreated = users.addKey(user.id, body?.label)

    @GET
    @Path("keys")
    fun keys(): List<ApiKey> = users.keys(user.id, user.keyId)

    /** Отозвать ключ — например, потерянного телефона. Можно и текущий: дальше он получит `401`. */
    @DELETE
    @Path("keys/{id}")
    fun revoke(@PathParam("id") id: String) {
        if (!users.revoke(user.id, parseId(id))) throw notFound()
    }
}

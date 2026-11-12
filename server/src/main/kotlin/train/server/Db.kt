package train.server

import jakarta.enterprise.context.ApplicationScoped
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toKotlinLocalDate
import kotlinx.serialization.KSerializer
import train.core.TrainJson
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import kotlin.time.toKotlinInstant
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid

/**
 * Тонкая обёртка над JDBC. Внутри `@Transactional` все вызовы идут через одно соединение
 * транзакции, поэтому `for update` держит строку до конца метода.
 *
 * jsonb передаётся строкой: в SQL — `cast(? as jsonb)`, значение — [Json].
 */
@ApplicationScoped
class Db(private val ds: DataSource) {
    fun <T> list(sql: String, vararg args: Any?, row: (Row) -> T): List<T> =
        ds.connection.use { c ->
            c.prepareStatement(sql).use { st ->
                bind(st, args)
                st.executeQuery().use { rs -> buildList { while (rs.next()) add(row(Row(rs))) } }
            }
        }

    fun <T> one(sql: String, vararg args: Any?, row: (Row) -> T): T? = list(sql, *args, row = row).firstOrNull()

    fun update(sql: String, vararg args: Any?): Int =
        ds.connection.use { c -> c.prepareStatement(sql).use { st -> bind(st, args); st.executeUpdate() } }

    private fun bind(st: PreparedStatement, args: Array<out Any?>) {
        args.forEachIndexed { i, a ->
            val n = i + 1
            when (a) {
                null -> st.setObject(n, null)
                is Uuid -> st.setObject(n, a.toJavaUuid())
                is Instant -> st.setObject(n, OffsetDateTime.ofInstant(a.toJavaInstant(), ZoneOffset.UTC))
                is kotlinx.datetime.LocalDate -> st.setObject(n, a.toJavaLocalDate())
                is Enum<*> -> st.setString(n, a.name)
                is Json -> st.setString(n, a.text)
                is UuidList -> st.setArray(n, st.connection.createArrayOf("uuid", a.ids.map { it.toJavaUuid() }.toTypedArray()))
                else -> st.setObject(n, a)
            }
        }
    }
}

/** Значение для `cast(? as jsonb)`. */
class Json(val text: String) {
    companion object {
        fun <T> of(serializer: KSerializer<T>, value: T) = Json(TrainJson.encodeToString(serializer, value))
    }
}

/** Значение для `= any(?)`. */
class UuidList(val ids: Collection<Uuid>)

class Row(private val rs: ResultSet) {
    fun uuid(c: String): Uuid? = rs.getObject(c, UUID::class.java)?.toKotlinUuid()
    fun string(c: String): String? = rs.getString(c)
    fun int(c: String): Int? = rs.getInt(c).takeUnless { rs.wasNull() }
    fun long(c: String): Long? = rs.getLong(c).takeUnless { rs.wasNull() }
    fun double(c: String): Double? = rs.getDouble(c).takeUnless { rs.wasNull() }
    fun bool(c: String): Boolean = rs.getBoolean(c)
    fun instant(c: String): Instant? = rs.getObject(c, OffsetDateTime::class.java)?.toInstant()?.toKotlinInstant()
    fun date(c: String): kotlinx.datetime.LocalDate? = rs.getObject(c, java.time.LocalDate::class.java)?.toKotlinLocalDate()
    fun uuids(c: String): List<Uuid> = (rs.getArray(c)?.array as Array<*>?).orEmpty().map { (it as UUID).toKotlinUuid() }
    fun <T> json(c: String, serializer: KSerializer<T>): T = TrainJson.decodeFromString(serializer, rs.getString(c))
    inline fun <reified E : Enum<E>> enum(c: String): E? = string(c)?.let { enumValueOf<E>(it) }

    fun uuidReq(c: String) = uuid(c)!!
    fun instantReq(c: String) = instant(c)!!
}

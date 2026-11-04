package train.server

import io.quarkus.test.junit.QuarkusTest
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDate
import java.util.UUID

/**
 * V2 на базе, где уже есть данные V1, как на проде: схема поднимается до V1, заполняется и
 * домигрируется. Пояс сессии — Asia/Yerevan (+04:00, без летнего времени), как TZ сервера.
 */
@QuarkusTest
class MigrationV2Test {
    @ConfigProperty(name = "quarkus.datasource.jdbc.url")
    lateinit var url: String

    @ConfigProperty(name = "quarkus.datasource.username")
    lateinit var user: String

    @ConfigProperty(name = "quarkus.datasource.password")
    lateinit var password: String

    private val schema = "v2_check_" + UUID.randomUUID().toString().take(8)

    @AfterEach
    fun dropSchema() {
        sql { it.createStatement().execute("drop schema if exists $schema cascade") }
    }

    @Test
    fun singleOwnerBecomesAdminAndCatalogShared() {
        migrate("1")
        val owner = UUID.randomUUID()
        val bench = UUID.randomUUID()
        val late = UUID.randomUUID()
        val evening = UUID.randomUUID()
        sql { c ->
            c.insertUser(owner, "owner@test.local")
            c.insertExercise(bench, owner)
            c.insertWorkout(late, owner, "2026-03-10T21:30:00Z") // в Ереване 11 марта, 01:30
            c.insertWorkout(evening, owner, "2026-03-10T19:30:00Z") // в Ереване 10 марта, 23:30
        }

        migrate(null)

        sql { c ->
            assertEquals(true, c.value("select admin from app_user where id = ?", owner))
            assertNull(c.value("select owner_user_id from exercise where id = ?", bench), "справочник владельца — общий")
            assertEquals(LocalDate.parse("2026-03-11"), c.date(late), "день — по Еревану, не по UTC")
            assertEquals(LocalDate.parse("2026-03-10"), c.date(evening))
            assertNull(c.value("select utc_offset_sec from workout where id = ?", late), "смещения у старых записей нет")
            assertEquals(2L, c.value("select count(*) from workout where user_id = ?", owner), "тренировки не потерялись")
        }
    }

    @Test
    fun withTwoUsersNobodyIsAdminAndCatalogsStayOwn() {
        migrate("1")
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        val firstBench = UUID.randomUUID()
        val secondBench = UUID.randomUUID()
        val workout = UUID.randomUUID()
        sql { c ->
            c.insertUser(first, "first@test.local")
            c.insertUser(second, "second@test.local")
            c.insertExercise(firstBench, first)
            c.insertExercise(secondBench, second)
            c.insertWorkout(workout, second, "2026-01-01T20:00:00Z") // в Ереване 2 января, 00:00
        }

        migrate(null)

        sql { c ->
            assertEquals(0L, c.value("select count(*) from app_user where admin"))
            assertEquals(first, c.value("select owner_user_id from exercise where id = ?", firstBench))
            assertEquals(second, c.value("select owner_user_id from exercise where id = ?", secondBench))
            assertEquals(LocalDate.parse("2026-01-02"), c.date(workout))
        }
    }

    @Test
    fun emptyBaseMigrates() {
        migrate("1")
        migrate(null)
        sql { c ->
            assertEquals(0L, c.value("select count(*) from workout"))
            assertEquals(1L, c.value("select count(*) from information_schema.tables where table_schema = ? and table_name = 'photo'", schema))
        }
    }

    @Test
    fun startDateIsRequiredAfterV2() {
        migrate(null)
        val owner = UUID.randomUUID()
        sql { c ->
            c.insertUser(owner, "owner@test.local")
            val failed = runCatching { c.insertWorkout(UUID.randomUUID(), owner, "2026-03-10T10:00:00Z") }
            assertFalse(failed.isSuccess, "без start_date запись не проходит")
        }
    }

    /** Flyway без пула: пояс сессии не утекает в соединения приложения. null — до последней версии. */
    private fun migrate(target: String?) {
        Flyway.configure()
            .dataSource(url, user, password)
            .schemas(schema)
            .createSchemas(true)
            .locations("classpath:db/migration")
            .initSql("set time zone 'Asia/Yerevan'")
            .apply { if (target != null) target(target) }
            .load()
            .migrate()
    }

    private fun <T> sql(block: (Connection) -> T): T = DriverManager.getConnection(url, user, password).use { c ->
        c.createStatement().execute("set search_path to $schema; set time zone 'Asia/Yerevan'")
        block(c)
    }

    private fun Connection.exec(sql: String, vararg args: Any) {
        prepareStatement(sql).use { st ->
            args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
            st.executeUpdate()
        }
    }

    private fun Connection.value(sql: String, vararg args: Any): Any? = prepareStatement(sql).use { st ->
        args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
        st.executeQuery().use { rs -> check(rs.next()) { "нет строки: $sql" }; rs.getObject(1) }
    }

    private fun Connection.date(workout: UUID): LocalDate =
        (value("select start_date from workout where id = ?", workout) as java.sql.Date).toLocalDate()

    private fun Connection.insertUser(id: UUID, email: String) =
        exec("insert into app_user (id, email) values (?, ?)", id, email)

    private fun Connection.insertExercise(id: UUID, owner: UUID) =
        exec("insert into exercise (id, owner_user_id, name_ru, record_type) values (?, ?, 'Жим лёжа', 'weight_reps')", id, owner)

    private fun Connection.insertWorkout(id: UUID, owner: UUID, startedAt: String) = exec(
        """insert into workout (id, user_id, started_at, layout, received_at, rev, op_id)
           values (?, ?, ?::timestamptz, '{"blocks": []}'::jsonb, now(), 1, ?)""",
        id, owner, startedAt, UUID.randomUUID(),
    )
}

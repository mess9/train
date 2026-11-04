package train.server

import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import train.core.ApiError
import train.core.Exercise
import train.core.ExerciseCreate
import train.core.Me
import train.core.Plan
import train.core.User
import train.core.UserCreate
import train.core.UserCreated
import train.core.newId

/** Админ заводит пользователей и ведёт общий справочник (ADR 0011). */
@QuarkusTest
class AdminTest {
    @Inject
    lateinit var users: Users

    @Inject
    lateinit var db: Db

    @Test
    fun adminCreatesUserWithFirstKey() {
        val admin = users.newAdmin(db)
        assertTrue(admin.get<Me>("/api/me").admin)

        val email = "Friend-${newId()}@Test.Local "
        val created = admin.post<_, UserCreated>("/api/admin/users", UserCreate(email, name = "Друг", keyLabel = "телефон"))
        assertEquals(email.trim().lowercase(), created.user.email)
        assertEquals("Друг", created.user.name)
        assertFalse(created.user.admin)
        assertEquals("телефон", created.key.label)

        val friend = TestApi(created.key.key)
        val me = friend.get<Me>("/api/me")
        assertEquals(created.user.id, me.userId)
        assertFalse(me.admin)
        assertTrue(admin.get<List<User>>("/api/admin/users").any { it.id == created.user.id })

        // Повтор той же почты не заводит второго и не выдаёт новый ключ
        admin.post<_, ApiError>("/api/admin/users", UserCreate(email.uppercase()), status = 409)
        admin.post<_, ApiError>("/api/admin/users", UserCreate("без собаки"), status = 400)
    }

    @Test
    fun onlyAdminManagesUsers() {
        val api = users.newApi()
        assertFalse(api.get<Me>("/api/me").admin)
        assertEquals(403, api.status("/api/admin/users"))
        api.post<_, ApiError>("/api/admin/users", UserCreate("${newId()}@test"), status = 403)
        assertEquals(401, TestApi(null).status("/api/admin/users"))
    }

    @Test
    fun sharedCatalog() {
        val admin = users.newAdmin(db)
        val a = users.newApi()
        val b = users.newApi()

        // У админа новое упражнение по умолчанию — в общий справочник
        val shared = admin.post<_, Exercise>("/api/exercises", ExerciseCreate(nameRu = "Общий жим ${newId()}", sourceId = "0025"))
        assertTrue(shared.shared)
        val own = admin.post<_, Exercise>("/api/exercises", ExerciseCreate(nameRu = "Своё админа ${newId()}", shared = false))
        assertFalse(own.shared)

        // Общее видят все и могут взять в план
        assertTrue(a.get<Exercise>("/api/exercises/${shared.id}").shared)
        assertTrue(b.get<List<Exercise>>("/api/exercises").any { it.id == shared.id })
        assertEquals(404, a.status("/api/exercises/${own.id}"))
        a.put<_, Plan>("/api/plans/${newId()}", TestApi.planPut(shared))

        // Своё — только у себя
        val mine = a.exercise(nameRu = "Своё ${newId()}")
        assertFalse(mine.shared)
        assertEquals(404, b.status("/api/exercises/${mine.id}"))
        assertEquals(404, admin.status("/api/exercises/${mine.id}"))
        a.post<_, ApiError>("/api/exercises", ExerciseCreate(nameRu = "В общий без прав", shared = true), status = 403)

        // Общее правит только админ, своё — владелец
        a.spec().body("""{"nameRu":"чужая правка"}""").patch("/api/exercises/${shared.id}").then().statusCode(403)
        val renamed = admin.spec().body("""{"nameRu":"Жим общий"}""").patch("/api/exercises/${shared.id}")
            .let { with(admin) { it.decode<Exercise>(200, "patch") } }
        assertEquals("Жим общий", renamed.nameRu)
        assertTrue(renamed.shared)
        a.spec().body("""{"nameRu":"Моё"}""").patch("/api/exercises/${mine.id}").then().statusCode(200)
        b.spec().body("""{"nameRu":"чужое"}""").patch("/api/exercises/${mine.id}").then().statusCode(404)
        assertEquals("Жим общий", b.get<Exercise>("/api/exercises/${shared.id}").nameRu)
    }
}

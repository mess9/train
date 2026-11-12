package train.server

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import org.hamcrest.CoreMatchers.containsString
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertNotNull
import train.core.TRAIN_VERSION

@QuarkusTest
class InfoResourceTest {
    @Test
    fun info() {
        given().get("/api/info").then()
            .statusCode(200)
            .body("name", equalTo("train"))
            // Вне сборки образа коммита нет: версия — 0.2.0+dev, номера сборки нет
            .body("version", equalTo("$TRAIN_VERSION+dev"))
            .body("build", nullValue())
            // Тестовый датасет — две записи и папка с картинкой
            .body("dataset.exercises", equalTo(2))
            .body("dataset.media", equalTo(true))
    }

    /** По `app` приложение узнаёт, что на сервере уже другая сборка. */
    @Test
    fun infoNamesServedApp() {
        val hash = appJs()!!.removePrefix("app.").removeSuffix(".js")
        given().get("/api/info").then().body("app", equalTo(hash))
    }

    /** `app.js` в сборке с хешем в имени (`server:appDist`) — берём его из `index.html`. */
    private fun appJs() = given().get("/").then().statusCode(200).extract().asString()
        .let { Regex("""app\.[0-9a-f]{12}\.js""").find(it)?.value }

    @Test
    fun appIsServed() {
        val js = appJs()
        assertNotNull(js, "в index.html нет app.<хеш>.js")
        given().get("/$js").then().statusCode(200)
        given().get("/sw.js").then()
            .statusCode(200)
            .body(containsString(js))
    }

    /** Без хеша в имени — только с проверкой у сервера, иначе Cloudflare держит старую сборку. */
    @Test
    fun unhashedFilesAreNotCached() {
        for (path in listOf("/", "/index.html", "/sw.js", "/manifest.webmanifest")) {
            given().get(path).then().statusCode(200).header("Cache-Control", equalTo("no-cache"))
        }
        given().get("/${appJs()}").then().header("Cache-Control", containsString("immutable"))
    }

    @Test
    fun wasmIsCompressed() {
        val wasm = given().get("/${appJs()}").then().statusCode(200).extract().asString()
            .let { Regex("""[0-9a-f]{20}\.wasm""").find(it)!!.value }
        given().header("Accept-Encoding", "gzip").get("/$wasm").then()
            .statusCode(200)
            .header("Content-Type", containsString("application/wasm"))
            .header("Content-Encoding", "gzip")
    }
}

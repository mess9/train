package train.server

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import org.hamcrest.CoreMatchers.containsString
import org.hamcrest.CoreMatchers.equalTo
import org.junit.jupiter.api.Test

@QuarkusTest
class InfoResourceTest {
    @Test
    fun info() {
        given().get("/api/info").then()
            .statusCode(200)
            .body("name", equalTo("train"))
    }

    @Test
    fun appIsServed() {
        given().get("/").then()
            .statusCode(200)
            .body(containsString("app.js"))
    }

    @Test
    fun wasmIsCompressed() {
        val wasm = given().get("/app.js").then().statusCode(200).extract().asString()
            .let { Regex("""[0-9a-f]{20}\.wasm""").find(it)!!.value }
        given().header("Accept-Encoding", "gzip").get("/$wasm").then()
            .statusCode(200)
            .header("Content-Type", containsString("application/wasm"))
            .header("Content-Encoding", "gzip")
    }
}

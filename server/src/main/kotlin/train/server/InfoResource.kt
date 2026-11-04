package train.server

import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.config.inject.ConfigProperty
import train.core.API_PREFIX
import train.core.ServerInfo

@Path("$API_PREFIX/info")
class InfoResource(
    @ConfigProperty(name = "quarkus.application.version") private val version: String,
    @ConfigProperty(name = "train.build", defaultValue = "dev") private val build: String,
) {
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    fun info() = ServerInfo(name = "train", version = version, build = build, app = APP)

    private companion object {
        /** Хеш из `app.<хеш>.js` в отдаваемом index.html (`server:appDist`); без сборки приложения — null. */
        val APP: String? = InfoResource::class.java.getResource("/META-INF/resources/index.html")
            ?.readText()?.let { Regex("""app\.([0-9a-f]{12})\.js""").find(it)?.groupValues?.get(1) }
    }
}

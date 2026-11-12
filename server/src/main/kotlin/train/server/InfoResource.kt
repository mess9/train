package train.server

import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.config.inject.ConfigProperty
import train.core.API_PREFIX
import train.core.DatasetInfo
import train.core.ServerInfo
import train.core.TRAIN_VERSION
import train.core.fullVersion
import java.nio.file.Paths
import java.util.Optional
import kotlin.io.path.isDirectory

@Path("$API_PREFIX/info")
class InfoResource(
    @ConfigProperty(name = "train.commit") private val commit: Optional<String>,
    @ConfigProperty(name = "train.build") private val build: Optional<String>,
    @ConfigProperty(name = "train.media.path") private val media: Optional<String>,
    private val source: Source,
) {
    /** `0.2.0+5cdae85`; номер сборки Jenkins — отдельно (ADR 0009). */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    fun info(): ServerInfo {
        val c = commit.orElse(null)?.takeIf { it.isNotBlank() && it != "dev" }
        return ServerInfo(
            name = "train", version = fullVersion(TRAIN_VERSION, c), commit = c, build = build.orElse(null), app = APP,
            dataset = DatasetInfo(source.size, media.map { Paths.get(it).isDirectory() }.orElse(false)),
        )
    }

    private companion object {
        /** Хеш из `app.<хеш>.js` в отдаваемом index.html (`server:appDist`); без сборки приложения — null. */
        val APP: String? = InfoResource::class.java.getResource("/META-INF/resources/index.html")
            ?.readText()?.let { Regex("""app\.([0-9a-f]{12})\.js""").find(it)?.groupValues?.get(1) }
    }
}

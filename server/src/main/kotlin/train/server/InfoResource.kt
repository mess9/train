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
) {
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    fun info() = ServerInfo(name = "train", version = version)
}

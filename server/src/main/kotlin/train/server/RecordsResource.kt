package train.server

import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.QueryParam
import train.core.API_PREFIX
import train.core.Records

@Path("$API_PREFIX/records")
class RecordsResource(private val store: Store, private val user: CurrentUser) {
    /** Без ids — все упражнения пользователя; exclude исключает текущую тренировку целиком. */
    @GET
    fun get(@QueryParam("ids") ids: String?, @QueryParam("exclude") exclude: String?): Records = store.records(
        user.id,
        ids?.split(',')?.filter { it.isNotBlank() }?.map(::parseIdParam)?.takeIf { it.isNotEmpty() },
        exclude?.let(::parseIdParam),
    )
}

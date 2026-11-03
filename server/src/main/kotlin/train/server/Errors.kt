package train.server

import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import kotlinx.serialization.SerializationException
import org.jboss.resteasy.reactive.RestResponse
import org.jboss.resteasy.reactive.server.ServerExceptionMapper
import train.core.ApiError
import kotlin.uuid.Uuid

fun apiError(status: Response.Status, message: String) =
    WebApplicationException(Response.status(status).entity(ApiError(message)).type(MediaType.APPLICATION_JSON).build())

fun notFound(what: String = "не найдено") = apiError(Response.Status.NOT_FOUND, what)

fun badRequest(message: String) = apiError(Response.Status.BAD_REQUEST, message)

/** ID из пути; кривой — как будто записи нет. */
fun parseId(s: String): Uuid = runCatching { Uuid.parse(s) }.getOrElse { throw notFound() }

fun parseDate(s: String?): kotlinx.datetime.LocalDate? =
    s?.let { runCatching { kotlinx.datetime.LocalDate.parse(it) }.getOrElse { throw badRequest("дата не в формате YYYY-MM-DD: $s") } }

class ErrorMappers {
    @ServerExceptionMapper
    fun serialization(e: SerializationException): RestResponse<ApiError> =
        RestResponse.status(Response.Status.BAD_REQUEST, ApiError(e.message ?: "некорректный JSON"))
}

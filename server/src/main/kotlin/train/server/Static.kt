package train.server

import io.quarkus.vertx.http.runtime.filters.Filters
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes

/**
 * Файлы приложения без хеша в имени — каждый раз через сервер. Quarkus в проде отдаёт статику
 * как `public, immutable, max-age=86400`: Cloudflare держал бы старый sw.js на краю до суток,
 * и телефон не видел бы новую сборку. У остальных файлов хеш в имени — им кэш не мешает.
 */
@ApplicationScoped
class StaticCache {
    fun register(@Observes filters: Filters) {
        filters.register({ rc ->
            if (rc.normalizedPath() in UNHASHED) {
                // перед отправкой заголовков: статика выставляет свой Cache-Control позже фильтра
                rc.addHeadersEndHandler { rc.response().headers().set("Cache-Control", "no-cache") }
            }
            rc.next()
        }, 100)
    }

    private companion object {
        val UNHASHED = setOf("/", "/index.html", "/sw.js", "/manifest.webmanifest")
    }
}

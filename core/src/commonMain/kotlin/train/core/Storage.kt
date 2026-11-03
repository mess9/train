package train.core

/** Хранилище строк по ключу. В браузере — `localStorage`: запись синхронная (ADR 0007). */
interface Storage {
    fun read(key: String): String?

    fun write(key: String, value: String)

    fun remove(key: String)
}

/** Ключ документа [Session]: тренировка и очередь. Его потеря — потеря неотправленного. */
const val SESSION_KEY = "session"

fun Storage.loadSession(): Session? =
    read(SESSION_KEY)?.let { runCatching { TrainJson.decodeFromString(Session.serializer(), it) }.getOrNull() }

fun Storage.saveSession(s: Session) = write(SESSION_KEY, TrainJson.encodeToString(Session.serializer(), s))

package train.health

import android.content.Context
import androidx.core.content.edit

/**
 * Настройки и состояние синхронизации — в SharedPreferences приложения. Из резервных копий
 * исключены (манифест): там ключ API и секрет Cloudflare.
 */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("sync", Context.MODE_PRIVATE)

    var url: String?
        get() = prefs.getString("url", null)
        set(v) = prefs.edit { putString("url", v) }

    var key: String?
        get() = prefs.getString("key", null)
        set(v) = prefs.edit { putString("key", v) }

    var cfClientId: String?
        get() = prefs.getString("cf_client_id", null)
        set(v) = prefs.edit { putString("cf_client_id", v) }

    var cfClientSecret: String?
        get() = prefs.getString("cf_client_secret", null)
        set(v) = prefs.edit { putString("cf_client_secret", v) }

    /** Итог последней синхронизации одной строкой — показывает экран. */
    var status: String?
        get() = prefs.getString("status", null)
        set(v) = prefs.edit { putString("status", v) }

    val configured get() = !url.isNullOrBlank() && !key.isNullOrBlank()

    /** Сервисный токен Cloudflare Access, если сервер за ним (как у CLI). */
    val headers: Map<String, String>
        get() {
            val id = cfClientId?.trim().orEmpty()
            val secret = cfClientSecret?.trim().orEmpty()
            return if (id.isEmpty() || secret.isEmpty()) emptyMap()
            else mapOf("CF-Access-Client-Id" to id, "CF-Access-Client-Secret" to secret)
        }

    /** Токен изменений Health Connect по типу записей; нет — нужна полная выгрузка. */
    fun token(type: String): String? = prefs.getString("token_$type", null)

    fun setToken(type: String, token: String?) = prefs.edit { if (token == null) remove("token_$type") else putString("token_$type", token) }

    /** Ссылка `https://сервер/#key=…` из `gym admin key --qr`: адрес и ключ. */
    fun applyLink(link: String): Boolean {
        val t = link.trim()
        val i = t.indexOf("#key=")
        if (i <= 0) return false
        url = t.substring(0, i).trimEnd('/')
        key = t.substring(i + "#key=".length).substringBefore('&').trim()
        return !key.isNullOrEmpty()
    }
}

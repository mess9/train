package train.health

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private const val RATIONALE = """Приложение читает из Health Connect сон, шаги, пульс, SpO2 и тренировки и отправляет их только на ваш сервер train — по ссылке, которую вы вставили. Больше никуда данные не уходят; в Health Connect приложение ничего не пишет. Отозвать доступ — в настройках Health Connect."""

/**
 * Один экран: ссылка на сервер с ключом, сервисный токен Cloudflare, разрешения Health Connect,
 * синхронизация сейчас и итог последней. Дальше — в фоне раз в 8 часов ([SyncWorker]), перед тренировкой — кнопкой.
 */
class MainActivity : ComponentActivity() {
    private val scope = MainScope()
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var settings: Settings
    private lateinit var permissions: ActivityResultLauncher<Set<String>>
    private lateinit var state: TextView
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        permissions = registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { refresh() }

        val pad = (16 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun text(s: String, size: Float = 15f) = TextView(this).apply { text = s; textSize = size; setPadding(0, pad / 2, 0, pad / 4) }
        fun field(hint: String, value: String?, secret: Boolean = false) = EditText(this).apply {
            this.hint = hint
            setText(value.orEmpty())
            inputType = InputType.TYPE_CLASS_TEXT or
                if (secret) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
        }
        fun button(label: String, onClick: () -> Unit) = Button(this).apply { text = label; setOnClickListener { onClick() } }

        val rationale = intent?.action in setOf("androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE", Intent.ACTION_VIEW_PERMISSION_USAGE)
        col.addView(text("Сон и пульс → train", 22f))
        if (rationale) col.addView(text(RATIONALE))

        col.addView(text("Сервер: ссылка …/#key=… из «gym admin key --qr»"))
        val link = field(settings.url?.let { "$it/#key=${settings.key.orEmpty()}" } ?: "https://…/#key=…", null)
        col.addView(link)
        col.addView(text("Cloudflare Access (если сервер за ним)"))
        val cfId = field("CF-Access-Client-Id", settings.cfClientId)
        val cfSecret = field("CF-Access-Client-Secret", settings.cfClientSecret, secret = true)
        col.addView(cfId)
        col.addView(cfSecret)
        col.addView(button("Сохранить") {
            val l = link.text.toString()
            if (l.isNotBlank() && !settings.applyLink(l)) {
                status.text = "в ссылке нет #key="
                return@button
            }
            settings.cfClientId = cfId.text.toString().trim().ifEmpty { null }
            settings.cfClientSecret = cfSecret.text.toString().trim().ifEmpty { null }
            link.setText("")
            link.hint = "${settings.url}/#key=…"
            SyncWorker.schedule(this)
            refresh()
        })

        state = text("")
        col.addView(state)
        col.addView(button("Разрешения Health Connect") { askPermissions() })
        col.addView(button("Отправить сейчас") {
            SyncWorker.now(this)
            status.text = "отправка поставлена в очередь…"
        })
        status = text("")
        col.addView(status)
        col.addView(text(RATIONALE, 12f).apply { visibility = if (rationale) View.GONE else View.VISIBLE })

        setContentView(ScrollView(this).apply { addView(col) })
        if (settings.configured) SyncWorker.schedule(this)
    }

    override fun onResume() {
        super.onResume()
        refresh()
        tick()
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacksAndMessages(null)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    /** Итог фоновой синхронизации пишется в настройки — перечитываем, пока экран открыт. */
    private fun tick() {
        status.text = settings.status ?: "ещё не синхронизировали"
        ui.postDelayed({ tick() }, 2000)
    }

    private fun askPermissions() {
        when (HealthConnectClient.getSdkStatus(this)) {
            HealthConnectClient.SDK_AVAILABLE -> permissions.launch(SYNC_PERMISSIONS)
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.healthdata&url=healthconnect%3A%2F%2Fonboarding")),
            )
            else -> state.text = "Health Connect на этом телефоне недоступен"
        }
    }

    private fun refresh() {
        val server = if (settings.configured) "сервер: ${settings.url}" + (if (settings.headers.isEmpty()) "" else " · с токеном Cloudflare") else "сервер не задан"
        if (HealthConnectClient.getSdkStatus(this) != HealthConnectClient.SDK_AVAILABLE) {
            state.text = "$server\nHealth Connect недоступен"
            return
        }
        scope.launch {
            val granted = runCatching { HealthConnectClient.getOrCreate(this@MainActivity).permissionController.getGrantedPermissions() }
                .getOrDefault(emptySet())
            val missing = SYNC_PERMISSIONS - granted
            state.text = "$server\n" + if (missing.isEmpty()) "разрешения: все" else "разрешения: нет ${missing.size} из ${SYNC_PERMISSIONS.size}" +
                missing.joinToString("", prefix = "\n") { "· " + it.substringAfterLast('.').lowercase() + "\n" }.trimEnd()
        }
    }
}

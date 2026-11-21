package train.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.core.content.FileProvider
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import java.io.File

/**
 * Связь общего кода с Android: контекст приложения и текущая Activity. Модуль `android/` зовёт
 * [init] из `Application.onCreate` — раньше первого обращения к [DeviceStorage] — и [attach] из
 * `Activity.onCreate`, до `setContent`.
 */
object AndroidHost {
    internal lateinit var context: Context
        private set

    /** Activity на экране или в фоне; `null` — её нет (закрыта, пересоздаётся). */
    internal var activity: ComponentActivity? = null
        private set

    /** `versionName` APK — номер сборки для экрана «Настройки». */
    internal var build = "dev"
        private set

    internal val main = Handler(Looper.getMainLooper())

    internal val online = mutableListOf<() -> Unit>()
    internal val visible = mutableListOf<() -> Unit>()

    /** Экран не гаснет: флаг окна. Помним, чтобы поставить и новой Activity. */
    internal var wakeOn = false

    private var gallery: (() -> Unit)? = null
    private var camera: ((Uri) -> Unit)? = null

    fun init(context: Context) {
        this.context = context.applicationContext
        build = runCatching { this.context.packageManager.getPackageInfo(this.context.packageName, 0).versionName }.getOrNull() ?: "dev"
        DeviceStorage.dir = File(this.context.filesDir, "store").apply { mkdirs() }
        // Как событие `online` браузера: только возврат сети после потери, не каждая смена сети
        val cm = this.context.getSystemService(ConnectivityManager::class.java)
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            private var lost = false

            override fun onLost(network: Network) {
                lost = true
            }

            override fun onAvailable(network: Network) {
                if (!lost) return
                lost = false
                main.post { online.toList().forEach { it() } }
            }
        })
    }

    fun attach(a: ComponentActivity) {
        activity = a
        // AppState новой Activity подпишется заново: подписки прежней — уже ничьи
        online.clear()
        visible.clear()
        BackStack.reset()
        a.onBackPressedDispatcher.addCallback(a, BackStack.newCallback())
        a.lifecycle.addObserver(object : DefaultLifecycleObserver {
            private var started = false

            override fun onStart(owner: LifecycleOwner) {
                // Первый старт — это запуск, а не возврат на экран
                if (started) visible.toList().forEach { it() }
                started = true
            }

            override fun onDestroy(owner: LifecycleOwner) {
                if (activity === a) activity = null
            }
        })
        if (wakeOn) a.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Выбор фото регистрируется до старта Activity — иначе система не вернёт результат
        val pick = a.registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri == null) Photo.cancel() else Photo.use(uri, fromCamera = false)
        }
        gallery = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        var shot: Uri? = null
        val take = a.registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
            val u = shot
            if (ok && u != null) Photo.use(u, fromCamera = true) else Photo.cancel()
        }
        camera = { uri -> shot = uri; take.launch(uri) }
    }

    internal fun visibleNow(): Boolean = activity?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) == true

    internal fun openGallery(): Boolean = gallery?.let { it(); true } ?: false

    /** Снимок пишется в кэш приложения: камере его отдаёт `FileProvider` (манифест `android/`). */
    internal fun openCamera(): Boolean {
        val launch = camera ?: return false
        val file = File(context.cacheDir, "photos").apply { mkdirs() }.let { File(it, "shot.jpg") }
        launch(FileProvider.getUriForFile(context, context.packageName + ".photos", file))
        return true
    }
}

/** Корень APK: приложение — в безопасной области экрана (вырез, панели, клавиатура), фон — до краёв. */
@Composable
fun AndroidRoot() {
    Box(Modifier.fillMaxSize().background(C.Bg).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Root()
    }
}

package train.apk

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import train.app.AndroidHost
import train.app.AndroidRoot

/** Единственный экран: то же приложение, что PWA, — `Root` из общего кода `app`. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Тёмная тема приложения: значки панелей светлые, фон — до краёв экрана
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        AndroidHost.attach(this)
        setContent { AndroidRoot() }
    }
}

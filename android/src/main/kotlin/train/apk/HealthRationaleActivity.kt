package train.apk

import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity

/**
 * Зачем приложению данные Health Connect: его открывает сам Health Connect из запроса доступа и
 * из своих настроек. Без такого экрана Health Connect доступ не выдаёт (ADR 0015).
 */
class HealthRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val text = TextView(this).apply {
            setPadding(pad, pad, pad, pad)
            textSize = 16f
            text = "Тренировки читают из Health Connect сон, шаги, пульс, SpO2 и тренировки и отправляют их " +
                "только на ваш сервер train — тот, к которому подключено приложение. Тренер по ним разбирает " +
                "восстановление и нагрузку. Больше никуда данные не уходят; в Health Connect приложение ничего " +
                "не пишет. Отозвать доступ — в настройках Health Connect."
        }
        setContentView(ScrollView(this).apply { addView(text) })
    }
}

package train.apk

import android.app.Application
import train.app.AndroidHost

/** Платформа общего кода готова раньше любой Activity: хранилище, сеть, номер сборки. */
class TrainApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AndroidHost.init(this)
    }
}

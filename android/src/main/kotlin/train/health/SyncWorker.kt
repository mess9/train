package train.health

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.IOException
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

private const val PERIODIC = "health-sync"
private const val NOW = "health-sync-now"

/** Синхронизация в фоне: раз в час при сети, плюс по кнопке. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val settings = Settings(applicationContext)
        val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date())
        return try {
            settings.status = "$time · ${HealthSync(applicationContext).run()}"
            Result.success()
        } catch (e: IOException) {
            settings.status = "$time · нет связи: ${e.message ?: e::class.simpleName}"
            Result.retry()
        } catch (e: Exception) {
            // Нет настроек, разрешений, ответ сервера 4xx/5xx — повторит следующий час
            settings.status = "$time · ошибка: ${e.message ?: e::class.simpleName}"
            Result.failure()
        }
    }

    companion object {
        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS).setConstraints(network).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun now(context: Context) {
            val req = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(network).build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, req)
        }
    }
}

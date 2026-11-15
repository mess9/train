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
import train.client.ApiException
import java.io.IOException
import kotlinx.coroutines.CancellationException
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

private const val PERIODIC = "health-sync"
private const val NOW = "health-sync-now"

// Сон пишется раз в сутки, шаги и пульс — к вечеру: трёх раз в сутки хватает
private const val PERIOD_HOURS = 8L

/** Синхронизация в фоне: раз в [PERIOD_HOURS] часов при сети, плюс по кнопке — свежие данные перед тренировкой. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val settings = Settings(applicationContext)
        val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date())
        return try {
            settings.status = "$time · ${HealthSync(applicationContext).run()}"
            Result.success()
        } catch (e: CancellationException) {
            // Android остановил задачу (новое нажатие, экономия батареи): готовые типы сохранены,
            // следующий запуск продолжит с того, на котором остановились
            settings.status = "$time · прервано системой — продолжит при следующем запуске или по кнопке"
            throw e
        } catch (e: IOException) {
            settings.status = "$time · нет связи: ${e.message ?: e::class.simpleName}"
            Result.retry()
        } catch (e: ApiException) {
            settings.status = "$time · " + when (e.status) {
                401 -> "сервер не принял ключ — вставьте новую ссылку"
                404 -> "на сервере нет /api/health — он ещё не обновлён"
                else -> "сервер ответил ${e.status}: ${e.message.orEmpty().take(200)}"
            }
            Result.failure()
        } catch (e: Exception) {
            // Нет настроек, разрешений, ответ сервера 4xx/5xx — повторит следующий запуск
            settings.status = "$time · ошибка: ${e.message ?: e::class.simpleName}"
            Result.failure()
        }
    }

    companion object {
        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(PERIOD_HOURS, TimeUnit.HOURS).setConstraints(network).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        fun now(context: Context) {
            val req = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(network).build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, req)
        }
    }
}

package com.cleandroid.maintainer.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.cleandroid.maintainer.cleaner.JunkScanner
import com.cleandroid.maintainer.core.formatBytes
import java.util.concurrent.TimeUnit

/** Worker semanal: varre e notifica total de lixo. Nunca apaga sozinho. */
class ScheduledCleanWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        return try {
            val scanner = JunkScanner(applicationContext)
            val res = scanner.scan()
            notify(applicationContext, res.totalBytes, res.items.size)
            Result.success(workDataOf("total" to res.totalBytes, "count" to res.items.size))
        } catch (_: Exception) { Result.retry() }
    }

    companion object {
        const val TAG = "weekly_scan"
        const val CH = "cleandroid_maint"

        fun scheduleWeekly(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<ScheduledCleanWorker>(7, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .setRequiresStorageNotLow(true)
                        .build()
                )
                .addTag(TAG)
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                TAG, ExistingPeriodicWorkPolicy.UPDATE, req
            )
        }

        fun cancel(ctx: Context) = WorkManager.getInstance(ctx).cancelUniqueWork(TAG)

        private fun notify(ctx: Context, bytes: Long, count: Int) {
            try {
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (Build.VERSION.SDK_INT >= 26) {
                    nm.createNotificationChannel(
                        NotificationChannel(CH, "Manutenção", NotificationManager.IMPORTANCE_DEFAULT)
                    )
                }
                val n = NotificationCompat.Builder(ctx, CH)
                    .setSmallIcon(android.R.drawable.ic_menu_delete)
                    .setContentTitle("CleanDroid: varredura semanal")
                    .setContentText("Encontrado ~${formatBytes(bytes)} em $count itens. Abra para limpar.")
                    .setAutoCancel(true)
                    .build()
                nm.notify(7001, n)
            } catch (_: Exception) {}
        }
    }
}

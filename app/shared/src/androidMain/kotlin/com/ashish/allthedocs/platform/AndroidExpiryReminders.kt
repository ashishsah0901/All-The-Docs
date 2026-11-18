package com.ashish.allthedocs.platform

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toInstant
import java.util.concurrent.TimeUnit
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

class WorkManagerExpiryReminderScheduler(private val context: Context) : ExpiryReminderScheduler {
    @OptIn(ExperimentalTime::class)
    override suspend fun schedule(documentId: String, title: String, expiryDate: LocalDate) {
        val workManager = WorkManager.getInstance(context)
        val zone = TimeZone.currentSystemDefault()
        val now = Clock.System.now()
        REMINDER_LEAD_DAYS.forEach { leadDays ->
            val triggerAt = LocalDateTime(expiryDate.minus(DatePeriod(days = leadDays)), LocalTime(9, 0)).toInstant(zone)
            val delayMs = (triggerAt - now).inWholeMilliseconds
            if (delayMs <= 0) return@forEach
            val request = OneTimeWorkRequestBuilder<ExpiryReminderWorker>()
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .setInputData(
                    workDataOf(
                        ExpiryReminderWorker.KEY_DOCUMENT_ID to documentId,
                        ExpiryReminderWorker.KEY_TITLE to title,
                        ExpiryReminderWorker.KEY_EXPIRY to expiryDate.toString(),
                        ExpiryReminderWorker.KEY_LEAD_DAYS to leadDays,
                    ),
                )
                .addTag(tagFor(documentId))
                .build()
            workManager.enqueueUniqueWork("expiry-$documentId-$leadDays", ExistingWorkPolicy.REPLACE, request)
        }
    }

    override suspend fun cancel(documentId: String) {
        WorkManager.getInstance(context).cancelAllWorkByTag(tagFor(documentId))
    }

    private fun tagFor(documentId: String) = "expiry-$documentId"
}

class ExpiryReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val title = inputData.getString(KEY_TITLE) ?: return Result.success()
        val expiry = inputData.getString(KEY_EXPIRY) ?: return Result.success()
        val documentId = inputData.getString(KEY_DOCUMENT_ID) ?: return Result.success()
        val leadDays = inputData.getInt(KEY_LEAD_DAYS, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return Result.success()
        }
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Document expiry", NotificationManager.IMPORTANCE_DEFAULT),
        )
        val launchIntent = applicationContext.packageManager.getLaunchIntentForPackage(applicationContext.packageName)
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(applicationContext, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("$title expires in $leadDays days")
            .setContentText("Expiry date: $expiry. Start the renewal now.")
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(applicationContext).notify("$documentId-$leadDays".hashCode(), notification)
        return Result.success()
    }

    companion object {
        const val CHANNEL_ID = "document_expiry"
        const val KEY_DOCUMENT_ID = "document_id"
        const val KEY_TITLE = "title"
        const val KEY_EXPIRY = "expiry"
        const val KEY_LEAD_DAYS = "lead_days"
    }
}

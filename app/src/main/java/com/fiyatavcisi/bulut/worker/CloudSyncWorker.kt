package com.fiyatavcisi.bulut.worker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.fiyatavcisi.bulut.R
import com.fiyatavcisi.bulut.data.CloudRepository
import com.fiyatavcisi.bulut.model.CloudDeal
import com.fiyatavcisi.bulut.model.CloudSettings
import com.fiyatavcisi.bulut.model.StoreSite
import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

class CloudSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val repo = CloudRepository(applicationContext)
        val settings = repo.loadSettings()
        val result = repo.fetchLatestSnapshot(settings)
        if (result.isFailure) return Result.retry()

        val snapshot = result.getOrThrow()
        val notified = repo.getNotifiedDealKeys()
        val newlyNotified = mutableSetOf<String>()

        for (deal in snapshot.bigDeals) {
            if (deal.id in notified) continue
            if (deal.site != StoreSite.OTHER && deal.site !in settings.enabledSites) continue
            if (deal.dropPercent < settings.minDropPercent) continue
            if (deal.isFromCategory && !settings.notifyAnyCategoryDeals) continue
            if (!deal.isFromCategory && !settings.notifyTrackedProducts) continue

            sendNotification(applicationContext, deal)
            newlyNotified.add(deal.id)
        }

        if (newlyNotified.isNotEmpty()) {
            repo.markDealsNotified(newlyNotified)
        }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "fiyat_avcisi_bulut_sync"
        private const val CHANNEL_ID = "bulut_fiyat_bildirim"

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Bulut Fiyat Düşüşü Bildirimleri",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "7 sitedeki karşılaştırmalı fiyat düşüşleri ve büyük indirimler"
                }
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.createNotificationChannel(channel)
            }
        }

        fun schedule(context: Context, settings: CloudSettings) {
            val wm = WorkManager.getInstance(context)
            if (!settings.backgroundSyncEnabled || settings.snapshotRawUrl.isBlank()) {
                wm.cancelUniqueWork(WORK_NAME)
                return
            }
            val interval = maxOf(15L, settings.syncIntervalMinutes)
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val req = PeriodicWorkRequestBuilder<CloudSyncWorker>(interval, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        fun sendNotification(context: Context, deal: CloudDeal) {
            ensureChannel(context)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val granted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
                if (!granted) return
            }

            val tl = NumberFormat.getCurrencyInstance(Locale("tr", "TR"))
            val pct = String.format(Locale("tr", "TR"), "%.1f", deal.dropPercent)
            val title = "-%$pct İndirim: ${deal.title}"
            val shortText = "${deal.site.displayName}: ${tl.format(deal.oldPrice)} → ${tl.format(deal.newPrice)}"
            val fullText = buildString {
                append(shortText)
                if (deal.comparisonSummary.isNotBlank()) {
                    append("\n\nKarşılaştırma / Kaynak:\n")
                    append(deal.comparisonSummary)
                }
            }

            val openIntent = Intent(Intent.ACTION_VIEW, Uri.parse(deal.url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val pi = PendingIntent.getActivity(
                context,
                deal.id.hashCode(),
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_app_launcher)
                .setContentTitle(title)
                .setContentText(shortText)
                .setStyle(NotificationCompat.BigTextStyle().bigText(fullText))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .addAction(R.drawable.ic_app_launcher, "Satın Al (Uygulamada Aç)", pi)
                .build()

            NotificationManagerCompat.from(context).notify(deal.id.hashCode(), notification)
        }
    }
}

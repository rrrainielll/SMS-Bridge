package com.rrrainielll.smsbridge

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.rrrainielll.smsbridge.ui.MainActivity

/**
 * The only notification SMSBridge posts: a generic "delivery needs attention" alert. It deliberately
 * never contains SMS content, senders, or credentials, since notifications can show on a lock screen.
 */
object Notifier {
    private const val CHANNEL_ISSUES = "delivery_issues"
    private const val ID_ISSUE = 1001

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ISSUES,
                context.getString(R.string.channel_issues_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.channel_issues_desc)
                lockscreenVisibility = android.app.Notification.VISIBILITY_SECRET
            }
        )
    }

    fun showIssue(context: Context) {
        if (!canNotify(context)) return
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ISSUES)
            .setSmallIcon(R.drawable.ic_stat_sms)
            .setContentTitle(context.getString(R.string.notif_issue_title))
            .setContentText(context.getString(R.string.notif_issue_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.notif_issue_text)))
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(ID_ISSUE, notification)
    }

    fun clearIssue(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(ID_ISSUE)
    }

    private fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
}

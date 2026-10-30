package com.nextyearrobotics.seniorcare.service

import android.app.*
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.nextyearrobotics.seniorcare.MainActivity
import com.nextyearrobotics.seniorcare.R
import com.nextyearrobotics.seniorcare.domain.*

class SessionNotifications(private val context: Context) : SosAlerts {
    private val manager = context.getSystemService(NotificationManager::class.java)
    init {
        manager.createNotificationChannel(NotificationChannel(CONNECTION, context.getString(R.string.notification_connection), NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel(SOS, context.getString(R.string.notification_sos), NotificationManager.IMPORTANCE_HIGH).apply { enableVibration(true) })
    }
    private fun open(sos: Boolean = false) = PendingIntent.getActivity(context, if (sos) 1 else 0,
        Intent(context, MainActivity::class.java).putExtra("show_sos", sos).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun connection(robotId: String?, connection: Connection): Notification {
        val label = context.getString(when (connection) {
            Connection.READY -> R.string.status_connected
            Connection.STOPPED -> R.string.status_disconnected
            else -> R.string.status_searching
        })
        val stop = PendingIntent.getService(context, 0, Intent(context, BridgeService::class.java).setAction(BridgeService.STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(context, CONNECTION).setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(context.getString(R.string.notification_connection)).setContentText(listOfNotNull(robotId, label).joinToString(" · "))
            .setContentIntent(open()).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(0, context.getString(R.string.connection_stop), stop).build()
    }
    fun updateConnection(robotId: String?, state: Connection) { manager.notify(CONNECTION_ID, connection(robotId, state)) }
    override fun show(state: SosState, first: Boolean) {
        // No coordinates or personal location details on the lock screen.
        val notification = NotificationCompat.Builder(context, SOS).setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(context.getString(R.string.notification_sos)).setContentText(context.getString(R.string.sos_generic))
            .setContentIntent(open(true)).setOngoing(true).setAutoCancel(false).setOnlyAlertOnce(!first)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH).build()
        try { manager.notify(state.request.sosId, SOS_ID, notification) } catch (_: SecurityException) { /* In-app SOS and ACK remain active. */ }
    }
    override fun clear(id: String) { manager.cancel(id, SOS_ID) }
    companion object { const val CONNECTION_ID = 1; const val SOS_ID = 2; const val CONNECTION = "connection"; const val SOS = "sos" }
}

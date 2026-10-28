package ir.inourai.newapp.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import ir.inourai.newapp.MainActivity
import ir.inourai.newapp.R
import ir.inourai.newapp.data.Api
import ir.inourai.newapp.data.DeviceTokenRequest
import ir.inourai.newapp.data.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** نمایش اعلان‌های FCM (پیام‌های data-only با title/body از بک‌اند).
 *  سبک نوتیفیکیشن عین GenerationPollService است (آیکون ic_notification)،
 *  ولی کانال جداگانه‌ی "fcm" دارد. همه‌چیز best-effort است و هرگز کرش نمی‌کند. */
class FcmService : FirebaseMessagingService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        if (Session.token == null) return
        scope.launch {
            try {
                Api.service.registerDeviceToken(DeviceTokenRequest(token))
            } catch (_: Exception) {
            }
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        val title = remoteMessage.data["title"].orEmpty()
        val body = remoteMessage.data["body"].orEmpty()
        if (title.isEmpty() && body.isEmpty()) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "اعلان‌ها",
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pending = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        nm.notify(
            System.currentTimeMillis().toInt(),
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(body)
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build(),
        )
    }

    companion object {
        const val CHANNEL_ID = "fcm"
    }
}

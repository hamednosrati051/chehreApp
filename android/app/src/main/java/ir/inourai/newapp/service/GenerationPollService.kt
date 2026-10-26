package ir.inourai.newapp.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import ir.inourai.newapp.MainActivity
import ir.inourai.newapp.R
import ir.inourai.newapp.data.Api
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** سرویس پیش‌زمینه‌ی دنبال‌کردن ساخت تصویر: وقتی کاربر موقع ساخت از
 *  اپ بیرون می‌رود، با آماده‌شدن تصویر نوتیفیکیشن می‌دهد (مثل رقیب:
 *  «✨ تصویرت آماده شد»). بدون FCM کار می‌کند؛ اگر سیستم سرویس را
 *  بکشد، نتیجه باز هم در «تصاویر من» هست. */
class GenerationPollService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val genId = intent?.getLongExtra(EXTRA_GEN_ID, -1L) ?: -1L
        if (genId <= 0L) {
            stopSelf()
            return START_NOT_STICKY
        }
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "ساخت تصویر",
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        startForeground(
            ONGOING_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("در حال ساخت تصویر...")
                .setContentText("تصویرت دارد آماده می‌شود")
                .setOngoing(true)
                .setSilent(true)
                .build(),
        )
        scope.launch {
            // سقف ۱۵ دقیقه؛ بیشتر از آن یعنی ساخت گیر کرده است.
            repeat(180) {
                delay(5000)
                if (!isActive) return@launch
                try {
                    val st = Api.service.generation(genId)
                    if (st.status == "done") {
                        notifyDone(nm, genId, st.world_name)
                        stopSelf()
                        return@launch
                    }
                    if (st.status == "failed") {
                        notifyFailed(nm, genId)
                        stopSelf()
                        return@launch
                    }
                } catch (_: Exception) {
                    // خطای گذرا (شبکه/توکن)؛ نظرسنجی ادامه دارد.
                }
            }
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun notifyDone(nm: NotificationManager, genId: Long, worldName: String?) {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_GEN_ID, genId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        val pending = PendingIntent.getActivity(
            this, genId.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        nm.notify(
            genId.toInt(),
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("✨ تصویرت آماده شد")
                .setContentText("«${worldName ?: "دنیا"}» آماده‌ست — ببینش!")
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun notifyFailed(nm: NotificationManager, genId: Long) {
        nm.notify(
            genId.toInt(),
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("ساخت تصویر ناموفق بود")
                .setContentText("دوباره تلاش کن.")
                .setAutoCancel(true)
                .build(),
        )
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "generation"
        const val EXTRA_GEN_ID = "gen_id"
        private const val ONGOING_ID = 1

        fun start(context: Context, genId: Long) {
            val intent = Intent(context, GenerationPollService::class.java).apply {
                putExtra(EXTRA_GEN_ID, genId)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, GenerationPollService::class.java))
        }
    }
}

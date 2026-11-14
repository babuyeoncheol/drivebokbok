package kr.ansimgwiga.car

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * 운행을 지켜보는 포그라운드 서비스.
 *
 * 화면(MainActivity)이 뒤로 가도(운전자가 내비를 볼 때) 차량 신호 받기·판단·안내음·가족 알림이
 * 계속 돌게 [AppController]를 살려 둔다. 차 시동(부팅) 때 [BootReceiver]가 자동으로 켠다.
 */
class TripService : Service() {

    override fun onCreate() {
        super.onCreate()
        startInForeground()
        AppController.get(application)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    // onDestroy에서 AppController를 닫지 않는다. 화면이 아직 쓰고 있을 수 있고, START_STICKY로
    // 서비스가 다시 켜지면 같은 인스턴스를 그대로 이어 쓴다. 프로세스가 끝나면 함께 끝난다.

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("가족에게 귀가 소식을 전하고 있어요")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val CHANNEL_ID = "trip"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, TripService::class.java))
            } catch (e: Exception) {
                // 백그라운드에서 시작이 막힌 경우 등. 화면을 열면 다시 시도한다.
                Log.w("TripService", "start failed", e)
            }
        }
    }
}

/** 차 시동(부팅)·앱 업데이트 후 서비스를 자동으로 켠다. 운전자가 앱을 열지 않아도 동작하게. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> TripService.start(context)
        }
    }
}

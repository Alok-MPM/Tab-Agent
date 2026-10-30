package com.alok.hello

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager

object Notifier {
    private const val CH = "hello"

    fun show(c: Context, title: String, text: String) {
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CH, "Hello agent", NotificationManager.IMPORTANCE_HIGH)
        )
        val n = Notification.Builder(c, CH)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        try {
            nm.notify((System.currentTimeMillis() % 100000).toInt(), n)
        } catch (_: SecurityException) {
        }
    }
}

object Waker {
    @Suppress("DEPRECATION")
    fun wake(c: Context) {
        val pm = c.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE,
            "hello:wake"
        )
        wl.acquire(90_000)
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val id = i.getLongExtra("id", -1L)
        val t = Store.get(c, id) ?: return
        if (t.status != "PENDING") return
        Store.log(c, "Alarm aaya, task chalu kar raha hoon")
        Waker.wake(c)
        val s = HelloService.instance
        if (s != null) {
            s.runTask(id)
        } else {
            Store.setStatus(c, id, "FAILED", "Accessibility service band thi")
            Notifier.show(
                c, "Hello: message nahi gaya",
                "Accessibility service band hai. Hello app kholke ON karo, phir 'Test now' dabao."
            )
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Store.log(c, "Boot/update ke baad tasks dobara set kiye")
        Scheduler.rescheduleAll(c)
    }
}

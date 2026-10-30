package com.alok.hello

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

object Scheduler {

    private fun pending(c: Context, id: Long): PendingIntent =
        PendingIntent.getBroadcast(
            c,
            (id and 0x7fffffffL).toInt(),
            Intent(c, AlarmReceiver::class.java).putExtra("id", id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    fun schedule(c: Context, t: Task, overrideAt: Long? = null) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val at = overrideAt ?: t.triggerAt
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (exact) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(c, t.id))
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(c, t.id))
            Store.log(c, "Exact alarm permission nahi hai, alarm thoda late ho sakta hai")
        }
    }

    fun cancel(c: Context, id: Long) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pending(c, id))
    }

    /** Called on boot, app update and whenever the service connects. Overdue tasks fire in 20s. */
    fun rescheduleAll(c: Context) {
        val now = System.currentTimeMillis()
        Store.tasks(c).filter { it.status == "PENDING" }.forEach {
            if (it.triggerAt > now) schedule(c, it) else schedule(c, it, now + 20_000)
        }
    }
}

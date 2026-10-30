package com.alok.hello

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.app.Activity

class MainActivity : Activity() {

    private val bg = 0xFF0F1117.toInt()
    private val fg = 0xFFEDEFF7.toInt()
    private val dim = 0xFF9AA0B8.toInt()
    private val accent = 0xFFA99BFF.toInt()

    private lateinit var status: TextView
    private lateinit var tasksView: TextView
    private lateinit var logView: TextView
    private lateinit var timeInput: EditText
    private lateinit var msgInput: EditText
    private val ui = Handler(Looper.getMainLooper())
    private var resumed = false

    private val ticker = object : Runnable {
        override fun run() {
            if (!resumed) return
            refresh()
            ui.postDelayed(this, 3000)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun text(s: String, sp: Float, color: Int, bold: Boolean = false): TextView {
        val t = TextView(this)
        t.text = s
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        t.setTextColor(color)
        if (bold) t.setTypeface(t.typeface, android.graphics.Typeface.BOLD)
        return t
    }

    private fun button(label: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.isAllCaps = false
        b.setOnClickListener { onClick() }
        return b
    }

    private fun field(hint: String, value: String): EditText {
        val e = EditText(this)
        e.hint = hint
        e.setText(value)
        e.setTextColor(fg)
        e.setHintTextColor(dim)
        e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        return e
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(20), dp(24), dp(20), dp(40))

        root.addView(text("Hello", 30f, fg, true))
        root.addView(text("Tab Agent · Phase 1: limit-resume", 14f, accent))

        status = text("", 14f, fg)
        status.setPadding(0, dp(16), 0, dp(8))
        root.addView(status)

        root.addView(text("Setup (ek baar):", 15f, accent, true))
        root.addView(button("1. Accessibility ON karo") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(button("2. Overlay permission do") {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        })
        root.addView(button("3. Exact alarm permission do") {
            if (Build.VERSION.SDK_INT >= 31) {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName"))
                )
            }
        })
        root.addView(button("4. Battery: Unrestricted karo") {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        })
        root.addView(button("Accessibility grey hai? App info kholo") {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        })
        root.addView(
            text(
                "Grey ho to App info → ⋮ (teen dot) → \"Allow restricted settings\", phir step 1 dobara.",
                12f, dim
            )
        )

        val h2 = text("Message schedule karo:", 15f, accent, true)
        h2.setPadding(0, dp(20), 0, 0)
        root.addView(h2)
        timeInput = field("Reset time (2:30 pm  ya  in 3h 20m)", "")
        root.addView(timeInput)
        msgInput = field("Bhejne wala message", Store.message(this))
        root.addView(msgInput)
        root.addView(button("Schedule karo (reset + ${Store.offsetMin(this)} min)") { schedule() })
        root.addView(button("Test now (abhi Claude mein bhejo)") { testNow() })

        val h3 = text("Tasks:", 15f, accent, true)
        h3.setPadding(0, dp(20), 0, 0)
        root.addView(h3)
        tasksView = text("", 13f, fg)
        root.addView(tasksView)
        root.addView(button("Finished tasks hatao") {
            Store.clearFinished(this)
            refresh()
        })

        val h4 = text("Log:", 15f, accent, true)
        h4.setPadding(0, dp(20), 0, 0)
        root.addView(h4)
        logView = text("", 12f, dim)
        root.addView(logView)

        val sv = ScrollView(this)
        sv.setBackgroundColor(bg)
        sv.addView(
            root,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        setContentView(sv)

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        ticker.run()
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        val m = msgInput.text.toString().trim()
        if (m.isNotEmpty()) Store.setMessage(this, m)
    }

    private fun schedule() {
        val at = ResetTimeParser.parse(timeInput.text.toString())
        if (at == null) {
            Toast.makeText(this, "Time samajh nahi aaya. Jaise: 2:30 pm", Toast.LENGTH_LONG).show()
            return
        }
        val m = msgInput.text.toString().trim().ifEmpty { Store.DEFAULT_MSG }
        Store.setMessage(this, m)
        val trigger = at + Store.offsetMin(this) * 60_000L
        val t = Store.add(this, m, trigger)
        Scheduler.schedule(this, t)
        Store.log(this, "Task set: send ${HelloService.fmt(trigger)}")
        Toast.makeText(this, "Set: ${HelloService.fmt(trigger)} pe bhejunga", Toast.LENGTH_LONG).show()
        refresh()
    }

    private fun testNow() {
        val s = HelloService.instance
        if (s == null) {
            Toast.makeText(this, "Pehle Accessibility ON karo (step 1)", Toast.LENGTH_LONG).show()
            return
        }
        val m = msgInput.text.toString().trim().ifEmpty { Store.DEFAULT_MSG }
        val t = Store.add(this, m, System.currentTimeMillis())
        s.runTask(t.id)
    }

    private fun refresh() {
        val a11y = HelloService.instance != null
        val overlay = Settings.canDrawOverlays(this)
        val exact = Build.VERSION.SDK_INT < 31 ||
            (getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
        val battery = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .isIgnoringBatteryOptimizations(packageName)
        val claude = packageManager.getLaunchIntentForPackage(HelloService.CLAUDE) != null
        fun mark(b: Boolean) = if (b) "✅" else "❌"
        status.text = "${mark(a11y)} Accessibility service chal rahi hai\n" +
            "${mark(overlay)} Overlay permission\n" +
            "${mark(exact)} Exact alarm permission\n" +
            "${mark(battery)} Battery unrestricted\n" +
            "${mark(claude)} Claude app mila"

        val tasks = Store.tasks(this).sortedBy { it.triggerAt }.takeLast(12)
        tasksView.text = if (tasks.isEmpty()) "Koi task nahi." else tasks.joinToString("\n") {
            val note = if (it.note.isNotEmpty()) " (${it.note})" else ""
            "• ${HelloService.fmt(it.triggerAt)}  [${it.status}]$note\n   ${it.message.take(60)}"
        }
        logView.text = Store.logs(this).takeLast(15).joinToString("\n")
    }
}

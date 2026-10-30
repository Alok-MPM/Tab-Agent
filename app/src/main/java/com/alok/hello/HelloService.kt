package com.alok.hello

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HelloService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: HelloService? = null
        const val CLAUDE = "com.anthropic.claude"

        fun fmt(ms: Long): String =
            SimpleDateFormat("EEE h:mm a", Locale.getDefault()).format(Date(ms))
    }

    val handler = Handler(Looper.getMainLooper())
    private var bubble: Bubble? = null

    override fun onServiceConnected() {
        instance = this
        bubble = Bubble(this).also { it.show() }
        Scheduler.rescheduleAll(this)
        Store.log(this, "Service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        bubble?.hide()
        bubble = null
        return super.onUnbind(intent)
    }

    // ---------- bubble helpers ----------

    fun collapseBubble() {
        bubble?.collapse()
    }

    private fun state(symbol: String) {
        bubble?.setSymbol(symbol)
        if (symbol != "H") handler.postDelayed({ bubble?.setSymbol("H") }, 6000)
    }

    // ---------- reading the screen ----------

    /** The foreground app window, never our own bubble. */
    fun appRoot(): AccessibilityNodeInfo? {
        val active = rootInActiveWindow
        if (active != null && active.packageName?.toString() != packageName) return active
        val ws = windows
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .sortedByDescending { it.layer }
        for (w in ws) {
            val r = w.root ?: continue
            if (r.packageName?.toString() != packageName) return r
        }
        return null
    }

    private fun collect(n: AccessibilityNodeInfo, sb: StringBuilder, depth: Int) {
        if (depth > 40) return
        n.text?.let { sb.append(it).append('\n') }
        n.contentDescription?.let { sb.append(it).append('\n') }
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            collect(c, sb, depth + 1)
        }
    }

    fun scanAndSchedule(): String {
        val root = appRoot() ?: return "Screen nahi padh paaya (koi app window nahi mili)."
        val sb = StringBuilder()
        collect(root, sb, 0)
        val resetAt = ResetTimeParser.parse(sb.toString())
            ?: return "Screen pe reset time nahi mila. Aise type karo: limit 2:30 pm"
        return scheduleAt(resetAt, null)
    }

    fun scanThen(reply: (String) -> Unit) {
        collapseBubble()
        handler.postDelayed({ reply(scanAndSchedule()) }, 900)
    }

    fun scheduleAt(resetAt: Long, msg: String?): String {
        val off = Store.offsetMin(this)
        val trigger = resetAt + off * 60_000L
        val t = Store.add(this, msg ?: Store.message(this), trigger)
        Scheduler.schedule(this, t)
        Store.log(this, "Task set: reset ${fmt(resetAt)}, send ${fmt(trigger)}")
        return "Done. Reset ${fmt(resetAt)} pe hoga, message ${fmt(trigger)} pe bhejunga (+$off min)."
    }

    // ---------- doing the work in the Claude app ----------

    fun runTask(id: Long) {
        val t = Store.get(this, id) ?: return
        Store.setStatus(this, id, "RUNNING")
        state("…")
        launchWhenUnlocked(t, System.currentTimeMillis() + 10 * 60_000L, false)
    }

    private fun launchWhenUnlocked(t: Task, deadline: Long, warned: Boolean) {
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (km.isKeyguardLocked) {
            if (System.currentTimeMillis() > deadline) {
                fail(t, "Tablet locked tha, unlock nahi hua")
                return
            }
            if (!warned) {
                Notifier.show(this, "Hello: tablet unlock karo", "Claude mein message bhejna hai, tablet unlock kar do.")
            }
            handler.postDelayed({ launchWhenUnlocked(t, deadline, true) }, 10_000)
            return
        }
        val li = packageManager.getLaunchIntentForPackage(CLAUDE)
        if (li == null) {
            fail(t, "Claude app install nahi mila")
            return
        }
        li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(li)
        } catch (e: Exception) {
            fail(t, "Claude khol nahi paaya: ${e.message}")
            return
        }
        handler.postDelayed({ typeStep(t, System.currentTimeMillis() + 25_000L) }, 2500)
    }

    private fun typeStep(t: Task, deadline: Long) {
        val root = appRoot()
        if (root != null && root.packageName?.toString() == CLAUDE) {
            val edit = findEditable(root)
            if (edit != null && setText(edit, t.message)) {
                handler.postDelayed({ sendStep(t, 0) }, 800)
                return
            }
        }
        if (System.currentTimeMillis() > deadline) {
            fail(t, "Claude ka message box nahi mila")
        } else {
            handler.postDelayed({ typeStep(t, deadline) }, 800)
        }
    }

    private fun sendStep(t: Task, attempt: Int) {
        val root = appRoot()
        if (root == null || root.packageName?.toString() != CLAUDE) {
            fail(t, "Claude screen se hat gaya")
            return
        }
        val btn = findSend(root)
        if (btn == null) {
            if (attempt < 8) {
                handler.postDelayed({ sendStep(t, attempt + 1) }, 600)
            } else {
                fail(t, "Send button nahi mila")
            }
            return
        }
        clickNode(btn)
        handler.postDelayed({ verifyStep(t, attempt) }, 1600)
    }

    private fun verifyStep(t: Task, attempt: Int) {
        val root = appRoot()
        val edit = if (root != null && root.packageName?.toString() == CLAUDE) findEditable(root) else null
        val cur = edit?.text?.toString().orEmpty()
        val stillThere = edit != null && cur.contains(t.message.take(20))
        if (!stillThere) {
            Store.setStatus(this, t.id, "DONE")
            Store.log(this, "Message bhej diya")
            Notifier.show(this, "Hello: message bhej diya", "Claude mein bhej diya: ${t.message.take(80)}")
            state("✓")
        } else if (attempt < 3) {
            sendStep(t, attempt + 1)
        } else {
            fail(t, "Message type hua par send nahi hua")
        }
    }

    private fun fail(t: Task, reason: String) {
        Store.setStatus(this, t.id, "FAILED", reason)
        Store.log(this, "FAIL: $reason")
        Notifier.show(this, "Hello: kaam nahi hua", reason)
        state("✗")
    }

    // ---------- node utilities ----------

    private fun findEditable(n: AccessibilityNodeInfo, depth: Int = 0): AccessibilityNodeInfo? {
        if (depth > 40) return null
        if (n.isEditable && n.isVisibleToUser) return n
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            findEditable(c, depth + 1)?.let { return it }
        }
        return null
    }

    private fun findSend(n: AccessibilityNodeInfo, depth: Int = 0): AccessibilityNodeInfo? {
        if (depth > 40) return null
        val label = (n.contentDescription?.toString() ?: n.text?.toString() ?: "").lowercase()
        // Only real buttons: clickable, short label. Avoids matching chat text that merely contains "send".
        if (n.isClickable && label.length <= 30 && label.contains("send") &&
            !label.contains("feedback") && n.isEnabled && n.isVisibleToUser
        ) {
            return n
        }
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            findSend(c, depth + 1)?.let { return it }
        }
        return null
    }

    private fun clickNode(start: AccessibilityNodeInfo) {
        var n: AccessibilityNodeInfo? = start
        while (n != null) {
            if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            n = n.parent
        }
    }

    private fun setText(edit: AccessibilityNodeInfo, msg: String): Boolean {
        edit.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, msg)
        if (edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return true
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("hello", msg))
        return edit.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }
}

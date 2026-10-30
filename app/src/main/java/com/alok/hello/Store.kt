package com.alok.hello

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Task(
    val id: Long,
    val message: String,
    val triggerAt: Long,
    val status: String, // PENDING, RUNNING, DONE, FAILED
    val note: String = ""
)

/** Everything the agent must remember lives here, on disk, so a reboot or app kill never loses a task. */
object Store {
    const val DEFAULT_MSG =
        "Please continue the work from where you stopped. The usage limit has reset."

    private fun p(c: Context) =
        c.applicationContext.getSharedPreferences("hello", Context.MODE_PRIVATE)

    @Synchronized
    fun tasks(c: Context): List<Task> {
        val arr = JSONArray(p(c).getString("tasks", "[]"))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Task(
                o.getLong("id"), o.getString("message"), o.getLong("at"),
                o.getString("status"), o.optString("note")
            )
        }
    }

    @Synchronized
    private fun write(c: Context, list: List<Task>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject().put("id", it.id).put("message", it.message).put("at", it.triggerAt)
                    .put("status", it.status).put("note", it.note)
            )
        }
        p(c).edit().putString("tasks", arr.toString()).apply()
    }

    @Synchronized
    fun add(c: Context, message: String, at: Long): Task {
        val existing = tasks(c)
        val id = maxOf(System.currentTimeMillis(), (existing.maxOfOrNull { it.id } ?: 0L) + 1)
        val t = Task(id, message, at, "PENDING")
        write(c, existing + t)
        return t
    }

    @Synchronized
    fun get(c: Context, id: Long): Task? = tasks(c).firstOrNull { it.id == id }

    @Synchronized
    fun setStatus(c: Context, id: Long, status: String, note: String = "") {
        write(c, tasks(c).map { if (it.id == id) it.copy(status = status, note = note) else it })
    }

    @Synchronized
    fun clearFinished(c: Context) {
        write(c, tasks(c).filter { it.status == "PENDING" || it.status == "RUNNING" })
    }

    fun message(c: Context): String = p(c).getString("msg", DEFAULT_MSG) ?: DEFAULT_MSG
    fun setMessage(c: Context, m: String) = p(c).edit().putString("msg", m).apply()

    fun offsetMin(c: Context): Int = p(c).getInt("offset", 2)
    fun setOffset(c: Context, m: Int) = p(c).edit().putInt("offset", m).apply()

    @Synchronized
    fun log(c: Context, line: String) {
        val stamp = java.text.SimpleDateFormat("d MMM h:mm:ss a", java.util.Locale.getDefault())
            .format(java.util.Date())
        val old = p(c).getString("log", "") ?: ""
        val lines = (old.split("\n").filter { it.isNotBlank() } + "$stamp  $line").takeLast(40)
        p(c).edit().putString("log", lines.joinToString("\n")).apply()
    }

    fun logs(c: Context): List<String> =
        (p(c).getString("log", "") ?: "").split("\n").filter { it.isNotBlank() }
}

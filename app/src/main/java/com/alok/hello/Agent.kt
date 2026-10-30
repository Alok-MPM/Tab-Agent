package com.alok.hello

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * The smart loop: read screen -> ask the brain (Gemini via Vercel) -> do the action -> repeat.
 * The app is only eyes + hands. All the thinking lives on the server, so changing behaviour
 * (prompt, rules) needs a Vercel deploy, not an APK reinstall.
 */
object Agent {
    private const val MAX_STEPS = 25
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    var running = false

    @Volatile
    var waitingForUser = false

    private var runId = 0
    private var goal = ""
    private var steps = 0
    private val history = ArrayList<String>()
    private val nodes = HashMap<Int, AccessibilityNodeInfo>()
    private var svc: HelloService? = null
    private var report: (String) -> Unit = {}

    fun configured(s: HelloService): Boolean =
        Store.agentUrl(s).isNotBlank() && Store.agentToken(s).isNotBlank()

    fun start(s: HelloService, g: String, say: (String) -> Unit) {
        svc = s
        report = say
        goal = g
        steps = 0
        history.clear()
        nodes.clear()
        waitingForUser = false
        running = true
        runId++
        Store.log(s, "Agent goal: ${g.take(100)}")
        val id = runId
        s.collapseBubble()
        s.handler.postDelayed({ step(id) }, 700)
    }

    fun stop(why: String) {
        val s = svc
        running = false
        waitingForUser = false
        runId++
        if (s != null) {
            Store.log(s, "Agent stop: $why")
            s.setSymbol("✗")
            s.handler.postDelayed({ s.setSymbol("H") }, 5000)
        }
    }

    fun answer(text: String) {
        val s = svc ?: return
        history.add("USER NE KAHA: $text")
        waitingForUser = false
        s.setSymbol("…")
        val id = runId
        main.postDelayed({ step(id) }, 600)
    }

    private fun alive(id: Int) = running && id == runId

    private fun step(id: Int) {
        val s = svc ?: return
        if (!alive(id) || waitingForUser) return
        if (steps >= MAX_STEPS) {
            finish(id, "Step limit ($MAX_STEPS) pahunch gaya, ruk gaya.", false)
            return
        }
        steps++
        s.setSymbol("$steps")
        val screen = snapshot(s)
        if (screen == null) {
            history.add("$steps. screen padh nahi paaya")
            main.postDelayed({ step(id) }, 1500)
            return
        }
        Thread {
            var res: JSONObject? = null
            var err: String? = null
            try {
                res = call(s, screen)
            } catch (e: Exception) {
                err = e.message ?: e.javaClass.simpleName
            }
            val out = res
            val msg = err
            main.post {
                if (alive(id)) {
                    if (out == null) finish(id, "Server error: $msg", false) else act(id, out)
                }
            }
        }.start()
    }

    // ---------- eyes ----------

    private fun snapshot(s: HelloService): String? {
        val root = s.appRoot() ?: return null
        nodes.clear()
        val sb = StringBuilder()
        sb.append("app package: ").append(root.packageName).append('\n')
        var n = 0

        fun walk(x: AccessibilityNodeInfo, depth: Int) {
            if (n >= 150 || depth > 40) return
            if (x.isVisibleToUser) {
                val text = if (x.isPassword) "<password field>" else x.text?.toString()
                val desc = x.contentDescription?.toString()
                val interesting = x.isClickable || x.isEditable || x.isScrollable ||
                    !text.isNullOrBlank() || !desc.isNullOrBlank()
                if (interesting) {
                    n++
                    nodes[n] = x
                    sb.append('[').append(n).append("] ")
                    sb.append(x.className?.toString()?.substringAfterLast('.') ?: "View")
                    if (!text.isNullOrBlank()) sb.append(" \"").append(text.take(100).replace('\n', ' ')).append('"')
                    if (!desc.isNullOrBlank()) sb.append(" (desc: \"").append(desc.take(80).replace('\n', ' ')).append("\")")
                    if (x.isClickable) sb.append(" click")
                    if (x.isEditable) sb.append(" edit")
                    if (x.isScrollable) sb.append(" scroll")
                    if (x.isChecked) sb.append(" checked")
                    sb.append('\n')
                }
            }
            for (i in 0 until x.childCount) {
                val c = x.getChild(i) ?: continue
                walk(c, depth + 1)
            }
        }
        walk(root, 0)
        return if (n == 0) null else sb.toString()
    }

    // ---------- brain ----------

    private fun call(s: HelloService, screen: String): JSONObject {
        val body = JSONObject()
            .put("goal", goal)
            .put("history", JSONArray(history.takeLast(14)))
            .put("screen", screen)
            .put("step", steps)
        val c = URL(Store.agentUrl(s)).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 15000
        c.readTimeout = 45000
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("x-agent-token", Store.agentToken(s))
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val txt = stream?.bufferedReader()?.readText() ?: ""
        if (code == 429) throw RuntimeException("Gemini ki limit khatam (429). Thodi der baad try karo.")
        if (code == 401) throw RuntimeException("Token galat hai (401). Hello app mein Agent token check karo.")
        if (code !in 200..299) throw RuntimeException("HTTP $code ${txt.take(160)}")
        return JSONObject(txt)
    }

    // ---------- hands ----------

    private fun act(id: Int, o: JSONObject) {
        val thought = o.optString("thought")
        val arr = o.optJSONArray("actions")
        if (arr == null || arr.length() == 0) {
            history.add("$steps. model ne koi action nahi diya")
            main.postDelayed({ step(id) }, 1200)
            return
        }
        runActions(id, arr, 0, thought)
    }

    private fun runActions(id: Int, arr: JSONArray, i: Int, thought: String) {
        val s = svc ?: return
        if (!alive(id)) return
        if (i >= arr.length()) {
            main.postDelayed({ step(id) }, 1400)
            return
        }
        val a = arr.optJSONObject(i)
        if (a == null) {
            runActions(id, arr, i + 1, thought)
            return
        }
        val type = a.optString("type")
        if (type == "done") {
            finish(id, a.optString("summary", "Ho gaya."), true)
            return
        }
        if (type == "ask_user") {
            askUser(id, a.optString("question", "Mujhe aapki madad chahiye."))
            return
        }
        val d = execute(s, type, a)
        history.add("$steps. ${thought.take(90)} -> $d")
        Store.log(s, "Agent: $d")
        val gap = if (type == "open_app") 2500L else 900L
        main.postDelayed({ runActions(id, arr, i + 1, thought) }, gap)
    }

    private fun label(n: AccessibilityNodeInfo): String =
        (n.text?.toString() ?: n.contentDescription?.toString() ?: "").take(40)

    private fun execute(s: HelloService, type: String, a: JSONObject): String {
        val nid = a.optInt("id", -1)
        val node = nodes[nid]
        return when (type) {
            "click" -> when {
                node == null -> "click: id $nid nahi mila"
                s.clickNode(node) -> "click [$nid] ${label(node)}"
                else -> "click FAIL [$nid] ${label(node)}"
            }
            "type" -> {
                val text = a.optString("text")
                when {
                    node == null -> "type: id $nid nahi mila"
                    s.setText(node, text) -> "type [$nid] '${text.take(40)}'"
                    else -> "type FAIL [$nid]"
                }
            }
            "scroll" -> {
                val dir = a.optString("dir", "down")
                val target = nodes.values.firstOrNull { it.isScrollable }
                val act = if (dir == "up") AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                if (target != null && target.performAction(act)) "scroll $dir" else "scroll FAIL"
            }
            "back" -> {
                s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                "back"
            }
            "home" -> {
                s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                "home"
            }
            "open_app" -> {
                val name = a.optString("name")
                if (Commands.openApp(s, name)) "open_app $name" else "open_app FAIL $name"
            }
            "wait" -> "wait"
            else -> "unknown action '$type'"
        }
    }

    private fun askUser(id: Int, q: String) {
        val s = svc ?: return
        waitingForUser = true
        s.setSymbol("?")
        history.add("$steps. agent ne poocha: $q")
        Store.log(s, "Agent poochh raha: ${q.take(80)}")
        Notifier.show(s, "Hello ko aapki zaroorat hai", q)
        report("🙋 $q\n\n(Jawab niche likho aur Send dabao. 'stop' = band.)")
    }

    private fun finish(id: Int, msg: String, ok: Boolean) {
        val s = svc ?: return
        if (id != runId) return
        running = false
        waitingForUser = false
        Store.log(s, (if (ok) "Agent done: " else "Agent FAIL: ") + msg.take(120))
        s.setSymbol(if (ok) "✓" else "✗")
        s.handler.postDelayed({ s.setSymbol("H") }, 6000)
        Notifier.show(s, if (ok) "Hello: kaam ho gaya" else "Hello: ruk gaya", msg)
        report(msg)
    }
}

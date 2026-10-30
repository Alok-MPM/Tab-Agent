package com.alok.hello

/** Rule-based command parser. No AI, so it is instant and never runs out of limit. */
object Commands {

    const val HELP = "Commands:\n" +
        "• limit 2:30 pm  (ya: limit in 3h 20m)\n" +
        "• limit 2:30 pm msg: apna lamba message\n" +
        "• scan  → screen padh ke reset time nikalo\n" +
        "• now  → abhi bhejo\n" +
        "• msg <text>  → default message badlo\n" +
        "• youtube kholo | chrome kholo | open <app>\n" +
        "• youtube search physics rapid revision\n" +
        "• list | cancel | offset 3"

    private val openTriggers = setOf(
        "open", "kholo", "khol", "kholdo", "kholna", "launch", "start", "chalu", "chalao", "lagao"
    )
    private val fillers = setOf(
        "karo", "kar", "do", "de", "dena", "app", "please", "pls", "ko", "ka", "the", "pe", "par",
        "me", "mein", "mai", "ek", "zara"
    )
    private val searchWords = setOf("search", "dhundo", "dhoondo", "find", "play", "video", "videos")

    private fun launch(s: HelloService, i: android.content.Intent): Boolean {
        return try {
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            s.startActivity(i)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Finds an installed launcher app by (partial) name: exact > starts-with > contains. */
    private fun findApp(s: HelloService, name: String): android.content.pm.ResolveInfo? {
        val pm = s.packageManager
        val main = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(main, 0)
        fun label(r: android.content.pm.ResolveInfo) = r.loadLabel(pm).toString().lowercase()
        return apps.firstOrNull { label(it) == name }
            ?: apps.firstOrNull { label(it).startsWith(name) }
            ?: apps.firstOrNull { label(it).contains(name) }
    }

    /** Handles "youtube kholo", "open chrome", "youtube search <query>". Returns true if it took the command. */
    private fun openCommand(s: HelloService, low: String, reply: (String) -> Unit): Boolean {
        val words = low.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.none { it in openTriggers || it in searchWords }) return false

        if ("youtube" in words && words.any { it in searchWords || it in openTriggers } &&
            words.any { it in searchWords }
        ) {
            val q = words.filterNot {
                it == "youtube" || it in openTriggers || it in searchWords || it in fillers
            }.joinToString(" ")
            if (q.isNotEmpty()) {
                s.collapseBubble()
                val uri = android.net.Uri.parse(
                    "https://www.youtube.com/results?search_query=" + android.net.Uri.encode(q)
                )
                val i = android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
                    .setPackage("com.google.android.youtube")
                val ok = launch(s, i) ||
                    launch(s, android.content.Intent(android.content.Intent.ACTION_VIEW, uri))
                reply(if (ok) "YouTube pe search: $q" else "YouTube khol nahi paaya.")
                return true
            }
        }

        val name = words.filterNot { it in openTriggers || it in fillers }.joinToString(" ")
        if (name.isEmpty()) return false
        val app = findApp(s, name)
        if (app == null) {
            reply("'$name' naam ka app nahi mila.")
            return true
        }
        s.collapseBubble()
        val i = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            .setClassName(app.activityInfo.packageName, app.activityInfo.name)
        val label = app.loadLabel(s.packageManager)
        reply(if (launch(s, i)) "$label khol diya." else "$label khol nahi paaya.")
        return true
    }

    fun handle(s: HelloService, raw: String, reply: (String) -> Unit) {
        val text = raw.trim()
        if (text.isEmpty()) {
            reply(HELP)
            return
        }
        val low = text.lowercase()
        val msgIdx = low.indexOf("msg:")
        val customMsg = if (msgIdx >= 0) text.substring(msgIdx + 4).trim().ifEmpty { null } else null
        val head = if (msgIdx >= 0) text.substring(0, msgIdx).trim() else text
        val headLow = head.lowercase()

        when {
            headLow == "scan" || headLow.contains("screen") -> s.scanThen(reply)

            headLow == "now" || headLow == "abhi" -> {
                val t = Store.add(s, customMsg ?: Store.message(s), System.currentTimeMillis())
                reply("Bhej raha hoon…")
                s.collapseBubble()
                s.handler.postDelayed({ s.runTask(t.id) }, 400)
            }

            headLow == "list" -> {
                val l = Store.tasks(s).takeLast(8)
                reply(
                    if (l.isEmpty()) "Koi task nahi."
                    else l.joinToString("\n") { "• ${HelloService.fmt(it.triggerAt)}  ${it.status}  ${it.message.take(30)}" }
                )
            }

            headLow.startsWith("cancel") -> {
                val p = Store.tasks(s).filter { it.status == "PENDING" }
                p.forEach {
                    Scheduler.cancel(s, it.id)
                    Store.setStatus(s, it.id, "FAILED", "cancelled")
                }
                reply("${p.size} task cancel kiye.")
            }

            headLow.startsWith("msg ") -> {
                val m = head.substring(4).trim()
                Store.setMessage(s, m)
                reply("Default message set:\n$m")
            }

            headLow.startsWith("offset") -> {
                val n = Regex("""\d+""").find(head)?.value?.toIntOrNull()
                if (n == null) reply("Aise likho: offset 3")
                else {
                    Store.setOffset(s, n)
                    reply("Ab reset ke $n min baad bhejunga.")
                }
            }

            else -> {
                if (openCommand(s, headLow, reply)) return
                val at = ResetTimeParser.parse(head)
                if (at != null) reply(s.scheduleAt(at, customMsg))
                else reply("Samajh nahi aaya.\n$HELP")
            }
        }
    }
}

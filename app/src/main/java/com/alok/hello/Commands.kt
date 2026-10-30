package com.alok.hello

/** Rule-based command parser. No AI, so it is instant and never runs out of limit. */
object Commands {

    const val HELP = "Commands:\n" +
        "• limit 2:30 pm  (ya: limit in 3h 20m)\n" +
        "• limit 2:30 pm msg: apna lamba message\n" +
        "• scan  → screen padh ke reset time nikalo\n" +
        "• now  → abhi bhejo\n" +
        "• msg <text>  → default message badlo\n" +
        "• list | cancel | offset 3"

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

            headLow.startsWith("now") || headLow.startsWith("abhi") -> {
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
                val at = ResetTimeParser.parse(head)
                if (at != null) reply(s.scheduleAt(at, customMsg))
                else reply("Samajh nahi aaya.\n$HELP")
            }
        }
    }
}

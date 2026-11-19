package org.gptbot

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 每个会话最近说过的话（包括机器人自己的），只用于判定模型看"群里在聊什么"。
 * 独立于聊天记忆：聊天关闭时也照常记录，判定才有上下文。
 */
object GroupContext {

    private const val MAX_LINES = 24
    private const val MAX_CHARS = 120

    private val lines = ConcurrentHashMap<String, ArrayDeque<String>>()
    private val lock = Any()

    fun add(key: String, line: String) {
        val clean = line.replace(Regex("\\s+"), " ").trim()
        if (clean.isEmpty()) return
        synchronized(lock) {
            val queue = lines.getOrPut(key) { ArrayDeque() }
            queue.addLast(if (clean.length > MAX_CHARS) clean.take(MAX_CHARS) + "…" else clean)
            while (queue.size > MAX_LINES) queue.removeFirst()
        }
    }

    fun recent(key: String, n: Int): List<String> = synchronized(lock) {
        val queue = lines[key] ?: return emptyList()
        queue.toList().takeLast(n.coerceAtLeast(1))
    }

    fun clear(key: String) {
        synchronized(lock) { lines.remove(key) }
    }
}

/**
 * 判定随记：机器人自己攒的"什么情况该接话 / 什么情况别插嘴"的经验。
 * 每个会话存一份纯文本（data/org.gptbot.agent/judgenotes/<key>.txt），
 * 由模型周期性复盘最近群聊后更新，下次判定时直接带上。
 */
object JudgeNotes {

    private val cache = ConcurrentHashMap<String, String>()

    private val dir: File get() = File("./data/${AgentConfig.PLUGIN_ID}/judgenotes")

    private fun file(key: String): File =
        File(dir, key.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".txt")

    fun get(key: String): String = cache.getOrPut(key) {
        runCatching {
            val f = file(key)
            if (f.exists()) f.readText().removePrefix("\uFEFF").trim() else ""
        }.getOrDefault("")
    }

    fun set(key: String, note: String) {
        val text = note.trim()
        cache[key] = text
        runCatching {
            dir.mkdirs()
            file(key).writeText(text)
        }
    }

    fun clear(key: String) {
        cache.remove(key)
        runCatching { file(key).delete() }
    }
}

/**
 * 群友对"说多说少"的偏好反馈，按原话记下来（永久保存到
 * data/org.gptbot.agent/feedback/<key>.txt）。
 * 不做任何全局松紧调控，只作为证据交给复盘模型，由它自己写进随记。
 */
object FeedbackLog {

    private const val MAX_ENTRIES = 8

    private val cache = ConcurrentHashMap<String, MutableList<String>>()
    private val lock = Any()
    private val fmt = java.text.SimpleDateFormat("MM-dd HH:mm")

    private val dir: File get() = File("./data/${AgentConfig.PLUGIN_ID}/feedback")

    private fun file(key: String): File =
        File(dir, key.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".txt")

    fun recent(key: String, n: Int = 4): List<String> = synchronized(lock) {
        entries(key).takeLast(n)
    }

    fun add(key: String, line: String) {
        val text = line.replace(Regex("\\s+"), " ").trim().take(100)
        if (text.isEmpty()) return
        synchronized(lock) {
            val list = entries(key)
            list.add("[" + fmt.format(java.util.Date()) + "] " + text)
            while (list.size > MAX_ENTRIES) list.removeAt(0)
            runCatching {
                dir.mkdirs()
                file(key).writeText(list.joinToString("\n"))
            }
        }
    }

    private fun entries(key: String): MutableList<String> = cache.getOrPut(key) {
        runCatching {
            val f = file(key)
            if (f.exists()) {
                f.readText().removePrefix("\uFEFF").lines()
                    .map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
            } else mutableListOf()
        }.getOrElse { mutableListOf() }
    }
}

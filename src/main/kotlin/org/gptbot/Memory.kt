package org.gptbot

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

/** 一条对话记录 */
data class ChatMessage(val role: String, val content: String)

/**
 * 每个群 / 每个私聊一份独立记忆，落在 data/org.gptbot.agent/memory/<key>.json
 *
 * 除最近若干条原文外，还保留一段"历史摘要"：消息积累到一定条数后由模型压缩成摘要，
 * 原文另存到 memory/backup/ 下，之后上下文里只带摘要 + 最近几条，
 * 让模型知道聊过什么、但不会接着聊早就结束的老话题。
 */
class ChatMemory(val key: String, private val cfg: AgentConfig.Config) {

    private val file: File
        get() = File(AgentConfig.dataDir, "memory/$key.json")

    private val backupDir: File
        get() = File(AgentConfig.dataDir, "memory/backup")

    private val messages = mutableListOf<ChatMessage>()

    private var summary: String = ""

    /** 上次总结之后又新增了多少条 */
    private var sinceSummary: Int = 0

    @Synchronized
    fun load() {
        messages.clear()
        summary = ""
        sinceSummary = 0
        if (!file.exists()) return
        runCatching {
            val text = file.readText().trim()
            if (text.startsWith("{")) {
                val obj = JSONObject(text)
                summary = obj.optString("summary", "")
                sinceSummary = obj.optInt("sinceSummary", 0)
                readMessages(obj.optJSONArray("messages"))
            } else {
                // 兼容 2.0.0 早期的纯数组格式
                readMessages(JSONArray(text))
            }
        }
    }

    private fun readMessages(arr: JSONArray?) {
        if (arr == null) return
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val role = o.optString("role", "user")
            val content = o.optString("content", "")
            if (content.isNotBlank()) messages += ChatMessage(role, content)
        }
    }

    @Synchronized
    fun append(role: String, content: String) {
        if (content.isBlank()) return
        messages += ChatMessage(role, content)
        sinceSummary++
        trim()
        save()
    }

    /** chatempty：连摘要一起丢掉，从零开始 */
    @Synchronized
    fun clear() {
        messages.clear()
        summary = ""
        sinceSummary = 0
        runCatching { if (file.exists()) file.delete() }
    }

    @Synchronized
    fun size(): Int = messages.size

    @Synchronized
    fun summaryText(): String = summary

    @Synchronized
    fun pendingForSummary(): Int = sinceSummary

    /** 供模型请求使用的快照（已按条数/字符上限裁剪） */
    @Synchronized
    fun snapshot(): List<ChatMessage> = messages.toList()

    @Synchronized
    fun save() {
        trim()
        runCatching {
            file.parentFile?.mkdirs()
            val arr = JSONArray()
            messages.forEach {
                arr.put(JSONObject().put("role", it.role).put("content", it.content))
            }
            val root = JSONObject()
                .put("key", key)
                .put("summary", summary)
                .put("sinceSummary", sinceSummary)
                .put("messages", arr)
            file.writeText(root.toString())
        }
    }

    // ------------------------------------------------------------ 总结 & 备份

    @Synchronized
    fun needsSummary(): Boolean =
        cfg.memory.summaryEnabled && sinceSummary >= cfg.memory.summaryEveryMessages.coerceAtLeast(4)

    /** 待压缩的老消息（保留最后 keepRecent 条原文） */
    @Synchronized
    fun historyForSummary(keepRecent: Int): List<ChatMessage> {
        val keep = keepRecent.coerceAtLeast(0)
        return if (messages.size <= keep) emptyList() else messages.dropLast(keep)
    }

    /**
     * 用新摘要替换掉老消息：保留最后 keepRecent 条原文，其余丢弃（原文已备份）。
     */
    @Synchronized
    fun applySummary(text: String, keepRecent: Int) {
        backup("summary")
        summary = text.trim()
        val keep = messages.takeLast(keepRecent.coerceAtLeast(0))
        messages.clear()
        messages.addAll(keep)
        sinceSummary = 0
        save()
    }

    /** 只是把计数清零（没有可压缩内容时用） */
    @Synchronized
    fun markSummarized() {
        sinceSummary = 0
        save()
    }

    /** 把当前完整对话（含摘要）另存一份到 memory/backup/，人可读的 md */
    @Synchronized
    fun backup(reason: String) {
        if (!cfg.memory.backupEnabled) return
        runCatching {
            backupDir.mkdirs()
            val ts = SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())
            val out = File(backupDir, "$key-$ts-$reason.md")
            val sb = StringBuilder()
            sb.append("# 备份 $key\n\n")
            sb.append("- 时间：").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())).append("\n")
            sb.append("- 原因：").append(reason).append("\n")
            sb.append("- 条数：").append(messages.size).append("\n\n")
            if (summary.isNotBlank()) sb.append("## 已有摘要\n\n").append(summary).append("\n\n")
            sb.append("## 对话原文\n\n")
            messages.forEach { sb.append("**").append(it.role).append("**：").append(it.content).append("\n\n") }
            out.writeText(sb.toString())
        }
    }

    private fun trim() {
        while (messages.size > cfg.bot.maxHistoryMessages) messages.removeAt(0)
        while (messages.isNotEmpty() && messages.sumOf { it.content.length } > cfg.bot.maxHistoryChars) {
            messages.removeAt(0)
        }
        // 保证首条不是 assistant，避免部分模型对开头角色的限制
        while (messages.isNotEmpty() && messages.first().role != "user") messages.removeAt(0)
    }

    companion object {
        fun group(id: Long): String = "group-$id"
        fun friend(id: Long): String = "private-$id"
    }
}

package org.gptbot

import org.json.JSONObject
import java.io.File

/**
 * 每个会话的聊天开关（chaton / chatoff 硬指令），落在 data/org.gptbot.agent/chatstate.json。
 * 关闭后该会话的消息既不回复也不写入记忆；重启后状态保留。
 */
object ChatState {

    private val file: File get() = File(AgentConfig.dataDir, "chatstate.json")

    private val map = mutableMapOf<String, Boolean>()

    private var loaded = false

    @Synchronized
    fun isOn(key: String, default: Boolean): Boolean {
        ensureLoaded()
        return map[key] ?: default
    }

    @Synchronized
    fun set(key: String, on: Boolean) {
        ensureLoaded()
        map[key] = on
        persist()
    }

    @Synchronized
    fun reset() {
        ensureLoaded()
        map.clear()
        persist()
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        runCatching {
            if (file.isFile) {
                val obj = JSONObject(file.readText())
                obj.keys().forEach { k -> map[k] = obj.optBoolean(k, true) }
            }
        }
    }

    private fun persist() {
        runCatching {
            file.parentFile?.mkdirs()
            val obj = JSONObject()
            map.forEach { (k, v) -> obj.put(k, v) }
            file.writeText(obj.toString())
        }
    }
}

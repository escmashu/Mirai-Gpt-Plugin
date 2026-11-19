package org.gptbot

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 插件配置。首次启动时会把默认配置写到 config/org.gptbot.agent/config.json。
 */
object AgentConfig {

    const val PLUGIN_ID = "org.gptbot.agent"

    private val configFile: File get() = File("./config/$PLUGIN_ID/config.json")

    /** 实际读取的配置文件绝对路径，用于排查"改了配置没生效" */
    val configPath: String get() = configFile.absoluteFile.normalize().path

    /** 由 load() 填充；插件启用前不要读取 */
    @Volatile
    lateinit var current: Config

    val pluginDir: File get() = File("./config/$PLUGIN_ID")
    val dataDir: File get() = File("./data/$PLUGIN_ID")

    fun load(): Config {
        pluginDir.mkdirs()
        dataDir.mkdirs()
        if (!configFile.exists()) {
            configFile.writeText(DEFAULT_JSON)
        }
        // 去掉 UTF-8 BOM：Windows 记事本 / PowerShell 保存的文件常带 BOM，org.json 会直接抛异常
        val text = configFile.readText().removePrefix("\uFEFF")
        val cfg = try {
            parse(JSONObject(text))
        } catch (e: Exception) {
            Config.create().also { it.parseError = "配置解析失败：${e.message}" }
        }
        current = cfg
        return cfg
    }

    fun rawFile(): File = configFile

    data class Llm(
        val baseUrl: String,
        val apiKey: String,
        val model: String,
        val temperature: Double,
        val maxTokens: Int,
        val timeoutSeconds: Int,
        val maxToolRounds: Int,
        val extraHeaders: Map<String, String>,
        /** 关掉推理模型的思考过程（reasoning_effort=none），能大幅降低延迟 */
        val disableReasoning: Boolean,
        /** 原样合并进请求体的额外参数，留空即可 */
        val extraBody: JSONObject,
        val vision: Vision,
    )

    /** 图片理解（多模态输入） */
    data class Vision(
        val enabled: Boolean,
        val maxImages: Int,
        val maxBytes: Int,
        val maxEdge: Int,
    )

    /**
     * 单个会话（群 / 私聊）的行为覆盖，对应文件 data/org.gptbot.agent/personas/<key>.json 可选。
     */
    data class ChatOverride(
        val personaFile: String = "",
        val model: String = "",
        val enabled: Boolean = true,
    )

    data class Bot(
        val enabledGroups: List<Long>,
        val enabledPrivate: Boolean,
        val admins: List<Long>,
        val maxHistoryMessages: Int,
        val maxHistoryChars: Int,
        val maxReplyChars: Int,
        val maxSentences: Int,
        val commandPrefix: String,
        val chatDefaultOn: Boolean,
        val chatControlAdminsOnly: Boolean,
        /** 允许模型在需要长描述时把回复拆成多段，逐条发出去 */
        val multiSegmentEnabled: Boolean,
        /** 多段之间的间隔（毫秒） */
        val segmentDelayMs: Int,
        /** 最多拆成几段 */
        val maxSegments: Int,
    )

    /** 记忆压缩与备份 */
    data class MemoryCfg(
        val summaryEnabled: Boolean,
        val summaryEveryMessages: Int,
        val summaryKeepRecent: Int,
        val summaryModel: String,
        val summaryMaxChars: Int,
        val backupEnabled: Boolean,
    )

    data class Trigger(
        val keywords: List<String>,
        val requireAtInGroup: Boolean,
        val smartJudgeEnabled: Boolean,
        val smartJudgeModel: String,
        val smartJudgePrompt: String,
        val parenRuleEnabled: Boolean,
        /** 判定时带上多少条最近的群聊记录 */
        val judgeHistorySize: Int,
        /** 是否让机器人自己攒判定随记（复盘最近群聊，更新"该不该接话"的经验） */
        val judgeNotesEnabled: Boolean,
        /** 每判定多少次复盘一次随记；0 表示只在随记为空时生成 */
        val judgeNotesEvery: Int,
        /** 随记字数上限，0 = 不限 */
        val judgeNotesMaxChars: Int,
    )

    data class Persona(
        val systemPrompt: String,
        val personaDir: String,
    )

    /**
     * 立绘与表情包：路径相对于 config/org.gptbot.agent/。
     * 表情名是模型在回复里写 [表情:名称] 时用的键，一个文件可以被多个情绪名共用。
     */
    data class Assets(
        val portrait: String,
        val stickers: Map<String, String>,
    )

    /**
     * 站点积分发放（外部积分接口）。关掉时模型看不到这两个工具。
     * 账号绑定表在 config/org.gptbot.agent/accounts.json，发放记录在 data/org.gptbot.agent/credit_ledger.json。
     */
    data class Credit(
        val enabled: Boolean,
        val baseUrl: String,
        val grantPath: String,
        /** 可选的"账号是否存在"查询模板，支持 {username} 占位；留空则用发放接口的 404 兜底判断 */
        val userCheckPath: String,
        val apiKey: String,
        val minAmount: Int,
        val maxAmount: Int,
        /** 钱包主人以外的人单次上限（默认先推辞，真给了也给得少） */
        val guestMaxAmount: Int,
        val dailyCap: Int,
        val timeoutSeconds: Int,
        val siteUrl: String,
        /** 钱包主人的 QQ 与称呼：他本人要积分时不推辞、可以给到 maxAmount */
        val walletOwnerQq: Long,
        val walletOwnerName: String,
        /** 发放原因模板，随机挑一条，要求体现是透子给的 */
        val reasons: List<String>,
    )

    data class Tools(
        val enabled: Boolean,
        val sandboxDir: String,
        val timeEnabled: Boolean,
        val httpEnabled: Boolean,
        val httpAllowedHosts: List<String>,
        val httpMaxResponseChars: Int,
        val httpTimeoutSeconds: Int,
        val httpAllowPost: Boolean,
        val fileReadEnabled: Boolean,
        val fileReadMaxBytes: Int,
        val fileWriteEnabled: Boolean,
        val fileWriteMaxBytes: Int,
        val listDirEnabled: Boolean,
        val listDirMaxEntries: Int,
    )

    data class Config(
        val llm: Llm,
        val bot: Bot,
        val memory: MemoryCfg,
        val trigger: Trigger,
        val persona: Persona,
        val tools: Tools,
        val assets: Assets,
        val credit: Credit,
        var parseError: String? = null,
    ) {
        companion object {
            fun create(): Config = parse(JSONObject(DEFAULT_JSON))
        }
    }

    private fun parse(root: JSONObject): Config {
        val llm = root.optJSONObject("llm") ?: JSONObject()
        val bot = root.optJSONObject("bot") ?: JSONObject()
        val trigger = root.optJSONObject("trigger") ?: JSONObject()
        val memory = root.optJSONObject("memory") ?: JSONObject()
        val persona = root.optJSONObject("persona") ?: JSONObject()
        val tools = root.optJSONObject("tools") ?: JSONObject()
        val http = tools.optJSONObject("http") ?: JSONObject()
        val fileRead = tools.optJSONObject("fileRead") ?: JSONObject()
        val fileWrite = tools.optJSONObject("fileWrite") ?: JSONObject()
        val listDir = tools.optJSONObject("listDir") ?: JSONObject()
        val judge = trigger.optJSONObject("smartJudge") ?: JSONObject()
        val assets = root.optJSONObject("assets") ?: JSONObject()
        val credit = root.optJSONObject("credit") ?: JSONObject()

        return Config(
            llm = Llm(
                baseUrl = llm.optString("baseUrl", "https://api.deepseek.com/v1").trimEnd('/'),
                apiKey = llm.optString("apiKey", ""),
                model = llm.optString("model", "deepseek-chat"),
                temperature = llm.optDouble("temperature", 0.8),
                maxTokens = llm.optInt("maxTokens", 1024),
                timeoutSeconds = llm.optInt("timeoutSeconds", 120),
                maxToolRounds = llm.optInt("maxToolRounds", 4),
                extraHeaders = llm.optJSONObject("extraHeaders").let { obj ->
                    if (obj == null) emptyMap() else obj.keys().asSequence()
                        .associateWith { obj.optString(it, "") }
                },
                disableReasoning = llm.optBoolean("disableReasoning", true),
                extraBody = llm.optJSONObject("extraBody") ?: JSONObject(),
                vision = (llm.optJSONObject("vision") ?: JSONObject()).let { v ->
                    Vision(
                        enabled = v.optBoolean("enabled", true),
                        maxImages = v.optInt("maxImages", 2),
                        maxBytes = v.optInt("maxBytes", 1048576),
                        maxEdge = v.optInt("maxEdge", 1280),
                    )
                },
            ),
            bot = Bot(
                enabledGroups = bot.optJSONArray("enabledGroups").toLongList(),
                enabledPrivate = bot.optBoolean("enabledPrivate", true),
                admins = bot.optJSONArray("admins").toLongList(),
                maxHistoryMessages = bot.optInt("maxHistoryMessages", 40),
                maxHistoryChars = bot.optInt("maxHistoryChars", 12000),
                maxReplyChars = bot.optInt("maxReplyChars", 800),
                maxSentences = bot.optInt("maxSentences", 1),
                commandPrefix = bot.optString("commandPrefix", "/ai"),
                chatDefaultOn = bot.optBoolean("chatDefaultOn", true),
                chatControlAdminsOnly = bot.optBoolean("chatControlAdminsOnly", false),
                multiSegmentEnabled = bot.optBoolean("multiSegmentEnabled", true),
                segmentDelayMs = bot.optInt("segmentDelayMs", 2000).coerceIn(0, 10000),
                maxSegments = bot.optInt("maxSegments", 3).coerceIn(1, 6),
            ),
            memory = MemoryCfg(
                summaryEnabled = memory.optBoolean("summaryEnabled", true),
                summaryEveryMessages = memory.optInt("summaryEveryMessages", 20),
                summaryKeepRecent = memory.optInt("summaryKeepRecent", 10),
                summaryModel = memory.optString("summaryModel", ""),
                summaryMaxChars = memory.optInt("summaryMaxChars", 300),
                backupEnabled = memory.optBoolean("backupEnabled", true),
            ),
            trigger = Trigger(
                keywords = trigger.optJSONArray("keywords").toStringList(),
                requireAtInGroup = trigger.optBoolean("requireAtInGroup", false),
                smartJudgeEnabled = judge.optBoolean("enabled", true),
                smartJudgeModel = judge.optString("model", ""),
                smartJudgePrompt = judge.optString(
                    "prompt",
                    "你在判断群聊里机器人是否需要发言。只输出 YES 或 NO。"
                ),
                parenRuleEnabled = trigger.optBoolean("parenRuleEnabled", true),
                judgeHistorySize = judge.optInt("historySize", 14),
                judgeNotesEnabled = judge.optBoolean("notesEnabled", true),
                judgeNotesEvery = judge.optInt("notesEvery", 10),
                judgeNotesMaxChars = judge.optInt("notesMaxChars", 0),
            ),
            persona = Persona(
                systemPrompt = persona.optString("systemPrompt", ""),
                personaDir = persona.optString("personaDir", "personas"),
            ),
            tools = Tools(
                enabled = tools.optBoolean("enabled", true),
                sandboxDir = tools.optString("sandboxDir", "./data/$PLUGIN_ID/workspace"),
                timeEnabled = tools.optBoolean("timeEnabled", true),
                httpEnabled = http.optBoolean("enabled", true),
                httpAllowedHosts = http.optJSONArray("allowedHosts").toStringList(),
                httpMaxResponseChars = http.optInt("maxResponseChars", 4000),
                httpTimeoutSeconds = http.optInt("timeoutSeconds", 30),
                httpAllowPost = http.optBoolean("allowPost", false),
                fileReadEnabled = fileRead.optBoolean("enabled", true),
                fileReadMaxBytes = fileRead.optInt("maxBytes", 65536),
                fileWriteEnabled = fileWrite.optBoolean("enabled", false),
                fileWriteMaxBytes = fileWrite.optInt("maxBytes", 65536),
                listDirEnabled = listDir.optBoolean("enabled", true),
                listDirMaxEntries = listDir.optInt("maxEntries", 100),
            ),
            assets = Assets(
                portrait = assets.optString("portrait", ""),
                stickers = assets.optJSONObject("stickers").let { obj ->
                    if (obj == null) emptyMap() else obj.keys().asSequence()
                        .associateWith { obj.optString(it, "") }
                        .filterValues { it.isNotBlank() }
                },
            ),
            credit = Credit(
                enabled = credit.optBoolean("enabled", false),
                baseUrl = credit.optString("baseUrl", "https://www.escmashu.cn").trimEnd('/'),
                grantPath = credit.optString("grantPath", "/api/external/credits/grant")
                    .let { if (it.startsWith("/")) it else "/$it" },
                userCheckPath = credit.optString("userCheckPath", ""),
                apiKey = credit.optString("apiKey", ""),
                minAmount = credit.optInt("minAmount", 1),
                maxAmount = credit.optInt("maxAmount", 10),
                guestMaxAmount = credit.optInt("guestMaxAmount", 3),
                dailyCap = credit.optInt("dailyCap", 10),
                timeoutSeconds = credit.optInt("timeoutSeconds", 20),
                siteUrl = credit.optString("siteUrl", "https://www.escmashu.cn/"),
                walletOwnerQq = credit.optLong("walletOwnerQq", 645937001L),
                walletOwnerName = credit.optString("walletOwnerName", "麻薯"),
                reasons = credit.optJSONArray("reasons").toStringList().ifEmpty {
                    listOf("透子的礼物~", "透子给的零花钱", "透子请你吃糖~", "透子随手塞的一点心意")
                },
            ),
        )
    }

    private fun JSONArray?.toLongList(): List<Long> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { i -> optLong(i, 0L).takeIf { it > 0L } }
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { i -> optString(i, "") }.filter { it.isNotBlank() }
    }

    val DEFAULT_JSON: String by lazy {
        """
{
  "llm": {
    "baseUrl": "https://api.deepseek.com/v1",
    "apiKey": "REPLACE_ME",
    "model": "deepseek-chat",
    "temperature": 0.8,
    "maxTokens": 1024,
    "timeoutSeconds": 120,
    "maxToolRounds": 4,
    "disableReasoning": true,
    "vision": { "enabled": true, "maxImages": 2, "maxBytes": 1048576, "maxEdge": 1280 },
    "extraBody": {},
    "extraHeaders": {}
  },
  "bot": {
    "enabledGroups": [1090338744],
    "enabledPrivate": true,
    "admins": [1620165708],
    "maxHistoryMessages": 40,
    "maxHistoryChars": 12000,
    "maxReplyChars": 300,
    "maxSentences": 1,
    "commandPrefix": "/ai",
    "chatDefaultOn": true,
    "chatControlAdminsOnly": false,
    "multiSegmentEnabled": true,
    "segmentDelayMs": 2000,
    "maxSegments": 3
  },
  "memory": {
    "summaryEnabled": true,
    "summaryEveryMessages": 20,
    "summaryKeepRecent": 10,
    "summaryModel": "",
    "summaryMaxChars": 300,
    "backupEnabled": true
  },
  "trigger": {
    "keywords": [],
    "requireAtInGroup": false,
    "parenRuleEnabled": true,
    "smartJudge": {
      "enabled": true,
      "model": "",
      "historySize": 14,
      "notesEnabled": true,
      "notesEvery": 10,
      "notesMaxChars": 0,
      "prompt": "你是 QQ 群里的机器人「透子」，跑团 GM，说话简短自然。根据【最近群聊】判断【触发消息】需不需要你接话：在叫你、问你、等你接话、接着跟你聊 → YES；群里正在聊的话题你顺口接一句自然、不抢戏 → YES；明显是别人之间的无关闲聊、话题已经结束、纯表情包或图片刷屏、你插进去会很突兀 → NO。判断时先看【群友对你说多说少的反馈】和【你的随记】：它们记着这个群什么场合可以多接话、什么场合别插嘴；如果最近有人嫌它话多就收紧，有人希望它多说两句就按随记里那些场合放宽；随记的具体场合优先于上面的通用规则。只输出 YES 或 NO。"
    }
  },
  "assets": {
    "portrait": "portrait/portrait.png",
    "stickers": {
      "坏笑": "stickers/sly.png",
      "开心": "stickers/sly.png",
      "惊吓": "stickers/scared.png",
      "害怕": "stickers/scared.png"
    }
  },
  "credit": {
    "enabled": false,
    "baseUrl": "https://www.escmashu.cn",
    "grantPath": "/api/external/credits/grant",
    "userCheckPath": "",
    "apiKey": "REPLACE_ME",
    "minAmount": 1,
    "maxAmount": 10,
    "guestMaxAmount": 3,
    "dailyCap": 10,
    "timeoutSeconds": 20,
    "siteUrl": "https://www.escmashu.cn/",
    "walletOwnerQq": 645937001,
    "walletOwnerName": "麻薯",
    "reasons": ["透子的礼物~", "透子给的零花钱", "透子请你吃糖~", "透子随手塞的一点心意"]
  },
  "persona": {
    "systemPrompt": "你是一个 QQ 群聊里的 AI 助手，说话自然、简洁，中文回答。不要暴露自己在调用工具或读取配置。不确定的事情不要编造。",
    "personaDir": "personas"
  },
  "tools": {
    "enabled": true,
    "sandboxDir": "./data/org.gptbot.agent/workspace",
    "timeEnabled": true,
    "http": {
      "enabled": true,
      "allowedHosts": ["api.github.com"],
      "maxResponseChars": 4000,
      "timeoutSeconds": 30,
      "allowPost": false
    },
    "fileRead": { "enabled": true, "maxBytes": 65536 },
    "fileWrite": { "enabled": false, "maxBytes": 65536 },
    "listDir": { "enabled": true, "maxEntries": 100 }
  }
}
""".trimIndent()
    }
}

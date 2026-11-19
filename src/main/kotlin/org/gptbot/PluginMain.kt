package org.gptbot

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.mamoe.mirai.console.plugin.jvm.JvmPluginDescription
import net.mamoe.mirai.console.plugin.jvm.KotlinPlugin
import net.mamoe.mirai.contact.Contact
import net.mamoe.mirai.contact.MemberPermission
import net.mamoe.mirai.event.GlobalEventChannel
import net.mamoe.mirai.event.events.FriendMessageEvent
import net.mamoe.mirai.event.events.GroupMessageEvent
import net.mamoe.mirai.message.data.At
import net.mamoe.mirai.message.data.Image
import net.mamoe.mirai.message.data.MessageChain
import net.mamoe.mirai.message.data.PlainText
import net.mamoe.mirai.message.data.QuoteReply
import net.mamoe.mirai.utils.ExternalResource
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * mirai-gpt-agent
 * - 按每个群 / 每个私聊独立记忆，周期性总结 + 备份
 * - 自动判断是否发言（@ / 引用 / 关键词 / 概率 / 可选模型判定）
 * - 硬指令：@机器人 chaton / chatoff / chatempty
 * - OpenAI 兼容接口 + 工具调用（时间、白名单 HTTP、沙箱文件）
 */
object AgentPlugin : KotlinPlugin(
    JvmPluginDescription(
        id = AgentConfig.PLUGIN_ID,
        version = "2.5.3",
    ) {
        name("GPT Chat Agent")
        author("UGit")
        info("分组独立记忆的 LLM 聊天/agent 插件")
    }
) {

    /** 括号规则：对方用了括号（动作/旁白），回复也必须整个放在括号里 */
    private const val PAREN_RULE =
        "【本轮格式要求】对方这条消息里用了括号（表示动作或旁白）。你必须把整条回复放进一对圆括号里，" +
            "只写动作、旁白或一句话的短句，括号外不要出现任何文字，也不要解释自己在做什么。"

    /** 模型用这个标记把一条回复拆成多条消息依次发出 */
    private const val SEGMENT_SEPARATOR = "|||"

    /** 模型写 [表情:坏笑] / 【表情:坏笑】 这种标记，插件把它换成表情图 */
    private val stickerPattern = Regex("[\\[【]\\s*表情[:：]\\s*([^\\]】]{1,8})\\s*[\\]】]")

    /** 图片场景的额外说明，防止模型凭空编造画面 */
    private const val VISION_RULE =
        "【本轮附图】对方这轮发了图片，请先看清图片内容再回答：图里的文字要照原样读，" +
            "看不清或不认识就说看不清，不要编造画面细节；除非对方问，不要主动描述整张图。"

    private val controlCommands = setOf("chaton", "chatoff", "chatempty")

    @Volatile
    private var cfg: AgentConfig.Config = AgentConfig.Config.create()

    @Volatile
    private var llm: LlmClient = LlmClient(cfg, ToolRegistry(cfg))

    private val memories = ConcurrentHashMap<String, ChatMemory>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    /** 复盘随记用的后台协程，不阻塞回复 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 每个会话累计判定次数，用于周期性复盘随记 */
    private val judgeCounter = ConcurrentHashMap<String, Int>()

    /** 用户在嫌它话多 / 明确让它别回时的说法，命中即静默并记进随记 */
    private val complaintPatterns = listOf(
        "不需要你回复", "不需要你说话", "不用你回", "别回复", "不要回复", "别回我", "不用回",
        "别插话", "别插嘴", "插什么嘴", "少说话", "话太多", "话好多", "太吵", "安静点",
        "闭嘴", "别说了", "不要说了", "烦死", "好烦", "别刷屏", "你又来了", "没问你",
        "别说那么多", "说太多了", "话少点", "少说点", "一句就够了",
    )

    /** 群友希望它多说两句时，把原话记下来交给随记去学 */
    private val morePatterns = listOf(
        "多说点", "多说几句", "多说两句", "多聊", "活跃点", "活跃一点", "热闹点", "话多点",
        "别这么闷", "别沉默", "怎么不说话了", "你可以多说", "说详细点", "展开说说", "多讲点",
    )

    override fun onEnable() {
        cfg = AgentConfig.load()
        rebuild()
        logger.info("已加载配置：模型 ${cfg.llm.model}，群白名单 ${cfg.bot.enabledGroups}，私聊 ${if (cfg.bot.enabledPrivate) "开启" else "关闭"}")
        logger.info(
            "[配置] ${AgentConfig.configPath} | 关键词 ${cfg.trigger.keywords} | " +
                "模型判定 ${cfg.trigger.smartJudgeEnabled} | 仅@ ${cfg.trigger.requireAtInGroup} | " +
                "随记 ${if (cfg.trigger.judgeNotesEnabled) "开启（每 ${cfg.trigger.judgeNotesEvery} 次判定复盘）" else "关闭"} | " +
                "多段 " + (
                    if (cfg.bot.multiSegmentEnabled) "开启（≤${cfg.bot.maxSegments} 段，间隔 ${cfg.bot.segmentDelayMs}ms）"
                    else "关闭"
                    )
        )
        logger.info(
            "记忆压缩：${if (cfg.memory.summaryEnabled) "每 ${cfg.memory.summaryEveryMessages} 条总结一次，保留最近 ${cfg.memory.summaryKeepRecent} 条" else "关闭"}；" +
                "聊天默认 ${if (cfg.bot.chatDefaultOn) "开启" else "关闭"}（@我 chaton/chatoff 切换）"
        )
        logger.info(
            "思考过程：" + if (cfg.llm.disableReasoning) "已关闭（reasoning_effort=none，回复更快）"
            else "已开启（较慢，且会额外计费）"
        )
        if (cfg.llm.apiKey.isBlank() || cfg.llm.apiKey == "REPLACE_ME") {
            logger.warning("llm.apiKey 还没填，请在 ${AgentConfig.rawFile().absolutePath} 里填写后重启")
        }
        if (cfg.credit.enabled) {
            logger.info(
                "积分发放：开启（单次 ${cfg.credit.minAmount}~${cfg.credit.maxAmount} 点，每账号每日 ${cfg.credit.dailyCap} 点，" +
                    "已绑定 ${AccountStore.all().size} 个账号 → ${AccountStore.all().entries.joinToString(", ") { "${it.key}=${it.value}" }}）"
            )
            if (cfg.credit.apiKey.isBlank() || cfg.credit.apiKey == "REPLACE_ME") {
                logger.warning(
                    "credit.apiKey 还没填，发放会失败；密钥要和站点服务端环境变量 EXTERNAL_CREDIT_API_KEY 一致，" +
                        "填在 ${AgentConfig.rawFile().absolutePath} 的 credit.apiKey"
                )
            }
        }
        cfg.parseError?.let { logger.warning(it) }

        GlobalEventChannel.subscribeAlways<GroupMessageEvent> { onGroupMessage(this) }
        GlobalEventChannel.subscribeAlways<FriendMessageEvent> { onFriendMessage(this) }
    }

    override fun onDisable() {
        scope.cancel()
    }

    private fun rebuild() {
        val registry = ToolRegistry(cfg) { msg -> logger.info(msg) }
        llm = LlmClient(cfg, registry) { msg -> logger.info(msg) }
        logger.info(
            "[工具] 已注册 ${registry.definitions().length()} 个（tools=${cfg.tools.enabled}，积分=${cfg.credit.enabled}）"
        )
    }

    private fun memoryOf(key: String): ChatMemory =
        memories.computeIfAbsent(key) { ChatMemory(it, cfg).also { m -> m.load() } }

    private fun lockOf(key: String): Mutex = locks.computeIfAbsent(key) { Mutex() }

    // ---------------------------------------------------------------- 群聊

    private suspend fun onGroupMessage(event: GroupMessageEvent) {
        if (event.sender.id == event.bot.id) return
        val groupId = event.group.id
        if (groupId !in cfg.bot.enabledGroups) return

        val key = ChatMemory.group(groupId)
        val memory = memoryOf(key)
        val mentioned = event.message.any { it is At && it.target == event.bot.id }
        val plain = plainTextOf(event.message).trim()

        // 硬指令：@我 chaton / chatoff / chatempty（大小写、空格都不敏感）
        if (mentioned) {
            controlCommand(plain)?.let { cmd ->
                handleControl(event.subject, key, memory, cmd, event.sender.id, isGroupAdmin(event))
                return
            }
        }

        val images = collectImages(event.message)
        var text = event.message.contentToString().trim()
        if (text.isEmpty() && images.isEmpty()) return
        if (text.isEmpty()) text = "[图片]"

        if (text.startsWith(cfg.bot.commandPrefix) && event.sender.id in cfg.bot.admins) {
            handleCommand(event.subject, key, memory, text)
            return
        }

        val quotedBot = event.message[QuoteReply]?.source?.fromId == event.bot.id
        val senderName = event.senderName.ifBlank { event.sender.id.toString() }

        // 群里说什么都记进上下文（聊天关着也记），判定才看得到"群里在聊什么"
        GroupContext.add(key, "$senderName: $text${imageMark(images)}")

        // 聊天关闭时静默忽略，不写日志
        if (!ChatState.isOn(key, cfg.bot.chatDefaultOn)) return

        memory.append("user", "$senderName(${event.sender.id}): $text${imageMark(images)}")

        if (!shouldSpeak(key, senderName, text, mentioned, quotedBot)) return

        val botName = runCatching { event.group.botAsMember.nick }.getOrDefault("助手")
        val desc = "QQ群「${event.group.name}」（群号 ${event.group.id}）"
        reply(event.subject, key, memory, desc, botName, text, images, event.sender.id, senderName)
    }

    private fun isGroupAdmin(event: GroupMessageEvent): Boolean =
        runCatching {
            event.sender.permission.level >= MemberPermission.ADMINISTRATOR.level
        }.getOrDefault(false)

    // ---------------------------------------------------------------- 私聊

    private suspend fun onFriendMessage(event: FriendMessageEvent) {
        if (event.sender.id == event.bot.id) return
        if (!cfg.bot.enabledPrivate) return

        val key = ChatMemory.friend(event.sender.id)
        val memory = memoryOf(key)
        val images = collectImages(event.message)
        var text = event.message.contentToString().trim()
        if (text.isEmpty() && images.isEmpty()) return
        if (text.isEmpty()) text = "[图片]"

        controlCommand(plainTextOf(event.message).trim())?.let { cmd ->
            handleControl(event.subject, key, memory, cmd, event.sender.id, false)
            return
        }

        if (text.startsWith(cfg.bot.commandPrefix) && event.sender.id in cfg.bot.admins) {
            handleCommand(event.subject, key, memory, text)
            return
        }

        // 聊天关闭时静默忽略，不写日志
        if (!ChatState.isOn(key, cfg.bot.chatDefaultOn)) return

        GroupContext.add(key, "${event.senderName.ifBlank { event.sender.id.toString() }}: $text${imageMark(images)}")

        memory.append(
            "user",
            "${event.senderName.ifBlank { event.sender.id.toString() }}: $text${imageMark(images)}"
        )
        logger.info("[判定] $key 发言 ← 私聊「${preview(text)}」${if (images.isNotEmpty()) "（含 ${images.size} 张图）" else ""}")

        val desc = "与 QQ 用户「${event.senderName}」（${event.sender.id}）的私聊"
        reply(event.subject, key, memory, desc, "助手", text, images, event.sender.id, event.senderName)
    }

    // ---------------------------------------------------------------- 生成回复

    private suspend fun reply(
        contact: Contact,
        key: String,
        memory: ChatMemory,
        chatDescription: String,
        botName: String,
        userText: String,
        images: List<String> = emptyList(),
        senderId: Long = 0L,
        senderName: String = "",
    ) {
        val lock = lockOf(key)
        if (lock.isLocked) {
            logger.info("[回复] $key 跳过 ← 上一条还在生成中")
            return
        }

        val startedAt = System.currentTimeMillis()
        val paren = cfg.trigger.parenRuleEnabled && usesParens(userText)
        val segments = lock.withLock {
            val raw = withContext(Dispatchers.IO) {
                runCatching {
                    val sys = LlmClient.buildSystemPrompt(
                        cfg, key, chatDescription, botName,
                        summary = memory.summaryText(),
                        turnRule = turnRuleFor(key, paren, images.isNotEmpty()),
                        senderId = senderId,
                    )
                    val bound = if (cfg.credit.enabled) AccountStore.usernameOf(senderId) else null
                    if (cfg.credit.enabled) {
                        logger.info("[积分] $key 发送者=$senderId($senderName) 绑定=${bound ?: "无"}")
                    }
                    // 贴在本轮用户消息最前面：模型对这里最敏感，比写在系统提示里更管用
                    val creditHint = when {
                        !cfg.credit.enabled || senderId <= 0L -> ""
                        bound != null ->
                            "【本地记录】本条消息的发送者 QQ $senderId（$senderName）在积分绑定表里已绑定站点账号「$bound」。" +
                                "要发积分时先调 check_credit_account 确认，再调 grant_credits；" +
                                "不要问账号名、不要说记不清、不要装不知道。"
                        else ->
                            "【本地记录】本条消息的发送者 QQ $senderId（$senderName）在积分绑定表里没有站点账号；" +
                                "要发的时候 check_credit_account 会返回 NEED_ACCOUNT，那时才问账号名。"
                    }
                    llm.chat(
                        memory.snapshot(), sys,
                        images = images,
                        ctx = ToolContext(senderId, key, senderName),
                        userHint = creditHint,
                    )
                }.getOrElse {
                    logger.warning("$key 调用模型失败：${it.message}")
                    null
                }
            }
            val parts = raw?.let { parseOutgoing(it, paren) } ?: return@withLock null
            if (parts.isEmpty()) return@withLock null
            // 多段就按配置的间隔逐条发（默认 2 秒），像人一句一句打出来
            parts.forEachIndexed { i, msg ->
                if (i > 0) delay(cfg.bot.segmentDelayMs.toLong())
                if (msg.sticker.isNotBlank()) sendSticker(contact, key, msg.sticker)
                else sendText(contact, key, msg.text)
            }
            parts
        }
        if (segments.isNullOrEmpty()) return

        val joined = segments.joinToString(" ") {
            if (it.text.isNotBlank()) it.text else "[表情:${it.sticker}]"
        }
        memory.append("assistant", joined)
        GroupContext.add(key, "透子: $joined")
        logger.info(
            "[回复] $key ${System.currentTimeMillis() - startedAt}ms " +
                (if (segments.size > 1) "${segments.size} 段 → " else "→ ") + joined
        )

        rollSummary(key, memory)
    }

    // ---------------------------------------------------------------- 记忆压缩

    /** 消息攒够条数后：备份原文 + 生成摘要，只留最近几条原文 */
    private suspend fun rollSummary(key: String, memory: ChatMemory) {
        if (!memory.needsSummary()) return
        withContext(Dispatchers.IO) {
            runCatching {
                val keep = cfg.memory.summaryKeepRecent
                val old = memory.historyForSummary(keep)
                if (old.isEmpty()) {
                    memory.markSummarized()
                    return@runCatching
                }
                val transcript = old.joinToString("\n") { "${it.role}: ${it.content}" }
                val text = llm.summarize(
                    memory.summaryText(), transcript,
                    cfg.memory.summaryModel, cfg.memory.summaryMaxChars
                )
                if (text.isBlank()) {
                    memory.markSummarized()
                    logger.warning("[记忆] $key 摘要为空，跳过本轮")
                    return@runCatching
                }
                memory.applySummary(text, keep)
                logger.info("[记忆] $key 已压缩 ${old.size} 条为 ${text.length} 字摘要，原文已备份")
            }.onFailure { logger.warning("[记忆] $key 压缩失败：${it.message}") }
        }
    }

    // ---------------------------------------------------------------- 是否发言

    private suspend fun shouldSpeak(
        key: String,
        senderName: String,
        text: String,
        mentioned: Boolean,
        quoted: Boolean,
    ): Boolean {
        val p = preview(text)

        // 有人嫌它话多、让它别回：记下原话、静默，并让随记立刻复盘
        if (!mentioned && complaintPatterns.any { text.contains(it) }) {
            FeedbackLog.add(key, "$senderName: $text")
            logger.info("[判定] $key 不发言 ← 群友嫌话多「$p」")
            maybeReflect(key, feedback = "$senderName 说：「$text」（这是在嫌它插话，或让它别回、别多说）")
            return false
        }

        // 有人希望它多说两句：同样只记原话，交给随记去判断哪些场合可以多聊
        if (morePatterns.any { text.contains(it) }) {
            FeedbackLog.add(key, "$senderName: $text")
            logger.info("[判定] $key 记下偏好 ← 群友希望多说「$p」")
            maybeReflect(key, feedback = "$senderName 说：「$text」（这是希望它多说两句、别一直闷着）")
        }

        if (mentioned) {
            logger.info("[判定] $key 发言 ← @提及「$p」")
            return true
        }
        if (quoted) {
            logger.info("[判定] $key 发言 ← 引用机器人「$p」")
            return true
        }
        if (cfg.trigger.requireAtInGroup) {
            logger.info("[判定] $key 不发言 ← 配置要求必须@「$p」")
            return false
        }

        val keywordHit = cfg.trigger.keywords.firstOrNull { text.contains(it, ignoreCase = true) }
        if (keywordHit != null) {
            logger.info("[判定] $key 发言 ← 关键词「$keywordHit」「$p」")
            return true
        }

        if (!cfg.trigger.smartJudgeEnabled) {
            logger.info("[判定] $key 不发言 ← 未开启模型判定「$p」")
            return false
        }

        val say = runCatching {
            withContext(Dispatchers.IO) {
                llm.judge(
                    judgeContext(key, senderName, text),
                    cfg.trigger.smartJudgePrompt,
                    cfg.trigger.smartJudgeModel,
                )
            }
        }.getOrElse {
            logger.warning("[判定] $key 模型判定失败，按不发言处理：${it.message}")
            return false
        }

        logger.info("[判定] $key ${if (say) "发言" else "不发言"} ← 模型判定「$p」")
        judgeCounter.merge(key, 1, Int::plus)
        maybeReflect(key)
        return say
    }

    /** 判定模型看到的上下文：最近群聊 + 群友偏好 + 自己的随记 + 触发消息 */
    private fun judgeContext(key: String, senderName: String, text: String): String = buildString {
        appendLine("【最近群聊】")
        val recent = GroupContext.recent(key, cfg.trigger.judgeHistorySize)
        if (recent.isEmpty()) appendLine("（暂无）") else recent.forEach { appendLine(it) }
        appendLine()
        appendLine("【群友对你说多说少的反馈】")
        val feedback = FeedbackLog.recent(key, 3)
        if (feedback.isEmpty()) appendLine("（暂无）") else feedback.forEach { appendLine(it) }
        appendLine()
        appendLine("【你的随记】")
        appendLine(JudgeNotes.get(key).ifBlank { "（还没有，先按规则判断）" })
        appendLine()
        appendLine("【触发消息】")
        append("$senderName: $text")
    }

    /** 复盘随记：用户抱怨时立刻更新；平时每 N 次判定更新一次，随记为空时先生成一份 */
    private fun maybeReflect(key: String, feedback: String? = null) {
        if (!cfg.trigger.judgeNotesEnabled) return
        val every = cfg.trigger.judgeNotesEvery
        val blank = JudgeNotes.get(key).isBlank()
        if (feedback == null) {
            if (!blank && every <= 0) return
            if (!blank && (judgeCounter[key] ?: 0) % every != 0) return
        }

        val transcript = GroupContext.recent(key, cfg.trigger.judgeHistorySize).joinToString("\n")
        if (transcript.isBlank()) return

        scope.launch {
            runCatching {
                val note = llm.reflect(
                    JudgeNotes.get(key), transcript, cfg.trigger.judgeNotesMaxChars, feedback ?: ""
                )
                if (note.isBlank()) return@runCatching
                JudgeNotes.set(key, note)
                logger.info(
                    "[随记] $key 已更新（${note.length} 字）" +
                        (if (feedback != null) " ← 用户反馈" else "") + "：${preview(note)}"
                )
            }.onFailure { logger.warning("[随记] $key 复盘失败：${it.message}") }
        }
    }

    private fun preview(text: String): String =
        text.replace(Regex("\\s+"), " ").let { if (it.length > 30) it.take(30) + "…" else it }

    // ---------------------------------------------------------------- 图片

    /** 把消息里的图片转成 data URL（最多 maxImages 张），失败的直接跳过 */
    private suspend fun collectImages(message: MessageChain): List<String> {
        if (!cfg.llm.vision.enabled) return emptyList()
        val imgs = message.filterIsInstance<Image>().take(cfg.llm.vision.maxImages)
        if (imgs.isEmpty()) return emptyList()
        return withContext(Dispatchers.IO) {
            imgs.mapNotNull { img ->
                runCatching {
                    Vision.toDataUrl(img, cfg.llm.vision.maxBytes, cfg.llm.vision.maxEdge)
                }.getOrNull().also { url ->
                    if (url == null) logger.warning("[图片] 一张图处理失败，已忽略")
                }
            }
        }
    }

    private fun imageMark(images: List<String>): String =
        if (images.isEmpty()) "" else "（附 ${images.size} 张图片）"

    // ---------------------------------------------------------------- 硬指令

    private fun plainTextOf(message: MessageChain): String =
        message.filterIsInstance<PlainText>().joinToString("") { it.content }

    /** 去掉所有空白后匹配 chaton / chatoff / chatempty，忽略大小写 */
    private fun controlCommand(plain: String): String? =
        plain.replace(Regex("\\s+"), "").lowercase().takeIf { it in controlCommands }

    private suspend fun handleControl(
        contact: Contact,
        key: String,
        memory: ChatMemory,
        cmd: String,
        senderId: Long,
        senderIsGroupAdmin: Boolean,
    ) {
        val allowed = !cfg.bot.chatControlAdminsOnly || senderIsGroupAdmin || senderId in cfg.bot.admins
        if (!allowed) {
            runCatching { contact.sendMessage(PlainText("这个指令只有管理员能用")) }
            return
        }
        val text = when (cmd) {
            "chaton" -> {
                ChatState.set(key, true)
                "聊天已开启"
            }
            "chatoff" -> {
                ChatState.set(key, false)
                "聊天已关闭，@我 说 chaton 可重新开启"
            }
            else -> {
                memory.clear()
                "记忆已清空，从头开始聊吧"
            }
        }
        logger.info("[指令] $key $cmd by $senderId")
        runCatching { contact.sendMessage(PlainText(text)) }
    }

    // ---------------------------------------------------------------- /ai 指令

    private suspend fun handleCommand(
        contact: Contact,
        key: String,
        memory: ChatMemory,
        text: String,
    ) {
        val arg = text.removePrefix(cfg.bot.commandPrefix).trim().lowercase()
        val reply = when {
            arg.isEmpty() || arg == "help" -> """
                |${cfg.bot.commandPrefix} status 查看当前会话状态
                |${cfg.bot.commandPrefix} reset 清空当前会话记忆
                |${cfg.bot.commandPrefix} reload 重新读取配置
                |${cfg.bot.commandPrefix} portrait 看透子的立绘
                |@我 chaton / chatoff 开关聊天，chatempty 清空记忆
            """.trimMargin()

            arg == "portrait" -> {
                sendPortrait(contact, key)
                return
            }

            arg == "status" -> buildString {
                append("会话：$key\n")
                append("聊天：${if (ChatState.isOn(key, cfg.bot.chatDefaultOn)) "开启" else "关闭"}\n")
                append("模型：${cfg.llm.model}\n")
                append("记忆：${memory.size()} 条，待压缩 ${memory.pendingForSummary()} 条\n")
                append("摘要：${memory.summaryText().ifBlank { "无" }.take(60)}\n")
                append("群白名单：${cfg.bot.enabledGroups.joinToString(",")}\n")
                append("工具：${if (cfg.tools.enabled) "开启" else "关闭"}（HTTP 白名单 ${cfg.tools.httpAllowedHosts.size} 个）")
            }

            arg == "reset" -> {
                memory.clear()
                "已清空本会话记忆"
            }

            arg == "reload" -> runCatching {
                cfg = AgentConfig.load()
                rebuild()
                memories.clear()
                "配置已重载：模型 ${cfg.llm.model}，群白名单 ${cfg.bot.enabledGroups}"
            }.getOrElse { "重载失败：${it.message}" }

            else -> "未知指令，用 ${cfg.bot.commandPrefix} help 查看"
        }
        runCatching { contact.sendMessage(PlainText(reply)) }
    }

    // ---------------------------------------------------------------- 文本处理

    /** 消息里出现中英文圆括号，视为动作/旁白场景 */
    private fun usesParens(text: String): Boolean =
        text.any { it == '(' || it == ')' || it == '（' || it == '）' }

    private fun sanitize(raw: String, paren: Boolean): String {
        var s = raw.trim().removePrefix("assistant:").trim()
        s = limitSentences(s, cfg.bot.maxSentences)
        if (s.length > cfg.bot.maxReplyChars) s = s.take(cfg.bot.maxReplyChars) + "…"
        if (paren) s = wrapInParens(s)
        return s.trim()
    }

    /** 本轮要追加给模型的临时规则：附图、括号、以及群友对"说多说少"的反馈 */
    private fun turnRuleFor(key: String, paren: Boolean, hasImages: Boolean): String = buildString {
        if (hasImages) append(VISION_RULE).append('\n')
        if (paren) append(PAREN_RULE).append('\n')
        val feedback = FeedbackLog.recent(key, 3)
        if (feedback.isNotEmpty()) {
            append("【最近群友对你说话方式的反馈】\n")
            feedback.forEach { append("- ").append(it).append('\n') }
            append(
                "按这些反馈调整：有人嫌你话多、让你别多说，就只回最必要的一句，也不要拆成多段；" +
                    "有人希望你多说两句，就可以稍微展开。\n"
            )
        }
    }

    /** 一条待发送的消息：要么是文字，要么是一张表情图 */
    private data class Outgoing(val text: String = "", val sticker: String = "")

    /** 模型输出 → 待发送消息：||| 分段，[表情:名称] 变成图片消息 */
    private fun parseOutgoing(raw: String, paren: Boolean): List<Outgoing> {
        val body = raw.trim().removePrefix("assistant:").trim()
        val chunks = if (cfg.bot.multiSegmentEnabled) body.split(SEGMENT_SEPARATOR) else listOf(body)
        val out = mutableListOf<Outgoing>()
        chunks.forEach { chunk ->
            val body2 = chunk.trim()
            if (body2.isBlank()) return@forEach
            // 先摘出表情标记，再清洗文字：避免断句/字数上限把 [表情:x] 一起截掉
            var start = 0
            stickerPattern.findAll(body2).forEach { m ->
                val before = body2.substring(start, m.range.first).trim()
                if (before.isNotBlank()) {
                    val t = sanitize(before, paren)
                    if (t.isNotBlank()) out += Outgoing(text = t)
                }
                val name = m.groupValues[1].trim()
                if (name.isNotEmpty()) out += Outgoing(sticker = name)
                start = m.range.last + 1
            }
            val rest = body2.substring(start).trim()
            if (rest.isNotBlank()) {
                val t = sanitize(rest, paren)
                if (t.isNotBlank()) out += Outgoing(text = t)
            }
        }
        val max = if (cfg.bot.multiSegmentEnabled) cfg.bot.maxSegments.coerceAtLeast(1) else 1
        if (out.size <= max) return out
        // 超出发送条数：多余的表情丢掉，文字并进最后一条
        val head = out.take(max - 1)
        val tailText = out.drop(max - 1).joinToString(" ") { it.text }.trim()
        return if (tailText.isEmpty()) head else head + Outgoing(text = tailText)
    }

    private suspend fun sendText(contact: Contact, key: String, text: String) {
        if (text.isBlank()) return
        runCatching { contact.sendMessage(PlainText(text)) }
            .onFailure { logger.warning("$key 发送失败：${it.message}") }
    }

    /** 按名字找本地表情文件，上传成图片消息发出去 */
    private suspend fun sendSticker(contact: Contact, key: String, name: String) {
        val path = cfg.assets.stickers[name]
        if (path.isNullOrBlank()) {
            logger.info("[表情] $key 没有配置「$name」，跳过")
            return
        }
        val file = File(AgentConfig.pluginDir, path)
        if (!file.isFile) {
            logger.warning("[表情] $key 「$name」文件不存在：${file.absolutePath}")
            return
        }
        runCatching {
            sendLocalImage(contact, file)
            logger.info("[表情] $key 发送「$name」 ← ${file.name}")
        }.onFailure { logger.warning("[表情] $key 发送「$name」失败：${it.message}") }
    }

    /**
     * 本地图片文件 → 图片消息。
     * 编译用的 Kotlin 版本读不到 `ExternalResource.create`，这里用反射创建资源，再走 Contact.uploadImage。
     */
    private fun loadExternalResource(file: File): ExternalResource {
        // ExternalResource 是接口，create 被编译成接口上的 static 方法（@JvmStatic + @JvmOverloads），
        // 反射取静态方法最稳；单参数版本缺失时退回 create(File, String)。
        val cls = Class.forName("net.mamoe.mirai.utils.ExternalResource")
        val one = runCatching { cls.getMethod("create", File::class.java).invoke(null, file) }.getOrNull()
        if (one != null) return one as ExternalResource
        return cls.getMethod("create", File::class.java, String::class.java)
            .invoke(null, file, if (file.extension.isBlank()) "png" else file.extension) as ExternalResource
    }

    private suspend fun sendLocalImage(contact: Contact, file: File) {
        val resource = loadExternalResource(file)
        try {
            val image = contact.uploadImage(resource)
            contact.sendMessage(image)
        } finally {
            runCatching { resource.close() }
        }
    }

    /** 发立绘（/ai portrait） */
    private suspend fun sendPortrait(contact: Contact, key: String) {
        val path = cfg.assets.portrait
        if (path.isBlank()) {
            runCatching { contact.sendMessage(PlainText("还没配置立绘")) }
            return
        }
        val file = File(AgentConfig.pluginDir, path)
        if (!file.isFile) {
            runCatching { contact.sendMessage(PlainText("立绘文件不存在：$path")) }
            return
        }
        runCatching {
            sendLocalImage(contact, file)
            logger.info("[立绘] $key 已发送 ${file.name}")
        }.onFailure { logger.warning("[立绘] $key 发送失败：${it.message}") }
    }

    /** 只保留前 max 句（0 = 不限）；连续标点如"……"算一句 */
    private fun limitSentences(s: String, max: Int): String {
        if (max <= 0) return s
        val enders = "。！？!?…；;\n"
        val sb = StringBuilder()
        var count = 0
        var prevEnder = false
        for (ch in s) {
            sb.append(ch)
            val isEnder = ch in enders
            if (isEnder && !prevEnder) {
                count++
                if (count >= max) break
            }
            prevEnder = isEnder
        }
        return sb.toString().trim()
    }

    /** 整条包进圆括号；模型自己包了就不重复包 */
    private fun wrapInParens(s: String): String {
        val t = s.trim()
        if (t.isEmpty()) return t
        val alreadyWrapped =
            (t.startsWith("（") && t.endsWith("）")) || (t.startsWith("(") && t.endsWith(")"))
        if (alreadyWrapped) return t
        return "（" + t.trim('（', '）', '(', ')', '。', '.', '\n', ' ') + "）"
    }
}

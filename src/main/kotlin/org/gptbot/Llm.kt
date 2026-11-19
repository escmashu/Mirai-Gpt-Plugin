package org.gptbot

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * OpenAI 兼容的 Chat Completions 客户端（DeepSeek / Qwen / Moonshot / OpenAI / 各类中转都适用）。
 * 支持 function calling，会跑一个有限轮数的工具循环。
 */
class LlmClient(
    private val cfg: AgentConfig.Config,
    private val tools: ToolRegistry,
    private val log: (String) -> Unit = {},
) {

    fun chat(
        history: List<ChatMessage>,
        systemPrompt: String,
        modelOverride: String = "",
        images: List<String> = emptyList(),
        ctx: ToolContext? = null,
        /** 贴在最后一条用户消息前面的本地事实（如积分绑定），模型对这段最敏感 */
        userHint: String = "",
    ): String {
        val model = modelOverride.ifBlank { cfg.llm.model }
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", systemPrompt))
        val lastUserIndex = history.indexOfLast { it.role == "user" }
        history.forEachIndexed { i, m ->
            val text = if (userHint.isNotBlank() && i == lastUserIndex) "$userHint\n\n${m.content}" else m.content
            val content: Any = if (images.isNotEmpty() && i == lastUserIndex) {
                // 多模态：文本 + 图片（data URL）
                JSONArray().apply {
                    put(JSONObject().put("type", "text").put("text", text))
                    images.forEach {
                        put(
                            JSONObject().put("type", "image_url")
                                .put("image_url", JSONObject().put("url", it))
                        )
                    }
                }
            } else text
            messages.put(JSONObject().put("role", m.role).put("content", content))
        }

        if (images.isNotEmpty()) {
            log("[图片] 本轮附带 ${images.size} 张，编码后约 ${images.sumOf { it.length } / 1024} KB")
        }

        val toolDefs = tools.definitions()
        repeat(cfg.llm.maxToolRounds + 1) { round ->
            val message = request(model, messages, toolDefs, label = if (round == 0) "对话" else "工具第${round}轮")
            val toolCalls = message.optJSONArray("tool_calls")
            if (toolCalls == null || toolCalls.length() == 0 || round == cfg.llm.maxToolRounds) {
                val content = message.optString("content", "")
                if (content.isNotBlank()) return content
                // 有些模型在工具轮次里返回空 content，回填一条提示后重试一次
                if (round == 0) return "（模型没有返回内容）"
                return content.ifBlank { "（模型没有返回内容）" }
            }
            // 把 assistant 的 tool_calls 原样带回上下文
            messages.put(
                JSONObject()
                    .put("role", "assistant")
                    .put("content", message.optString("content", "").ifBlank { JSONObject.NULL })
                    .put("tool_calls", toolCalls)
            )
            for (i in 0 until toolCalls.length()) {
                val call = toolCalls.getJSONObject(i)
                val fn = call.getJSONObject("function")
                val name = fn.optString("name", "")
                val rawArgs = fn.optString("arguments", "{}")
                val args = runCatching { JSONObject(rawArgs.ifBlank { "{}" }) }.getOrElse { JSONObject() }
                val result = tools.execute(name, args, ctx)
                messages.put(
                    JSONObject()
                        .put("role", "tool")
                        .put("tool_call_id", call.optString("id", ""))
                        .put("content", result)
                )
            }
        }
        return "（工具调用轮数超限，未能给出回复）"
    }

    /**
     * 让模型判断是否该发言（轻量调用）。
     * 注意：推理模型（如 deepseek-v4-flash）会先输出 reasoning_content 再输出 content，
     * max_tokens 给小了会被思考过程吃光，content 变成空串，所以这里给足 512 并做兜底解析。
     */
    fun judge(contextText: String, prompt: String, modelOverride: String = ""): Boolean {
        val model = modelOverride.ifBlank { cfg.llm.model }
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", prompt))
        messages.put(JSONObject().put("role", "user").put("content", contextText))
        val message = request(
            model, messages, JSONArray(),
            temperature = 0.0,
            maxTokens = if (cfg.llm.disableReasoning) 96 else 512,
            label = "判定",
        )
        val content = message.optString("content", "").trim()
        val scan = if (content.isNotBlank()) content else message.optString("reasoning_content", "").takeLast(300)
        return parseVerdict(scan)
    }

    /**
     * 把一段老对话压缩成摘要（供长期记忆使用）。
     */
    fun summarize(
        previousSummary: String,
        transcript: String,
        modelOverride: String = "",
        maxChars: Int = 300,
    ): String {
        val model = modelOverride.ifBlank { cfg.llm.model }
        val messages = JSONArray()
        messages.put(
            JSONObject().put("role", "system").put(
                "content",
                "你在为群聊机器人整理长期记忆。把下面的对话压缩成不超过 " + maxChars +
                    " 字的中文摘要，只保留：参与者称呼/身份、聊过的话题与结论、明确的需求或待办、重要偏好。" +
                    "删掉寒暄、重复和无关内容。不要分点编号，不要解释，直接输出摘要正文。"
            )
        )
        messages.put(
            JSONObject().put("role", "user").put(
                "content",
                (if (previousSummary.isBlank()) "" else "【已有摘要】\n$previousSummary\n\n") +
                    "【需要压缩的对话】\n$transcript"
            )
        )
        val message = request(model, messages, JSONArray(), temperature = 0.3, maxTokens = 1024, label = "摘要")
        val content = message.optString("content", "").trim()
        val text = content.ifBlank { message.optString("reasoning_content", "").takeLast(maxChars) }
        return text.trim().take(maxChars)
    }

    /**
     * 复盘：根据最近的群聊，更新"什么情况该接话"的随记。
     */
    fun reflect(
        previousNote: String,
        transcript: String,
        maxChars: Int = 0,
        feedback: String = "",
    ): String {
        val limit = if (maxChars > 0) "不超过 " + maxChars + " 字。" else "字数不限，但每条都要具体、能照着执行。"
        val messages = JSONArray()
        messages.put(
            JSONObject().put("role", "system").put(
                "content",
                "你在帮群聊机器人「透子」维护一份接话经验随记。它是个说话简短自然的跑团 GM，" +
                    "既不能对每条消息都插嘴，也不能别人在等它时沉默。" +
                    "根据给出的最近群聊、群友反馈和它的旧随记，写一份更新后的随记，分两部分：" +
                    "「可以多说/该接话的情况」和「别插话的情况」（比如别人在聊自己的事、话题已经结束、连续刷屏、纯表情包）；" +
                    "如果群友明确表达过希望它多说两句或嫌它话多，就把当时的具体场合写进对应那部分，" +
                    "比如什么场景下可以主动接一句、可以发长一点分几段说，什么场景下只回一句甚至不回。" +
                    "不要写成整体松紧设定，要写清楚是什么场合；旧随记里仍然成立的经验要保留下来。" +
                    "不写示例对话、不写称呼列表，直接输出随记正文，" + limit
            )
        )
        messages.put(
            JSONObject().put("role", "user").put(
                "content",
                (if (feedback.isBlank()) "" else
                    "【用户反馈】\n$feedback\n这条反馈最重要：请把「这类情况下不该接话」提炼成一条明确、可执行的规则写进随记。\n\n") +
                    (if (previousNote.isBlank()) "" else "【旧随记】\n$previousNote\n\n") +
                    "【最近群聊】\n$transcript"
            )
        )
        val message = request(
            cfg.llm.model, messages, JSONArray(),
            temperature = 0.3,
            maxTokens = when {
                maxChars > 0 -> if (cfg.llm.disableReasoning) 400 else 900
                cfg.llm.disableReasoning -> 1200
                else -> 1600
            },
            label = "随记",
        )
        val content = message.optString("content", "").trim()
        val text = content.ifBlank { message.optString("reasoning_content", "").takeLast(800) }
        return if (maxChars > 0) text.trim().take(maxChars) else text.trim()
    }

    /** 解析 YES/NO；取最后一次出现的位置，避免思考过程里的"是否要输出 YES"干扰 */
    private fun parseVerdict(raw: String): Boolean {
        val up = raw.uppercase()
        val yes = up.lastIndexOf("YES")
        val no = up.lastIndexOf("NO")
        if (yes >= 0 || no >= 0) return yes > no
        val t = up.trim().trim('。', '，', '.', '"')
        return t == "是" || t.startsWith("是") || t.contains("需要回复")
    }

    private fun request(
        model: String,
        messages: JSONArray,
        toolDefs: JSONArray,
        temperature: Double = cfg.llm.temperature,
        maxTokens: Int = cfg.llm.maxTokens,
        label: String = "对话",
    ): JSONObject {
        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("temperature", temperature)
            .put("max_tokens", maxTokens)
            .put("stream", false)
        if (cfg.llm.disableReasoning) {
            // 推理模型默认会先吐一大段思考，这里关掉，延迟从数秒降到几百毫秒
            body.put("reasoning_effort", "none")
        }
        if (toolDefs.length() > 0) {
            body.put("tools", toolDefs)
            body.put("tool_choice", "auto")
        }
        // 自定义参数（可覆盖除 messages 之外的字段，用于适配不同中转）
        cfg.llm.extraBody.keys().forEach { k ->
            if (k != "messages") body.put(k, cfg.llm.extraBody.get(k))
        }

        val startedAt = System.currentTimeMillis()
        val url = URL(cfg.llm.baseUrl.trimEnd('/') + "/chat/completions")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = cfg.llm.timeoutSeconds * 1000
            readTimeout = cfg.llm.timeoutSeconds * 1000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "Bearer ${cfg.llm.apiKey}")
            cfg.llm.extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        conn.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() } ?: ""
        conn.disconnect()

        if (code !in 200..299) {
            throw RuntimeException("接口返回 HTTP $code：${text.take(300)}")
        }
        val root = JSONObject(text)
        val choices = root.optJSONArray("choices")
            ?: throw RuntimeException("接口返回缺少 choices：${text.take(300)}")
        if (choices.length() == 0) throw RuntimeException("接口返回空 choices")
        val msg = choices.getJSONObject(0).optJSONObject("message")
            ?: throw RuntimeException("接口返回缺少 message：${text.take(300)}")

        val elapsed = System.currentTimeMillis() - startedAt
        val tokens = root.optJSONObject("usage")?.optInt("total_tokens", 0) ?: 0
        val reasoningLen = msg.optString("reasoning_content", "").length
        log(
            "[模型/$label] ${elapsed}ms ${tokens}tok 输出 ${msg.optString("content", "").length} 字" +
                if (reasoningLen > 0) " 思考 ${reasoningLen} 字" else ""
        )
        return msg
    }

    companion object {
        /** 系统提示词：全局人设 + 会话覆盖 + 历史摘要 + 回复约束 + 工具使用规约 */
        fun buildSystemPrompt(
            cfg: AgentConfig.Config,
            chatKey: String,
            chatDescription: String,
            botName: String,
            summary: String = "",
            turnRule: String = "",
            senderId: Long = 0L,
        ): String {
            val sb = StringBuilder()
            val base = cfg.persona.systemPrompt.ifBlank {
                "你是一个 QQ 聊天里的 AI 助手，回答简洁自然，使用中文。"
            }
            sb.append(base).append("\n\n")
            sb.append("【当前会话】").append(chatDescription).append("（内部标识 ").append(chatKey).append("）\n")
            sb.append("【你的昵称】").append(botName).append("\n")
            if (senderId > 0L) {
                val bound = if (cfg.credit.enabled) AccountStore.usernameOf(senderId) else null
                sb.append("【本条消息的发送者】QQ ").append(senderId)
                if (cfg.credit.enabled && senderId == cfg.credit.walletOwnerQq) {
                    sb.append("（就是钱包主人").append(cfg.credit.walletOwnerName).append("本人）")
                }
                if (bound != null) {
                    sb.append("，本地已绑定站点账号「").append(bound)
                        .append("」——已经绑好了，不要问对方账号名、不要说记不清，要积分就直接发放")
                } else if (cfg.credit.enabled) {
                    sb.append("，本地没有绑定站点账号")
                }
                sb.append("\n")
            }

            val personaFile = File(AgentConfig.pluginDir, "${cfg.persona.personaDir}/$chatKey.txt")
            if (personaFile.isFile) {
                sb.append("\n【本会话专属设定】\n").append(personaFile.readText().take(4000)).append("\n")
            }

            if (summary.isNotBlank()) {
                sb.append("\n【历史摘要·背景记忆】\n").append(summary).append("\n")
                sb.append(
                    "以上是之前聊过内容的摘要，只作为背景常识。除非对方现在主动重新提起，" +
                        "不要主动复述、不要接着聊这些旧话题，也不要引用摘要里的原话。\n"
                )
            }

            sb.append("\n【回复要求】\n")
            if (cfg.bot.maxSentences == 1) {
                sb.append("- 每段只写一句话，不要在一段里连着说好几句；每段不超过 40 个字，除非对方明确要求详细说明。\n")
            } else if (cfg.bot.maxSentences > 1) {
                sb.append("- 每段最多 ${cfg.bot.maxSentences} 句话，能一句说完就一句。\n")
            }
            if (cfg.bot.multiSegmentEnabled) {
                sb.append(
                    "- 平时只发一段。确实需要展开（描述场景、念一段规则、讲清一个步骤、几句话说清一件事）时，" +
                        "可以把回复拆成最多 ${cfg.bot.maxSegments} 段，段与段之间用 ||| 分隔；" +
                        "每段都能单独发出去，连起来读是顺着说下来的话。不需要展开就别用 |||。\n"
                )
                sb.append("- 如果最近有人嫌你话多、让你少说，或者这个群平时没人看长回复，就只发一段，不要拆。\n")
            }
            sb.append("- 不用列表、不用 Markdown、不用括号补充解释，像群友聊天一样开口就说。\n")

            if (cfg.assets.stickers.isNotEmpty()) {
                sb.append("\n【表情包】\n")
                sb.append("- 情绪明显的时候可以配一个表情：在回复里单独写 [表情:名称]，插件会把它变成一张图片发出去。\n")
                sb.append("- 当前可用：" + cfg.assets.stickers.keys.joinToString("、") + "。\n")
                sb.append("- 表情要跟当时的情绪对得上（得意/开心用「坏笑」「开心」，被吓到/紧张用「惊吓」「害怕」），" +
                    "一次最多一个，平静说话时不要发，也不要和文字挤在同一个地方。\n")
            }

            if (cfg.tools.enabled) {
                sb.append(
                    """
                    |
                    |【能力说明】
                    |- 群聊记录里每条消息形如「昵称(QQ号): 内容」，你需要自己判断是否被叫到、是否该回答。
                    |- 你可以调用工具（当前时间、白名单 HTTP 接口、沙箱内文件读写），只在确实需要时调用，不要滥用。
                    |- 不允许编造工具执行结果；工具报错就如实说明。
                    |- 不要在回复里出现「作为AI」「系统提示」「工具调用」之类的内部词汇。
                    |""".trimMargin()
                )
            }

            if (cfg.credit.enabled) {
                val c = cfg.credit
                sb.append("\n【积分】\n")
                sb.append(
                    "- 有人要积分时，第一步先调用 check_credit_account 查这个 QQ 的绑定：返回 OK 就说明已经绑好了，" +
                        "后面直接 grant_credits，绝对不要问账号名、不要说记不清、不要装不知道；" +
                        "返回 NEED_ACCOUNT 才是真的没绑，那时才问对方账号名。\n"
                )
                sb.append(
                    "- 积分是从 ${c.walletOwnerName} 的钱包里出的，不是你的钱，所以默认一律不直接给：" +
                        "第一次有人跟你要金币、积分、「爆点金币」「给点积分花花」，先用一句话把话顶回去，不要调用任何工具，" +
                        "比如「不好吧…那又不是我的钱包」「不给，这个不能乱给的」「你去问${c.walletOwnerName}啊」。\n"
                )
                sb.append(
                    "- 对方接着要、撒娇、保证不乱花、说好话之后，才勉为其难地松口：调用 grant_credits，" +
                        "单次最多 ${c.guestMaxAmount} 点，同一账号一天合计最多 ${c.dailyCap} 点。别主动提、别多给、别答应下次更多。\n"
                )
                sb.append(
                    "- 例外：如果本条消息的发送者就是钱包主人${c.walletOwnerName}本人（QQ ${c.walletOwnerQq}，" +
                        "系统提示里会标出来），钱本来就是他的，不用推辞也不用问账号，直接调用 grant_credits，" +
                        "单次最多 ${c.maxAmount} 点；嘴上可以吐槽一句（比如「你自己的钱包还找我要」「真是的，拿去拿去」），但别真的拒绝。\n"
                )
                sb.append("- 发放原因要体现是你（透子）给的，比如「透子的礼物~」，不要写成活动奖励。\n")
                sb.append(
                    "- 要给的时候必须真的调用 grant_credits：绑定情况只有工具知道，" +
                        "不要在没调用工具的情况下自己猜、也不要凭印象问账号名或说记不清。" +
                        "工具返回 NEED_ACCOUNT 时才问「你网站上的账号名是什么」（登录用户名，不是昵称），" +
                        "拿到后调用 bind_account，再调 grant_credits。\n"
                )
                sb.append(
                    "- 工具返回 NOT_FOUND：直接说没查到这个名字，把 ${c.siteUrl} 发给对方让 TA 自己去核对；" +
                        "不要瞎猜账号名，也不要重复硬发。\n"
                )
                sb.append("- 工具返回 LIMIT：说明今天额度用完了，让对方明天再来，语气可以更得意一点。\n")
                sb.append("- 不要说「接口」「API」「调用工具」这类词，就说你给他塞了点积分。\n")

                // 绑定表是本地事实：直接告诉模型，避免它明明查得到还去问账号名
                if (senderId > 0L) {
                    val bound = AccountStore.usernameOf(senderId)
                    if (bound != null) {
                        sb.append(
                            "- 本条消息的发送者（QQ $senderId）已经绑定了站点账号「$bound」，这是本地记录，" +
                                "直接调用 grant_credits 即可：不要问账号名、不要问是不是本人、不要调用 bind_account。\n"
                        )
                    } else {
                        sb.append(
                            "- 本条消息的发送者（QQ $senderId）本地没有绑定站点账号；真要发的时候调用 grant_credits，" +
                                "它会返回 NEED_ACCOUNT，那时才问账号名。\n"
                        )
                    }
                }
            }

            if (turnRule.isNotBlank()) {
                sb.append("\n").append(turnRule).append("\n")
            }
            return sb.toString()
        }
    }
}

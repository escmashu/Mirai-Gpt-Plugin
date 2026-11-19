package org.gptbot

import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.random.Random

/**
 * QQ 号 ↔ 站点账号名 的本地绑定表，落在 config/org.gptbot.agent/accounts.json。
 * 文件可以直接手改，值是登录用的用户名（不是昵称）。
 */
object AccountStore {

    private const val COMMENT = "QQ号 → 站点账号用户名（不是昵称）。可直接手改，改完重启插件或 /ai reload 生效。"

    private val file: File get() = File(AgentConfig.pluginDir, "accounts.json")

    private val defaultJson: String
        get() = JSONObject().put("_comment", COMMENT).toString(2)

    @Synchronized
    fun all(): Map<Long, String> {
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            file.writeText(defaultJson)
            return emptyMap()
        }
        return runCatching {
            val obj = JSONObject(file.readText().removePrefix("\uFEFF"))
            obj.keys().asSequence()
                .filter { !it.startsWith("_") }
                .mapNotNull { k ->
                    val qq = k.trim().toLongOrNull() ?: return@mapNotNull null
                    val name = obj.optString(k, "").trim()
                    if (name.isEmpty()) null else qq to name
                }
                .toMap()
        }.getOrDefault(emptyMap())
    }

    fun usernameOf(qq: Long): String? = all()[qq]

    fun qqOf(username: String): Long? =
        all().entries.firstOrNull { it.value.equals(username, ignoreCase = true) }?.key

    @Synchronized
    fun bind(qq: Long, username: String) {
        val obj = if (file.exists()) {
            runCatching { JSONObject(file.readText().removePrefix("\uFEFF")) }
                .getOrDefault(JSONObject().put("_comment", COMMENT))
        } else JSONObject().put("_comment", COMMENT)
        obj.put(qq.toString(), username)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(obj.toString(2))
        if (file.exists()) file.delete()
        tmp.renameTo(file)
    }

    @Synchronized
    fun unbind(qq: Long) {
        if (!file.exists()) return
        val obj = runCatching { JSONObject(file.readText().removePrefix("\uFEFF")) }.getOrNull() ?: return
        obj.remove(qq.toString())
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(obj.toString(2))
        if (file.exists()) file.delete()
        tmp.renameTo(file)
    }
}

/**
 * 发放流水：data/org.gptbot.agent/credit_ledger.json
 * 结构：{ "days": { "2026-11-19": { "mashu": { "total": 3, "ids": ["..."] } } } }
 * 用来算"每个账号每天还能拿多少"，同时给每笔请求留个可查的编号。
 */
object CreditLedger {

    private val file: File get() = File(AgentConfig.dataDir, "credit_ledger.json")

    private val lock = Any()

    private fun read(): JSONObject {
        if (!file.exists()) return JSONObject().put("days", JSONObject())
        return runCatching { JSONObject(file.readText().removePrefix("\uFEFF")) }
            .getOrDefault(JSONObject().put("days", JSONObject()))
    }

    private fun write(obj: JSONObject) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(obj.toString(2))
        if (file.exists()) file.delete()
        tmp.renameTo(file)
    }

    fun todayTotal(username: String): Int = synchronized(lock) {
        val days = read().optJSONObject("days") ?: return 0
        val today = days.optJSONObject(LocalDate.now().toString()) ?: return 0
        today.optJSONObject(username)?.optInt("total", 0) ?: 0
    }

    fun record(username: String, amount: Int, requestId: String) = synchronized(lock) {
        val root = read()
        val days = root.optJSONObject("days") ?: JSONObject().also { root.put("days", it) }
        // 只留最近 30 天，避免文件无限增长
        val keepFrom = LocalDate.now().minusDays(30).toString()
        days.keys().asSequence().toList()
            .filter { it < keepFrom }
            .forEach { days.remove(it) }
        val todayKey = LocalDate.now().toString()
        val today = days.optJSONObject(todayKey) ?: JSONObject().also { days.put(todayKey, it) }
        val entry = today.optJSONObject(username) ?: JSONObject()
            .put("total", 0)
            .put("ids", org.json.JSONArray())
            .also { today.put(username, it) }
        entry.put("total", entry.optInt("total", 0) + amount)
        entry.optJSONArray("ids")?.put(requestId) ?: run {
            entry.put("ids", org.json.JSONArray().put(requestId))
        }
        write(root)
    }

    fun newRequestId(qq: Long): String {
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
        val rnd = Random.nextInt(100000, 999999)
        return "qq$qq-$stamp-$rnd"
    }
}

/**
 * 站点积分发放：账号绑定 + 每日额度 + 调外部接口。
 * 工具层只拿得到一句文本结果，所以所有状态都用「给人看的一句话」表达，
 * 需要模型接着做什么时用 NEED_ACCOUNT / NOT_FOUND / LIMIT 这类前缀。
 */
class CreditService(private val cfg: AgentConfig.Config) {

    private val c = cfg.credit

    private val usernameRegex = Regex("^[A-Za-z0-9_.\\-]{2,32}$")

    /** 绑定：先问清楚账号名，再（可选的）查一次存在性，最后落盘 */
    fun bind(ctx: ToolContext, rawUsername: String): String {
        if (!c.enabled) return "ERROR: 积分功能没开启（config.json 里 credit.enabled）"
        val username = rawUsername.trim()
        if (!usernameRegex.matches(username)) {
            return "ERROR: 账号名格式不对（2~32 位字母、数字、下划线、点或连字符），" +
                "请让对方去 ${c.siteUrl} 确认登录用的账号名"
        }
        val current = AccountStore.usernameOf(ctx.qq)
        if (current != null && current.equals(username, ignoreCase = true)) {
            return "OK: 已经绑过了，${ctx.qq} 对应账号 $username，直接调用 grant_credits 发放即可"
        }
        val owner = AccountStore.qqOf(username)
        if (owner != null && owner != ctx.qq) {
            return "ERROR: 账号 $username 已经绑在另一个 QQ 上了，不能让第三方代领"
        }
        // 有官方查询接口就先查，没有就留给发放时的 404 兜底
        if (c.userCheckPath.isNotBlank()) {
            when (checkUserExists(username)) {
                true -> Unit
                false -> return "NOT_FOUND: 站点上没有叫 $username 的账号（请对方去 ${c.siteUrl} 核对账号名，注意不是昵称）"
                null -> Unit
            }
        }
        AccountStore.bind(ctx.qq, username)
        return "OK: 已记下 ${ctx.qq}（${ctx.senderName}）对应站点账号 $username，以后直接调用 grant_credits"
    }

    /** 查当前对话者的绑定：有人要积分时先调这个，别靠猜也别问账号名 */
    fun checkAccount(ctx: ToolContext): String {
        if (!c.enabled) return "ERROR: 积分功能没开启"
        val username = AccountStore.usernameOf(ctx.qq)
            ?: return "NEED_ACCOUNT: 本地绑定表里没有 QQ ${ctx.qq}（${ctx.senderName}）的站点账号，" +
                "真要给的时候才问对方账号名（登录用户名，不是昵称）"
        val used = CreditLedger.todayTotal(username)
        val left = (c.dailyCap - used).coerceAtLeast(0)
        val isOwner = ctx.qq == c.walletOwnerQq
        val perTime = if (isOwner) c.maxAmount else minOf(c.maxAmount, c.guestMaxAmount)
        return "OK: QQ ${ctx.qq}（${ctx.senderName}）本地已绑定站点账号「$username」" +
            (if (isOwner) "（钱包主人本人）" else "") +
            "，单次最多 $perTime 点，今天已发 $used 点、还剩 $left 点。" +
            "这是本地记录：直接调用 grant_credits 就行，不要问账号名、不要说不知道、不要调 bind_account。"
    }

    /** 发放：1~10 点、每账号每日上限、原因必须体现是透子给的 */
    fun grant(ctx: ToolContext, rawAmount: Int, rawReason: String): String {
        if (!c.enabled) return "ERROR: 积分功能没开启"
        if (c.apiKey.isBlank() || c.apiKey == "REPLACE_ME") {
            return "ERROR: 发放密钥没配置（credit.apiKey），管理员需要先在站点后台配置好 EXTERNAL_CREDIT_API_KEY"
        }
        val username = AccountStore.usernameOf(ctx.qq)
            ?: return "NEED_ACCOUNT: 还不知道对方的站点账号。先问一句对方的账号名（登录用的用户名，不是昵称），" +
                "拿到后调用 bind_account；如果对方说不清，让 TA 去 ${c.siteUrl} 看一下"

        val used = CreditLedger.todayTotal(username)
        val remaining = (c.dailyCap - used).coerceAtLeast(0)
        if (remaining <= 0) {
            return "LIMIT: 账号 $username 今天已经拿满 ${c.dailyCap} 点了，明天再说"
        }

        val isOwner = ctx.qq == c.walletOwnerQq
        // 钱包主人本人可以给到 maxAmount；其他人先被推辞过，真给了也只给小额度
        val perRequest = if (isOwner) c.maxAmount else minOf(c.maxAmount, c.guestMaxAmount)
        val min = c.minAmount.coerceAtLeast(1)
        val max = minOf(perRequest, remaining).coerceAtLeast(min)
        val amount = if (rawAmount <= 0) min else rawAmount.coerceAtMost(max).coerceAtLeast(min)
        val reason = pickReason(rawReason)
        val requestId = CreditLedger.newRequestId(ctx.qq)

        var lastError = ""
        repeat(2) { attempt ->
            val result = postGrant(username, amount, reason, requestId)
            when (result) {
                is GrantResult.Ok -> {
                    CreditLedger.record(username, amount, requestId)
                    val balance = result.balance
                    return "OK: 已给账号 $username 发了 $amount 点积分，原因「$reason」" +
                        (if (balance >= 0) "，发放时余额 $balance" else "") +
                        "，账号今天累计 $used+$amount 点（每日上限 ${c.dailyCap}）"
                }

                is GrantResult.NotFound -> return "NOT_FOUND: 站点上没有叫 $username 的账号，" +
                    "别硬发。让对方去 ${c.siteUrl} 核对账号名（要登录用户名，不是昵称），确认后再调用 bind_account"

                is GrantResult.Fatal -> return result.message

                is GrantResult.Retryable -> lastError = result.message
            }
            if (attempt == 0) Thread.sleep(800)
        }
        return "ERROR: 发放接口连不上或超时（$lastError），稍后再试一次；请求编号 $requestId 已经记下，别换编号重复发"
    }

    private fun pickReason(raw: String): String {
        val trimmed = raw.trim()
        // 模型给的原因里必须有"透子"，否则回到模板里随机挑一条
        val candidate = if (trimmed.length in 3..300 && trimmed.contains("透子")) trimmed
        else c.reasons.random()
        return candidate.take(300)
    }

    private sealed class GrantResult {
        data class Ok(val balance: Int) : GrantResult()
        object NotFound : GrantResult()
        data class Fatal(val message: String) : GrantResult()
        data class Retryable(val message: String) : GrantResult()
    }

    private fun postGrant(username: String, amount: Int, reason: String, requestId: String): GrantResult {
        val url = URL(c.baseUrl + c.grantPath)
        val body = JSONObject()
            .put("username", username)
            .put("amount", amount)
            .put("reason", reason)
            .put("request_id", requestId)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = c.timeoutSeconds * 1000
            readTimeout = c.timeoutSeconds * 1000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "Bearer ${c.apiKey}")
            setRequestProperty("User-Agent", "mirai-gpt-agent/2.4")
        }
        return try {
            conn.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() } ?: ""
            when (code) {
                200 -> GrantResult.Ok(runCatching { JSONObject(text).optInt("balance", -1) }.getOrDefault(-1))
                404 -> GrantResult.NotFound
                401 -> GrantResult.Fatal("ERROR: 发放密钥无效（401），管理员需要检查 credit.apiKey 和站点环境变量")
                409 -> GrantResult.Fatal("ERROR: 请求编号冲突（409），这笔没发出去，请稍后重试")
                422 -> GrantResult.Fatal("ERROR: 参数不合法（422）：${text.take(200)}")
                503 -> GrantResult.Fatal("ERROR: 站点的外部积分接口没启用（503），管理员需要先配置密钥")
                else -> GrantResult.Retryable("HTTP $code ${text.take(200)}")
            }
        } catch (e: Exception) {
            GrantResult.Retryable("${e.javaClass.simpleName}: ${e.message}")
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /** 可选的账号存在性查询：模板里能用 {username} 占位，也可以自动拼 ?username= */
    private fun checkUserExists(username: String): Boolean? {
        val encoded = URLEncoder.encode(username, "UTF-8")
        val path = if (c.userCheckPath.contains("{username}")) {
            c.userCheckPath.replace("{username}", encoded)
        } else {
            c.userCheckPath + (if (c.userCheckPath.contains("?")) "&" else "?") + "username=" + encoded
        }
        val url = runCatching { URL(c.baseUrl + path) }.getOrNull() ?: return null
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = c.timeoutSeconds * 1000
            readTimeout = c.timeoutSeconds * 1000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "mirai-gpt-agent/2.4")
        }
        return try {
            when (conn.responseCode) {
                200 -> true
                404 -> false
                else -> null
            }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { conn.disconnect() }
        }
    }
}

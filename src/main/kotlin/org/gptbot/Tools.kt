package org.gptbot

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** 工具执行时的会话上下文：积分这类工具必须知道是谁在要 */
data class ToolContext(
    val qq: Long,
    val key: String,
    val senderName: String = "",
)

/**
 * Agent 可调用的工具集合。全部为白名单式：配置里关掉即不注册、模型也看不到。
 */
class ToolRegistry(
    private val cfg: AgentConfig.Config,
    /** 工具调用的调试日志（谁调的、参数、结果），出问题时看日志就能定位 */
    private val log: (String) -> Unit = {},
) {

    private val tools = cfg.tools

    private val credit = CreditService(cfg)

    private val sandbox: File = File(tools.sandboxDir).absoluteFile.also { it.mkdirs() }

    fun definitions(): JSONArray {
        if (!tools.enabled) return JSONArray()
        val arr = JSONArray()

        if (tools.timeEnabled) {
            arr.put(
                fn(
                    "get_current_time",
                    "获取服务器当前日期与时间。",
                    emptyParams()
                )
            )
        }

        if (tools.httpEnabled && tools.httpAllowedHosts.isNotEmpty()) {
            arr.put(
                fn(
                    "http_request",
                    "访问白名单内的 HTTP 接口并返回响应文本。可用域名：${tools.httpAllowedHosts.joinToString(", ")}",
                    params(
                        "url" to prop("string", "完整 URL，必须是白名单域名"),
                        "method" to prop("string", "GET 或 POST，默认 GET"),
                        "body" to prop("string", "POST 时的请求体，默认空"),
                    ),
                    required = listOf("url")
                )
            )
        }

        if (tools.fileReadEnabled) {
            arr.put(
                fn(
                    "read_text_file",
                    "读取沙箱目录内的文本文件（相对路径）。",
                    params("path" to prop("string", "相对于沙箱根目录的路径")),
                    required = listOf("path")
                )
            )
        }

        if (tools.fileWriteEnabled) {
            arr.put(
                fn(
                    "write_text_file",
                    "向沙箱目录内写入文本文件（覆盖写，会自动创建父目录）。",
                    params(
                        "path" to prop("string", "相对于沙箱根目录的路径"),
                        "content" to prop("string", "要写入的完整文本"),
                    ),
                    required = listOf("path", "content")
                )
            )
        }

        if (tools.listDirEnabled) {
            arr.put(
                fn(
                    "list_directory",
                    "列出沙箱目录内某个路径下的文件与子目录。",
                    params("path" to prop("string", "相对于沙箱根目录的路径，空表示根目录")),
                )
            )
        }

        if (cfg.credit.enabled) {
            arr.put(
                fn(
                    "check_credit_account",
                    "查当前对话者在本地绑定了哪个站点积分账号、今天已经拿了多少、还能拿多少。" +
                        "有人要积分/金币时第一步先调用本工具，再决定发多少；" +
                        "返回 OK 说明已经绑定，直接调用 grant_credits，不要问账号名、不要装不知道；" +
                        "只有返回 NEED_ACCOUNT 时才问对方账号名。",
                    emptyParams()
                )
            )
            arr.put(
                fn(
                    "grant_credits",
                    "给当前对话者绑定的站点账号发放积分（每账号每天合计上限 ${cfg.credit.dailyCap} 点）。" +
                        "钱是${cfg.credit.walletOwnerName}的：第一次被要积分时要先推辞、不要调用本工具，" +
                        "等对方再要才勉强给，单次最多 ${cfg.credit.guestMaxAmount} 点；" +
                        "只有钱包主人本人（QQ ${cfg.credit.walletOwnerQq}）可以直接给，最多 ${cfg.credit.maxAmount} 点。" +
                        "返回 NEED_ACCOUNT 表示还不知道对方账号，先问账号名再调用 bind_account；" +
                        "系统提示里写明对方已绑定时直接调用本工具，不要再问账号名。",
                    params(
                        "amount" to prop(
                            "integer",
                            "发放点数，${cfg.credit.minAmount}~${cfg.credit.maxAmount}（非钱包主人最多 ${cfg.credit.guestMaxAmount}），默认 ${cfg.credit.minAmount}",
                        ),
                        "reason" to prop("string", "发放原因，必须体现是透子给的，比如「透子的礼物~」，留空则自动挑一条"),
                    ),
                )
            )
            arr.put(
                fn(
                    "bind_account",
                    "把当前 QQ 与站点的登录账号名绑定并存到本地（一次绑定，之后不用再问）。" +
                        "账号名是登录用户名，不是昵称；格式 2~32 位字母数字下划线点连字符。",
                    params("username" to prop("string", "对方的站点登录账号名")),
                    required = listOf("username"),
                )
            )
        }
        return arr
    }

    /** 执行一次工具调用，任何异常都转成文本返回给模型 */
    fun execute(name: String, args: JSONObject, ctx: ToolContext? = null): String {
        val result = runTool(name, args, ctx)
        val who = if (ctx == null) "-" else "qq=${ctx.qq}"
        log("[工具] $who $name ${args.toString().take(150)} → ${result.replace('\n', ' ').take(220)}")
        return result
    }

    private fun runTool(name: String, args: JSONObject, ctx: ToolContext? = null): String {
        if (!tools.enabled && name != "grant_credits" && name != "bind_account" && name != "check_credit_account") {
            return "ERROR: 工具功能已关闭"
        }
        return try {
            when (name) {
                "get_current_time" -> LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss (EEEE)"))

                "http_request" -> httpRequest(args)
                "read_text_file" -> readFile(args)
                "write_text_file" -> writeFile(args)
                "list_directory" -> listDir(args)
                "check_credit_account" -> {
                    if (!cfg.credit.enabled) return "ERROR: 积分功能没开启"
                    val c = ctx ?: return "ERROR: 当前会话拿不到 QQ 号，没法查绑定"
                    credit.checkAccount(c)
                }
                "grant_credits" -> {
                    if (!cfg.credit.enabled) return "ERROR: 积分功能没开启"
                    val c = ctx ?: return "ERROR: 当前会话拿不到 QQ 号，没法发积分"
                    credit.grant(c, args.optInt("amount", 0), args.optString("reason", ""))
                }
                "bind_account" -> {
                    if (!cfg.credit.enabled) return "ERROR: 积分功能没开启"
                    val c = ctx ?: return "ERROR: 当前会话拿不到 QQ 号，没法绑定账号"
                    credit.bind(c, args.optString("username", ""))
                }
                else -> "ERROR: 未知工具 $name"
            }
        } catch (e: ToolDenied) {
            "ERROR: ${e.message}"
        } catch (e: Exception) {
            "ERROR: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    private fun httpRequest(args: JSONObject): String {
        if (!tools.httpEnabled) throw ToolDenied("HTTP 工具已关闭")
        val raw = args.optString("url", "")
        if (raw.isBlank()) throw ToolDenied("url 不能为空")
        val url = URL(raw)
        if (url.protocol != "http" && url.protocol != "https") throw ToolDenied("只允许 http/https")
        val host = url.host.lowercase()
        val allowed = tools.httpAllowedHosts.any { host == it.lowercase() || host.endsWith("." + it.lowercase()) }
        if (!allowed) throw ToolDenied("域名不在白名单内：$host")

        val method = args.optString("method", "GET").uppercase()
        if (method == "POST" && !tools.httpAllowPost) throw ToolDenied("POST 未开启，请在配置 http.allowPost 中开启")
        if (method != "GET" && method != "POST") throw ToolDenied("只支持 GET/POST")

        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = tools.httpTimeoutSeconds * 1000
            readTimeout = tools.httpTimeoutSeconds * 1000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "mirai-gpt-agent/2.0")
        }
        if (method == "POST") {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.outputStream.use { it.write(args.optString("body", "").toByteArray(StandardCharsets.UTF_8)) }
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() } ?: ""
        conn.disconnect()
        val body = if (text.length > tools.httpMaxResponseChars) {
            text.take(tools.httpMaxResponseChars) + "\n...(响应过长已截断)"
        } else text
        return "HTTP $code\n$body"
    }

    private fun readFile(args: JSONObject): String {
        if (!tools.fileReadEnabled) throw ToolDenied("文件读取已关闭")
        val f = resolve(args.optString("path", ""))
        if (!f.isFile) throw ToolDenied("文件不存在：${rel(f)}")
        if (f.length() > tools.fileReadMaxBytes) throw ToolDenied("文件过大（${f.length()} 字节，上限 ${tools.fileReadMaxBytes}）")
        return f.readText()
    }

    private fun writeFile(args: JSONObject): String {
        if (!tools.fileWriteEnabled) throw ToolDenied("文件写入已关闭")
        val content = args.optString("content", "")
        if (content.toByteArray().size > tools.fileWriteMaxBytes) {
            throw ToolDenied("内容过大（上限 ${tools.fileWriteMaxBytes} 字节）")
        }
        val f = resolve(args.optString("path", ""))
        f.parentFile?.mkdirs()
        f.writeText(content)
        return "已写入 ${rel(f)}（${content.toByteArray().size} 字节）"
    }

    private fun listDir(args: JSONObject): String {
        if (!tools.listDirEnabled) throw ToolDenied("目录列举已关闭")
        val path = args.optString("path", "").ifBlank { "." }
        val f = resolve(path)
        if (!f.isDirectory) throw ToolDenied("目录不存在：$path")
        val entries = f.listFiles()?.sortedBy { it.name }?.take(tools.listDirMaxEntries) ?: emptyList()
        if (entries.isEmpty()) return "（空目录）"
        return entries.joinToString("\n") { (if (it.isDirectory) "[目录] " else "[文件] ") + it.name + (if (it.isFile) " (${it.length()} B)" else "") }
    }

    /** 沙箱路径解析：禁止越界 */
    private fun resolve(userPath: String): File {
        if (userPath.isBlank()) throw ToolDenied("路径不能为空")
        val candidate = File(sandbox, userPath).canonicalFile
        val root = sandbox.canonicalPath + File.separator
        if (candidate.path != sandbox.canonicalPath && !candidate.path.startsWith(root)) {
            throw ToolDenied("路径越界，仅允许访问沙箱目录")
        }
        return candidate
    }

    private fun rel(f: File): String = runCatching { f.relativeTo(sandbox).path }.getOrDefault(f.name)

    private class ToolDenied(message: String) : RuntimeException(message)

    private fun fn(name: String, description: String, parameters: JSONObject, required: List<String> = emptyList()): JSONObject {
        val p = parameters
        if (required.isNotEmpty()) p.put("required", JSONArray(required))
        return JSONObject()
            .put("type", "function")
            .put(
                "function", JSONObject()
                    .put("name", name)
                    .put("description", description)
                    .put("parameters", p)
            )
    }

    private fun emptyParams(): JSONObject = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject())

    private fun params(vararg entries: Pair<String, JSONObject>): JSONObject {
        val props = JSONObject()
        entries.forEach { (k, v) -> props.put(k, v) }
        return JSONObject().put("type", "object").put("properties", props)
    }

    private fun prop(type: String, description: String): JSONObject =
        JSONObject().put("type", type).put("description", description)
}

# mirai-gpt-agent

mirai-console 的 LLM 聊天 / agent 插件（2.0.0）。原 1.x 版本只能调度老式 Chat 接口、靠 `~` 前缀手动触发，已重写为：

- **分组独立记忆**：每个 QQ 群、每个私聊各自一份对话记录，互不干扰
- **自动判断是否发言**：@机器人 / 引用机器人消息 / 关键词 / 模型判定（带群聊上下文与自攒随记）
- **OpenAI 兼容接口**：`/chat/completions`，支持 `tools`（function calling）
- **agent 工具**：当前时间、白名单 HTTP 请求、沙箱内文件读写与列目录，全部可在配置里开关
- **群白名单**：不在白名单的群完全不响应

## 2.1.1 日志

每条群消息都会打一行判定原因，便于确认"为什么没回"：

```
[判定] group-1090338744 发言 ← @提及「透子，跑团吗」
[判定] group-1090338744 发言 ← 关键词「透子」「透子在吗」
[判定] group-1090338744 不发言 ← 冷却中，还剩 12s「今天吃啥」
[判定] group-1090338744 不发言 ← 模型判定「哈哈哈」
[判定] group-1090338744 不发言 ← 聊天已关闭「在吗」
[图片] 一张图处理失败，已忽略
[图片] 本轮附带 1 张，编码后约 320 KB
[模型/判定] 210ms 62tok 输出 2 字
[模型/对话] 240ms 42tok 输出 16 字
[回复] group-1090338744 460ms → 行啊，不过我剧本还没看完
```

## 2.1 新增能力

| 能力 | 配置 | 行为 |
|---|---|---|
| 周期性总结 + 备份 | `memory.summaryEnabled` / `summaryEveryMessages` / `summaryKeepRecent` | 每攒够 N 条消息，就把老对话压缩成摘要，原文另存 `data/org.gptbot.agent/memory/backup/<会话>-<时间>-summary.md`，记忆里只留最近 keepRecent 条原文 |
| 不翻旧账 | 摘要自动注入系统提示词 | 摘要标注为"背景记忆"，除非对方重新提起，模型不许复述或接着聊旧话题 |
| 括号规则 | `trigger.parenRuleEnabled` | 对方消息里出现 `()`/`（）` 时，回复被强制整条包进圆括号（模型忘了包，插件自动补） |
| 一句话限制 | `bot.maxSentences` / `bot.maxReplyChars` | 按 `。！？!?…；;` 断句截断，`1` = 只回一句；字符上限兜底 |
| 硬开关 | `@我 chaton` / `@我 chatoff` | 直接由插件处理，不经过模型；状态存 `data/org.gptbot.agent/chatstate.json`，重启保留。关闭期间该会话消息不回复、也不写记忆 |
| 清空记忆 | `@我 chatempty` | 连摘要一起清掉，从零开始 |

> 指令忽略大小写与空格：`@透子 chaton`、`@透子chaton`、`@透子 CHATON` 都识别。
> 权限：默认任何人都能用；`bot.chatControlAdminsOnly: true` 后只允许群主/群管理员和 `bot.admins`。
> `bot.chatDefaultOn`: `true` = 新会话默认就开着聊天（可用 chatoff 关）；`false` = 必须先 `@我 chaton` 才说话。

## 编译

```bat
set JAVA_HOME=D:\UGit\.buildtools\jdk11\jdk-11.0.33+10
D:\UGit\.buildtools\apache-maven-3.9.9\bin\mvn.cmd -B -DskipTests clean package
```

产物：`target/mirai-gpt-agent-2.0.0.jar`（约 156 KB，只内嵌了 org.json，mirai 依赖在运行期由 console 提供）。

> 1.x 用的 Gradle 构建已替换为 Maven（Kotlin 1.8.21 + JDK 11）。

## 安装

把 jar 放进 `mirai/plugins/`，重启 mirai-console。首次启动会生成配置：

```
mirai/config/org.gptbot.agent/config.json      # 主配置
mirai/config/org.gptbot.agent/personas/        # 可选：会话专属人设 <group-123>.txt
mirai/data/org.gptbot.agent/memory/            # 每个会话的记忆 json
mirai/data/org.gptbot.agent/workspace/         # 文件工具的沙箱根目录
```

## 配置说明（config.json）

### llm —— 模型接口

| 字段 | 说明 |
|---|---|
| `baseUrl` | OpenAI 兼容地址，例如 `https://api.deepseek.com/v1`、`https://dashscope.aliyuncs.com/compatible-mode/v1`、`https://api.openai.com/v1` |
| `apiKey` | 密钥，留 `REPLACE_ME` 时插件只会告警不会工作 |
| `model` | 模型名，如 `deepseek-chat`、`qwen-plus`、`gpt-4o-mini` |
| `temperature` / `maxTokens` | 采样温度 / 单次回复上限 |
| `timeoutSeconds` | 单次 HTTP 超时 |
| `maxToolRounds` | 工具调用最多循环几轮（防死循环） |
| `vision.enabled` | 默认 `true`，把消息里的图片转成 `image_url` 发给模型（需模型本身支持图片，如 `deepseek/deepseek-v4-flash`） |
| `vision.maxImages` | 一轮最多带几张图，默认 2 |
| `vision.maxBytes` | 单张图片压缩后的上限，默认 1 MB（超出会等比缩到 `maxEdge` 并压 JPEG） |
| `vision.maxEdge` | 长边上限，默认 1280 px |
| `disableReasoning` | 默认 `true`，请求带 `reasoning_effort: none` 关掉推理模型的思考过程；实测同一句话 2.3s→0.22s，token 195→42 |
| `extraBody` | 原样合并进请求体的额外参数（适配不同中转，可覆盖除 `messages` 外的字段） |
| `extraHeaders` | 额外请求头（自建中转常用） |

### bot —— 生效范围与会话

| 字段 | 说明 |
|---|---|
| `enabledGroups` | **群白名单**，只有这些群会响应；测试期只放了 `1090338744` |
| `enabledPrivate` | 是否响应私聊（每个私聊独立记忆） |
| `admins` | 管理员 QQ，可用 `/ai` 指令 |
| `maxHistoryMessages` / `maxHistoryChars` | 每个会话保留的记忆条数 / 字符上限，超出从最旧开始丢 |
| `maxReplyChars` | 单条回复最大长度，超出截断 |
| `maxSentences` | 每段最多几句（`1` = 每段一句，`0` = 不限） |
| `commandPrefix` | 管理指令前缀，默认 `/ai` |
| `chatDefaultOn` | 新会话默认是否开启聊天 |
| `chatControlAdminsOnly` | `chaton/chatoff/chatempty` 是否只允许管理员 |
| `multiSegmentEnabled` | 允许模型在需要长描述时用 `\|\|\|` 把回复拆成多条依次发出（默认 `true`） |
| `segmentDelayMs` | 多段之间的间隔，默认 2000 ms |
| `maxSegments` | 最多拆几段，默认 3 |

### memory —— 记忆压缩与备份

| 字段 | 说明 |
|---|---|
| `summaryEnabled` | 是否启用周期性总结 |
| `summaryEveryMessages` | 攒够多少条新消息触发一次总结（最小 4） |
| `summaryKeepRecent` | 总结后保留最近多少条原文，其余压进摘要 |
| `summaryModel` | 总结用模型，留空用主模型 |
| `summaryMaxChars` | 摘要最大字数 |
| `backupEnabled` | 是否把原文备份到 `memory/backup/`（markdown，人可直接读） |

### trigger —— 什么时候说话

| 字段 | 说明 |
|---|---|
| `keywords` | 命中即回答（群里除 @ 之外的第二种触发） |
| `requireAtInGroup` | `true` 时群里只有 @ 或引用机器人才回答，忽略关键词与模型判定 |
| `smartJudge.enabled` | 让模型根据最近群聊判断"这条要不要回"，关掉就只认 @ / 引用 / 关键词 |
| `smartJudge.model` | 判定用模型（留空用主模型） |
| `smartJudge.prompt` | 判定提示词，要求模型只回 YES / NO |
| `smartJudge.historySize` | 判定时带多少条最近的群聊记录 |
| `smartJudge.notesEnabled` | 是否让机器人自己攒"接话随记"（复盘群聊，学会什么情况别插嘴） |
| `smartJudge.notesEvery` | 每判定多少次复盘一次随记；0 = 只在随记为空时生成 |
| `smartJudge.notesMaxChars` | 随记最大字数，`0` = 不限（默认 0） |

优先级：@ 或引用 → 必定回答；否则 `requireAtInGroup` 拦截 → 关键词 → 模型判定（判定失败按不发言处理）。
没有概率插话：每一条普通消息都过一遍模型判定。

**判定随记**：插件把最近的群聊（含机器人自己的发言）存在内存里，每隔 `notesEvery` 次判定，
让模型复盘一次"什么情况该接话、什么情况别插嘴"，结果永久保存到
`data/org.gptbot.agent/judgenotes/<会话>.txt`，下次判定时随上下文一起送给判定模型。
群里有人说「这个不需要你回复」「好烦啊透子一直说」这类话时，插件会立刻静默并马上复盘，把这条教训写进随记。
随记分两部分写：「可以多说 / 该接话的情况」和「别插话的情况」，是分场合的经验，不是整体松紧开关。

**偏好反馈**：群友说「多说点」「多聊两句」或「别说了」「话太多」时，原话会存进
`data/org.gptbot.agent/feedback/<会话>.txt`（保留最近 8 条），同时立刻触发一次随记复盘，
把对应的具体场合写进随记；之后判定和生成回复都会带上这份反馈。

**多段回复**：需要长描述（场景、规则、步骤）时，模型可以把一条回复用 `|||` 拆成 2~3 段，
插件按 `segmentDelayMs`（默认 2 秒）一条一条发出去，像人在群里连打几句；
平时仍是一句。有人嫌它话多时，模型会被明确要求不要拆段。

**表情包**：模型在回复里写 `[表情:坏笑]` 这种标记，插件会把它换成一张图片消息发出去，
可以和 `|||` 分段混着用，按顺序依次发（情绪明显时才发，一次一个）。表情名与文件在
`assets.stickers` 里配置，文件放 `config/org.gptbot.agent/stickers/`。
以后加表情只需丢文件 + 配置加一行，不用改代码。
立绘在 `assets.portrait`，`/ai portrait` 可以直接发出来。

### persona —— 人设

- `systemPrompt`：全局系统提示词。
- `personaDir`：目录名，放 `personas/group-1090338744.txt` 即可给某个群单独加设定（覆盖追加到系统提示词末尾）。

会话标识规则：群 = `group-<群号>`，私聊 = `private-<QQ号>`。

### tools —— agent 能力

| 字段 | 说明 |
|---|---|
| `enabled` | 总开关，关闭后模型完全看不到工具 |
| `sandboxDir` | 文件工具根目录，所有路径都限制在里面（防目录穿越） |
| `timeEnabled` | `get_current_time` |
| `http.enabled` | `http_request` |
| `http.allowedHosts` | 域名白名单，支持后缀匹配（`api.github.com` 也放行 `xx.api.github.com`） |
| `http.allowPost` | 是否允许 POST（默认只 GET） |
| `http.maxResponseChars` | 响应截断长度 |
| `fileRead.enabled` / `maxBytes` | 读文本文件（沙箱内） |
| `fileWrite.enabled` / `maxBytes` | 写文本文件（默认关闭） |
| `listDir.enabled` / `maxEntries` | 列目录 |

工具执行出错一律以 `ERROR: ...` 文本回给模型，不会中断对话。

### credit —— 站点积分发放

有人跟透子要金币/积分时，它不情不愿地用 `grant_credits` 调站点外部接口发 1~10 点，
原因由配置模板随机挑（都带"透子"字样）。账号怎么对上：

| 文件 | 作用 |
|---|---|
| `config/org.gptbot.agent/accounts.json` | QQ号 → 站点账号名，一行一个，可手改（`{"645937001":"mashu"}`） |
| `data/org.gptbot.agent/credit_ledger.json` | 每日发放记录（每账号每天合计上限 `dailyCap`）+ 请求编号，自动生成 |

流程：`grant_credits` 发现本地没有账号 → 返回 `NEED_ACCOUNT`，模型问对方"网站账号名是什么" →
`bind_account` 落盘 → 再发。站点查不到该账号时接口回 404，模型据此说"没查到"并把
`siteUrl` 发出去。若站点以后提供查询接口，把模板填进 `credit.userCheckPath`（支持 `{username}` 占位），
绑定前会先查一次。

态度：默认**先推辞不发**（"不好吧，那又不是我的钱包"），对方再要才勉强给，且单次最多
`guestMaxAmount`；钱包主人 `walletOwnerQq` 本人来要则不推辞、可直接给到 `maxAmount`，系统提示里会
标注本条消息的发送者是不是钱包主人。

| 字段 | 说明 |
|---|---|
| `enabled` | 开关，关闭后模型看不到这两个工具 |
| `baseUrl` / `grantPath` | 发放接口地址，如 `https://www.escmashu.cn` + `/api/external/credits/grant` |
| `apiKey` | 站点 `EXTERNAL_CREDIT_API_KEY` 同值密钥，**必填** |
| `minAmount` / `maxAmount` | 单次发放范围，默认 1~10 |
| `guestMaxAmount` | 钱包主人以外的单次上限，默认 3（插件侧硬限制） |
| `walletOwnerQq` / `walletOwnerName` | 钱包主人，默认 `645937001` / `麻薯`：本人来要不推辞、可给到 `maxAmount` |
| `dailyCap` | 每账号每天合计上限，默认 10 |
| `siteUrl` | 查不到账号时发给对方的站点地址 |
| `reasons` | 原因模板，随机挑一条 |

## 管理指令（仅 admins）

| 指令 | 作用 |
|---|---|
| `/ai status` | 查看当前会话 key、模型、记忆条数、群白名单 |
| `/ai reset` | 清空当前会话记忆（群指令只清该群，私聊只清该私聊） |
| `/ai reload` | 重新读取 config.json（改完配置可免重启） |

硬开关（不需要管理员，直接 @ 机器人）：`@我 chaton` / `@我 chatoff` / `@我 chatempty`。

## 与 1.x 的差异

| 1.x | 2.0 |
|---|---|
| `~` 前缀手动触发 | @ / 引用 / 关键词 / 概率 / 模型判定自动触发 |
| 单一全局对话历史 | 每群、每私聊独立记忆并落盘 |
| 老 Chat API | OpenAI 兼容 `chat/completions` + function calling |
| 无工具能力 | 时间 / 白名单 HTTP / 沙箱文件读写 |
| 无群限制 | 群白名单 |

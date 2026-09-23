package com.jev.probe.jev

import android.util.Log
import com.jev.probe.core.Analysis
import com.jev.probe.core.ApiProviders
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Choice
import com.jev.probe.core.RankedReply
import com.jev.probe.core.Score
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Universal Multi-Provider Client (Cherry Studio style):
 * Supports OpenRouter, DeepSeek, OpenAI, SiliconFlow, Moonshot, Zhipu, and custom OpenAI-compatible endpoints.
 *
 * Built-in psychological decision prompts faithfully reproduce Jev's 7 calibrated criteria:
 * - Intent disambiguation (subtext & loyalty tests vs literal asks)
 * - 10-level danger scale (0~9)
 * - Needs detection (apology, care, action, explanation, nothing)
 * - Action planning (check_history, apologize, say_less, acknowledge, give_commitment, explain, make_plan)
 * - 3 distinct, spoken WeChat-style candidate drafts respecting custom user personas.
 */
class JevClient(
    val provider: String,
    val baseUrl: String,
    val key: String,
    val replyModel: String,
    val customStyle: String = "",
    var draftClient: JevClient? = null
) {

    /** Legacy constructor for backward compatibility */
    constructor(key: String, replyModel: String) : this(
        ApiProviders.OPENROUTER.id,
        ApiProviders.OPENROUTER.defaultBaseUrl,
        key,
        replyModel,
        ""
    )

    val isTypeSafe = provider.equals(ApiProviders.TYPESAFE.id, ignoreCase = true)
    private val isOpenRouter = provider.equals(ApiProviders.OPENROUTER.id, ignoreCase = true)
    private val decisionsUrl = "https://openrouter.ai/api/alpha/decisions"
    private val typeSafeSystemOneUrl = "${baseUrl.trim().trimEnd('/')}/v1/systemone"

    private fun getChatCompletionsUrl(): String {
        val clean = baseUrl.trim().trimEnd('/')
        return when {
            clean.endsWith("/chat/completions") -> clean
            clean.endsWith("/v1") || clean.endsWith("/v4") -> "$clean/chat/completions"
            else -> "$clean/v1/chat/completions"
        }
    }

    /** Run judgment questions (~1s). */
    fun judge(snapshot: ChatSnapshot, relationship: String): Analysis {
        val start = System.currentTimeMillis()
        try {
            return when {
                isTypeSafe -> judgeViaTypeSafe(snapshot, relationship, start)
                isOpenRouter -> {
                    try {
                        judgeViaOpenRouter(snapshot, relationship, start)
                    } catch (e: Exception) {
                        Log.w(TAG, "OpenRouter decisions failed, falling back to chat completions: ${e.message}")
                        judgeViaChatCompletions(snapshot, relationship, start)
                    }
                }
                else -> judgeViaChatCompletions(snapshot, relationship, start)
            }
        } catch (e: Exception) {
            Log.w(TAG, "judge failed: ${e.message}")
            return Analysis(
                null, null, null, null, null, null, null, emptyList(),
                System.currentTimeMillis() - start, error = readableError(e)
            )
        }
    }

    /** Draft 3 candidate replies and rank them. */
    fun draftAndRank(snapshot: ChatSnapshot, relationship: String): List<RankedReply> {
        return when {
            isTypeSafe -> draftAndRankViaTypeSafe(snapshot, relationship)
            isOpenRouter -> {
                try {
                    draftAndRankViaOpenRouter(snapshot, relationship)
                } catch (e: Exception) {
                    Log.w(TAG, "OpenRouter draftAndRank failed, falling back to chat completions: ${e.message}")
                    draftAndRankViaChatCompletions(snapshot, relationship)
                }
            }
            else -> draftAndRankViaChatCompletions(snapshot, relationship)
        }
    }

    /** Convenience for settings connectivity test: judge + replies, sequential. */
    fun analyze(snapshot: ChatSnapshot, relationship: String): Analysis {
        val a = judge(snapshot, relationship)
        if (a.error != null) return a
        val ranked = try { draftAndRank(snapshot, relationship) } catch (e: Exception) { emptyList() }
        return a.copy(rankedReplies = ranked)
    }

    // =========================================================================
    // TypeSafe native System One decision endpoints (Official Jev API)
    // =========================================================================

    private fun judgeViaTypeSafe(snapshot: ChatSnapshot, relationship: String, start: Long): Analysis {
        val model = if (replyModel.isNotBlank()) replyModel else "jev-latest"
        val body = JSONObject()
            .put("model", model)
            .put("state", JevQuestions.buildState(snapshot, relationship))
            .put("questions", JevQuestions.judge())
        val answers = postJson(typeSafeSystemOneUrl, body).optJSONObject("answers") ?: JSONObject()
        return Analysis(
            trueIntent = parseChoice(answers.optJSONObject("true_intent")),
            dangerLevel = parseScore(answers.optJSONObject("danger_level")),
            sheNeeds = parseChoice(answers.optJSONObject("she_needs")),
            shouldReplyNow = answers.optJSONObject("should_reply_now")?.optDouble("noul"),
            bestAction = parseChoice(answers.optJSONObject("best_action")),
            tensionResolved = answers.optJSONObject("tension_resolved")?.optDouble("noul"),
            literalQuestion = answers.optJSONObject("literal_question")?.optDouble("noul"),
            rankedReplies = emptyList(),
            latencyMs = System.currentTimeMillis() - start
        )
    }

    private fun draftAndRankViaTypeSafe(snapshot: ChatSnapshot, relationship: String): List<RankedReply> {
        val candidates = generateCandidates(snapshot, relationship)
        val questions = JSONObject().put("best_reply",
            JevQuestions.rankQuestion(candidates).getJSONObject("best_reply"))
        val model = if (replyModel.isNotBlank()) replyModel else "jev-latest"
        val body = JSONObject()
            .put("model", model)
            .put("state", JevQuestions.buildState(snapshot, relationship))
            .put("questions", questions)
        val answers = postJson(typeSafeSystemOneUrl, body).optJSONObject("answers") ?: JSONObject()
        return parseRanked(answers.optJSONObject("best_reply"), candidates)
    }

    // =========================================================================
    // OpenRouter native decision endpoints
    // =========================================================================

    private fun judgeViaOpenRouter(snapshot: ChatSnapshot, relationship: String, start: Long): Analysis {
        val body = JSONObject()
            .put("model", "typesafe/jev-1.13")
            .put("state", JevQuestions.buildState(snapshot, relationship))
            .put("questions", JevQuestions.judge())
        val answers = postJson(decisionsUrl, body).optJSONObject("answers") ?: JSONObject()
        return Analysis(
            trueIntent = parseChoice(answers.optJSONObject("true_intent")),
            dangerLevel = parseScore(answers.optJSONObject("danger_level")),
            sheNeeds = parseChoice(answers.optJSONObject("she_needs")),
            shouldReplyNow = answers.optJSONObject("should_reply_now")?.optDouble("noul"),
            bestAction = parseChoice(answers.optJSONObject("best_action")),
            tensionResolved = answers.optJSONObject("tension_resolved")?.optDouble("noul"),
            literalQuestion = answers.optJSONObject("literal_question")?.optDouble("noul"),
            rankedReplies = emptyList(),
            latencyMs = System.currentTimeMillis() - start
        )
    }

    private fun draftAndRankViaOpenRouter(snapshot: ChatSnapshot, relationship: String): List<RankedReply> {
        val candidates = generateCandidates(snapshot, relationship)
        val questions = JSONObject().put("best_reply",
            JevQuestions.rankQuestion(candidates).getJSONObject("best_reply"))
        val body = JSONObject()
            .put("model", "typesafe/jev-1.13")
            .put("state", JevQuestions.buildState(snapshot, relationship))
            .put("questions", questions)
        val answers = postJson(decisionsUrl, body).optJSONObject("answers") ?: JSONObject()
        return parseRanked(answers.optJSONObject("best_reply"), candidates)
    }

    // =========================================================================
    // Universal OpenAI-compatible Chat Completions endpoints with Jev Mind
    // =========================================================================

    private fun judgeViaChatCompletions(snapshot: ChatSnapshot, relationship: String, start: Long): Analysis {
        val convo = snapshot.messages.takeLast(10).joinToString("\n") {
            (if (it.side == "me") "我" else "对方") + "：" + it.text
        }
        val sys = """
            你是一个顶级即时通讯（微信/QQ）对话心理研判专家。你精通中文语境中的潜台词、人情世故、情绪试探与冲突阶梯。
            请基于双方关系与对话上下文，对【对方】的最新消息进行深刻心理研判。

            【研判核心准则】：
            1. true_intent (必须严格挑选其一):
               - "confirm_you_care": 试探你在不在乎/记不记得（如："你是不是又忘了"、"那你自己说"、"你最好是"、冷嘲"大忙人"，核心是忠诚与在乎测试，哪怕字面像催你办事）。
               - "vent_anger": 宣泄委屈或愤怒，拉高情绪温度。此时给方案对方根本听不进去，需要先被接住情绪。
               - "request_action": 纯粹、具体的时间/事务/方案要求，不是忠诚度测试。
               - "seek_explanation": 探寻事实原因，询问为什么或发生了什么，不是主要为了道歉或新计划。
               - "casual_chat": 轻松闲聊、打趣互动、分享日常，无火药味与心理测试。
               - "close_topic": 真正和平收尾或真诚原谅（如"好啦/没事了/收到"）。注意：冷淡反讽的"随便你/我习惯了/别找我"是 vent_anger，绝非 close_topic。

            2. danger_level (冲突危险等级，0~9 整数):
               - 0分: 纯轻松闲聊、幽默调侃，无任何抱怨。
               - 1~2分: 轻微打趣或温和提醒，回得笨拙也只略显尴尬。
               - 3~4分: 明显不悦，提到被遗忘、被冷落，但仍给机会补救。
               - 5~6分: 阴阳怪气、冷短回复、"那你最好是"，试探中，回错必将升级。
               - 7~8分: 明确愤怒指责、最后通牒警告（"最后一次"、"以后别找我了"）。
               - 9分: 关系决裂、拉黑、分手。

            3. she_needs (对方此刻最核心的诉求，必须严格挑选其一):
               - "care": 证明你在乎、在听、记得（在乎度测试时，切忌乱编借口，先证明你在乎）。
               - "apology": 真诚认错（针对已确定的具体错误）。
               - "action": 具体的行动、时间安排或事实复述。
               - "explanation": 事实原因解释。
               - "nothing": 事情已平和过去，无多余要求。

            4. best_action (下一步最佳动作，必须严格挑选其一):
               - "check_history": 翻聊天记录核实事实（被质问是否记得时，千万不要瞎猜，必须先看记录）。
               - "apologize": 先真诚道歉。
               - "give_commitment": 给出具体时间承诺与明确安排。
               - "explain": 讲清事实来龙去脉。
               - "acknowledge": 接住情绪、同理心共情、让对方觉得被看见。
               - "say_less": 少说两句、适可而止，避免画蛇添足或火上浇油。
               - "make_plan": 敲定具体约会/任务日程。

            5. should_reply_now: 1.0表示此刻适合立即给实质方案/事实；0.0表示此刻不要急于给实质（需先查记录、先安抚情绪或少说两句）。
            6. tension_resolved: 1.0表示紧张已消除；0.0表示仍有矛盾或试探。

            【输出规范】：必须且仅输出严格的纯 JSON 对象，不得附带 Markdown 标记（如 ```json ）：
            {
              "true_intent": "confirm_you_care",
              "confidence": 0.88,
              "danger_level": 5,
              "she_needs": "care",
              "best_action": "check_history",
              "should_reply_now": 0.0,
              "tension_resolved": 0.0
            }
        """.trimIndent()
        val user = "关系设定：$relationship\n\n最近对话：\n$convo\n\n请严格按规范输出研判 JSON："

        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", sys))
            .put(JSONObject().put("role", "user").put("content", user))
        val body = JSONObject()
            .put("model", replyModel)
            .put("messages", messages)
            .put("temperature", 0.3)

        val resp = postJson(getChatCompletionsUrl(), body)
        val content = resp.optJSONArray("choices")?.optJSONObject(0)
            ?.optJSONObject("message")?.optString("content") ?: "{}"
        val parsedJson = JSONObject(cleanJson(content))

        val intentStr = parsedJson.optString("true_intent", "casual_chat")
        val conf = parsedJson.optDouble("confidence", 0.85).coerceIn(0.1, 1.0)
        val danger = parsedJson.optDouble("danger_level", 2.0).coerceIn(0.0, 9.0)
        val needsStr = parsedJson.optString("she_needs", "care")
        val actionStr = parsedJson.optString("best_action", "acknowledge")
        val replyNow = parsedJson.optDouble("should_reply_now", 1.0)
        val tension = parsedJson.optDouble("tension_resolved", 0.0)

        return Analysis(
            trueIntent = Choice(intentStr, conf, mapOf(intentStr to conf)),
            dangerLevel = Score(danger, 0.9, 9),
            sheNeeds = Choice(needsStr, conf, mapOf(needsStr to conf)),
            shouldReplyNow = replyNow,
            bestAction = Choice(actionStr, conf, mapOf(actionStr to conf)),
            tensionResolved = tension,
            literalQuestion = 0.0,
            rankedReplies = emptyList(),
            latencyMs = System.currentTimeMillis() - start
        )
    }

    private fun draftAndRankViaChatCompletions(snapshot: ChatSnapshot, relationship: String): List<RankedReply> {
        val convo = snapshot.messages.takeLast(10).joinToString("\n") {
            (if (it.side == "me") "我" else "对方") + "：" + it.text
        }
        val stylePart = if (customStyle.isNotBlank()) "【我指定的回复风格与人设】：$customStyle" else "【回复风格要求】：日常自然微信口语，接地气、真诚，不卑不亢，绝无机器味。"

        val sys = """
            你是顶级即时通讯（微信/QQ）聊天回复副驾。
            请根据上下文关系、对方言下之意，为【我】起草 3 条最合适、最自然的候选回复，并评估各自契合度占比（总和尽量为1.0）。

            $stylePart

            【核心规则】：
            1. 三条策略必须有明确区分度：
               - 第 1 条：【稳妥承接/情绪共情】优先接住情绪或证明在乎，不生硬反驳；
               - 第 2 条：【行动安排/明确表态】给出具体落地方案、时间节点或推进动作；
               - 第 3 条：【简短低姿态/留有余地】简明克制，少说两句避免画蛇添足，或幽默化解。
            2. 口语化极限要求：
               - 必须是真实人类在微信里单手打出的口语短句！
               - 严禁假大空！严禁长篇大论！严禁客服腔、公文风与翻译腔！
               - 每条回复字数控制在 5~35 字之间，利落自然，适合直接发送。

            【输出规范】：必须且仅输出一个纯 JSON 数组，包含且仅包含 3 个对象，不要附带 Markdown 标记（如 ```json ）：
            [
              {"text": "候选回复1", "prob": 0.60},
              {"text": "候选回复2", "prob": 0.25},
              {"text": "候选回复3", "prob": 0.15}
            ]
        """.trimIndent()
        val user = "关系设定：$relationship\n\n最近对话：\n$convo\n\n请输出 3 条候选回复 JSON 数组："

        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", sys))
            .put(JSONObject().put("role", "user").put("content", user))
        val body = JSONObject()
            .put("model", replyModel)
            .put("messages", messages)
            .put("temperature", 0.7)

        val resp = postJson(getChatCompletionsUrl(), body)
        val content = resp.optJSONArray("choices")?.optJSONObject(0)
            ?.optJSONObject("message")?.optString("content") ?: "[]"
        return parseRankedRepliesFromChat(cleanJson(content))
    }

    fun generateCandidates(snapshot: ChatSnapshot, relationship: String): List<String> {
        val dc = draftClient
        if (dc != null) {
            try {
                val c = dc.generateCandidates(snapshot, relationship)
                if (c.isNotEmpty()) {
                    return c
                }
            } catch (e: Exception) {
                Log.w(TAG, "draftClient failed: ${e.message}")
                throw e
            }
        }
        if (isTypeSafe) {
            throw IllegalStateException("未配置文本补全服务商（如 DeepSeek/OpenAI 等）。请在设置中配置文本补全服务商以起草候选回复。")
        }
        val convo = snapshot.messages.takeLast(10).joinToString("\n") {
            (if (it.side == "me") "我" else "对方") + "：" + it.text
        }
        val stylePart = if (customStyle.isNotBlank()) "【我指定的回复风格】：$customStyle" else "自然真实中文口语"
        val sys = "你是中文即时通讯回复副驾。请为我起草 3 条微信回复。$stylePart。" +
            "三条策略要有区别（一条稳妥承接/在乎、一条具体行动安排、一条简短留有余地）。" +
            "每条不超过 35 字，口语、自然、像真人在聊天。直接输出 JSON 数组，不要解释。"
        val user = "关系：$relationship\n\n最近对话：\n$convo\n\n请给出 3 条候选回复。"
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", sys))
            .put(JSONObject().put("role", "user").put("content", user))
        val body = JSONObject()
            .put("model", replyModel)
            .put("messages", messages)
            .put("temperature", 0.8)
        val resp = postJson(getChatCompletionsUrl(), body)
        val content = resp.optJSONArray("choices")?.optJSONObject(0)
            ?.optJSONObject("message")?.optString("content") ?: ""
        return parseThree(cleanJson(content))
    }

    private fun buildContextualCandidates(snapshot: ChatSnapshot, relationship: String): List<String> {
        return listOf(
            "收到，我看了下，刚才手头有点事，稍后马上和你说。",
            "刚才看到，我确认了一下细节，等我忙完这个点就去找你。",
            "在的，别急，我心里有数。"
        )
    }

    private fun parseRankedRepliesFromChat(content: String): List<RankedReply> {
        val start = content.indexOf('[')
        val end = content.lastIndexOf(']')
        if (start >= 0 && end > start) {
            try {
                val arr = JSONArray(content.substring(start, end + 1))
                val list = ArrayList<RankedReply>()
                for (i in 0 until arr.length()) {
                    val item = arr.opt(i)
                    if (item is JSONObject) {
                        val txt = item.optString("text", "").trim()
                        val prob = item.optDouble("prob", if (i == 0) 0.6 else 0.2)
                        if (txt.isNotBlank()) list.add(RankedReply(txt, prob))
                    } else if (item is String && item.isNotBlank()) {
                        list.add(RankedReply(item.trim(), if (i == 0) 0.6 else 0.2))
                    }
                }
                if (list.size >= 3) return list.take(3).sortedByDescending { it.prob }
            } catch (_: Exception) {}
        }
        // Fallback
        val plain = parseThree(content)
        val defaultProbs = listOf(0.60, 0.25, 0.15)
        return plain.mapIndexed { i, s -> RankedReply(s, defaultProbs.getOrElse(i) { 0.1 }) }
    }

    private fun cleanJson(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("```json")) s = s.removePrefix("```json").trim()
        else if (s.startsWith("```")) s = s.removePrefix("```").trim()
        if (s.endsWith("```")) s = s.removeSuffix("```").trim()
        return s.trim()
    }

    private fun parseThree(content: String): List<String> {
        val start = content.indexOf('[')
        val end = content.lastIndexOf(']')
        if (start >= 0 && end > start) {
            try {
                val arr = JSONArray(content.substring(start, end + 1))
                val out = ArrayList<String>()
                for (i in 0 until arr.length()) {
                    val item = arr.opt(i)
                    if (item is JSONObject) {
                        val txt = item.optString("text", item.optString("reply", "")).trim()
                        if (txt.isNotBlank()) out.add(txt)
                    } else if (item is String && item.isNotBlank()) {
                        out.add(item.trim())
                    }
                }
                if (out.size >= 3) return out.take(3)
                if (out.isNotEmpty()) {
                    while (out.size < 3) out.add("（稍等，我看下）")
                    return out
                }
            } catch (_: Exception) { }
        }
        // Fallback: split lines.
        val lines = content.split("\n")
            .map { it.trim().trimStart('-', '*', '1', '2', '3', '.', ' ', '"', '[', ']').trimEnd(',', '"', ']') }
            .filter { it.isNotBlank() && !it.startsWith("{") && !it.startsWith("}") }
        val out = lines.take(3).toMutableList()
        while (out.size < 3) out.add("（稍等，我看下）")
        return out
    }

    private fun parseChoice(o: JSONObject?): Choice? {
        o ?: return null
        val probs = HashMap<String, Double>()
        o.optJSONObject("probabilities")?.let { p ->
            p.keys().forEach { k -> probs[k] = p.optDouble(k) }
        }
        return Choice(o.optString("choice"), o.optDouble("confidence", 0.0), probs)
    }

    private fun parseScore(o: JSONObject?): Score? {
        o ?: return null
        val legend = o.optJSONObject("legend")
        val maxLevel = legend?.keys()?.asSequence()?.mapNotNull { it.toIntOrNull() }?.maxOrNull() ?: 9
        return Score(o.optDouble("score", 0.0), o.optDouble("confidence", 0.0), maxLevel)
    }

    private fun parseRanked(o: JSONObject?, candidates: List<String>): List<RankedReply> {
        val keys = listOf("reply_a", "reply_b", "reply_c")
        val probs = o?.optJSONObject("probabilities")
        val list = candidates.mapIndexed { i, text ->
            RankedReply(text, probs?.optDouble(keys.getOrElse(i) { "" }, 0.0) ?: 0.0)
        }
        return list.sortedByDescending { it.prob }
    }

    /** POST JSON with one retry chain for 429/529 (exponential backoff). */
    private fun postJson(urlStr: String, body: JSONObject): JSONObject {
        var attempt = 0
        var lastErr: Exception? = null
        while (attempt < 3) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15000
                    readTimeout = 25000
                    doOutput = true
                    setRequestProperty("Authorization", "Bearer $key")
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36")
                    if (isOpenRouter) {
                        setRequestProperty("HTTP-Referer", "https://jev-assistant.local")
                        setRequestProperty("X-Title", "Jev Assistant")
                    }
                }
                val bytes = body.toString().toByteArray(Charsets.UTF_8)
                conn.outputStream.use { os: OutputStream -> os.write(bytes) }
                val code = conn.responseCode
                if (code == 429 || code == 529) {
                    attempt++
                    Thread.sleep(500L * (1L shl attempt))
                    continue
                }
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
                if (code !in 200..299) throw RuntimeException("HTTP $code: ${text.take(160)}")
                return JSONObject(text)
            } catch (e: Exception) {
                lastErr = e
                if (e.message?.contains("HTTP 4") == true) throw e // client error: no retry
                attempt++
                if (attempt < 3) Thread.sleep(500L * (1L shl attempt))
            } finally {
                conn?.disconnect()
            }
        }
        throw lastErr ?: RuntimeException("request failed")
    }

    private fun readableError(e: Exception): String {
        val m = e.message ?: e.javaClass.simpleName
        return when {
            m.contains("HTTP 401") -> if (isTypeSafe) "TypeSafe API Key 错误或失效（401）" else "密钥无效或未授权（401）"
            m.contains("HTTP 402") -> "TypeSafe 官方额度已用尽（402），请更换 Key"
            m.contains("HTTP 404") -> "接口地址不存在（404）"
            m.contains("HTTP 422") -> "请求格式错误（422）"
            m.contains("HTTP 429") -> "官方限速或请求频繁（429），请稍后重试"
            m.contains("HTTP 529") -> "TypeSafe 官方服务过载（529），请稍后重试"
            m.contains("HTTP 4") -> "请求被拒：$m"
            m.contains("timed out") || m.contains("timeout") -> "网络超时，请检查连接"
            m.contains("Unable to resolve host") || m.contains("Failed to connect") -> "无法连接网络或域名无效"
            else -> "分析失败：$m"
        }
    }

    companion object {
        private const val TAG = "JEVASSIST"
        private const val USER_AGENT = "Mozilla/5.0 (Android; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"

        /** TypeSafe 官方 10 秒快速自测（返回 HTTP 200 即代表可用） */
        fun pingTypeSafe(baseUrl: String, key: String): String {
            val url = "${baseUrl.trim().trimEnd('/')}/v1/systemone"
            val body = JSONObject()
                .put("state", "ping")
                .put("model", "jev-latest")
                .put("questions", JSONObject().put("ok", JSONObject()
                    .put("type", "noul")
                    .put("instructions", "Is this a test?")))
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8000
                readTimeout = 10000
                doOutput = true
                setRequestProperty("Authorization", "Bearer ${key.trim()}")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", USER_AGENT)
            }
            val start = System.currentTimeMillis()
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            conn.disconnect()
            val latency = System.currentTimeMillis() - start
            if (code == 200) {
                return "自测成功 (HTTP 200, TypeSafe Jev 响应正常, 延时 ${latency}ms)"
            }
            val errDesc = when (code) {
                401 -> "API Key 错误或未授权 (401)"
                402 -> "官方额度已用尽 (402)"
                422 -> "请求体格式错误 (422)"
                429 -> "官方接口限速 (429)"
                529 -> "官方服务过载 (529)"
                else -> "HTTP $code: ${text.take(80)}"
            }
            throw RuntimeException(errDesc)
        }

        /** 测试通用大模型文本补全连通性与时延 */
        fun testChatCompletion(baseUrl: String, key: String, model: String): String {
            val clean = baseUrl.trim().trimEnd('/')
            val endpoint = when {
                clean.endsWith("/chat/completions") -> clean
                clean.endsWith("/v1") || clean.endsWith("/v4") -> "$clean/chat/completions"
                else -> "$clean/v1/chat/completions"
            }
            val body = JSONObject()
                .put("model", model.ifBlank { "deepseek-chat" })
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "请用一句话说你好")))
                .put("max_tokens", 30)
                .put("temperature", 0.7)

            val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8000
                readTimeout = 12000
                doOutput = true
                setRequestProperty("Authorization", "Bearer ${key.trim()}")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", USER_AGENT)
            }
            val start = System.currentTimeMillis()
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            conn.disconnect()
            val latency = System.currentTimeMillis() - start
            if (code in 200..299) {
                val reply = try {
                    val jsonObj = JSONObject(text)
                    jsonObj.optJSONArray("choices")?.optJSONObject(0)
                        ?.optJSONObject("message")?.optString("content")?.trim() ?: "连通正常"
                } catch (e: Exception) { "连通正常" }
                return "补全成功 (HTTP $code, 延时 ${latency}ms, 样例: \"${reply.take(25)}\")"
            }
            throw RuntimeException("HTTP $code: ${text.take(80)}")
        }
    }
}

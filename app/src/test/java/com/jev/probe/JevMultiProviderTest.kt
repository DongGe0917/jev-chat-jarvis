package com.jev.probe

import com.jev.probe.core.ApiProviders
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Msg
import com.jev.probe.jev.JevClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class JevMultiProviderTest {

    private var serverSocket: ServerSocket? = null
    private var serverPort: Int = 0
    private val running = AtomicBoolean(true)
    @Volatile private var lastReceivedAuth: String? = null
    @Volatile private var lastRequestBody: String? = null

    @Before
    fun setUp() {
        running.set(true)
        serverSocket = ServerSocket(0)
        serverPort = serverSocket!!.localPort

        thread(isDaemon = true, name = "MockServer-Accept") {
            while (running.get() && !serverSocket!!.isClosed) {
                try {
                    val socket: Socket = serverSocket!!.accept()
                    thread(isDaemon = true, name = "MockServer-Worker") {
                        handleClient(socket)
                    }
                } catch (_: Exception) {
                    break
                }
            }
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 4000
            val rawIn: InputStream = socket.getInputStream()
            
            // Read headers byte by byte until \r\n\r\n
            val headerBytes = ArrayList<Byte>()
            var b: Int
            while (true) {
                b = rawIn.read()
                if (b == -1) break
                headerBytes.add(b.toByte())
                val s = headerBytes.size
                if (s >= 4 &&
                    headerBytes[s - 4] == '\r'.code.toByte() &&
                    headerBytes[s - 3] == '\n'.code.toByte() &&
                    headerBytes[s - 2] == '\r'.code.toByte() &&
                    headerBytes[s - 1] == '\n'.code.toByte()
                ) {
                    break
                }
            }

            val headerStr = String(headerBytes.toByteArray(), Charsets.UTF_8)
            var contentLength = 0
            for (line in headerStr.lines()) {
                if (line.startsWith("Authorization:", ignoreCase = true)) {
                    lastReceivedAuth = line.substring("Authorization:".length).trim()
                } else if (line.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = line.substring("Content-Length:".length).trim().toIntOrNull() ?: 0
                }
            }

            val bodyBuf = ByteArray(contentLength)
            var readTotal = 0
            while (readTotal < contentLength) {
                val r = rawIn.read(bodyBuf, readTotal, contentLength - readTotal)
                if (r < 0) break
                readTotal += r
            }
            val body = String(bodyBuf, Charsets.UTF_8)
            lastRequestBody = body

            val output = socket.getOutputStream()
            if (headerStr.contains("error_401")) {
                val errorJson = """{"error": {"message": "Invalid API Key"}}"""
                val respBytes = errorJson.toByteArray(Charsets.UTF_8)
                val respHeader = "HTTP/1.1 401 Unauthorized\r\nContent-Type: application/json\r\nConnection: close\r\nContent-Length: ${respBytes.size}\r\n\r\n"
                output.write(respHeader.toByteArray(Charsets.UTF_8))
                output.write(respBytes)
                output.flush()
                return
            }

            if (headerStr.contains("/v1/systemone")) {
                val systemOneResponse = if (body.contains("best_reply")) {
                    """
                    {
                      "model": "jev-1.13.0",
                      "answers": {
                        "best_reply": {
                          "type": "choice",
                          "choice": "reply_a",
                          "confidence": 0.88,
                          "probabilities": {
                            "reply_a": 0.70,
                            "reply_b": 0.20,
                            "reply_c": 0.10
                          }
                        }
                      },
                      "usage": {"input_tokens": 180, "output_tokens": 20}
                    }
                    """.trimIndent()
                } else if (body.contains("ping")) {
                    """
                    {
                      "model": "jev-1.13.0",
                      "answers": {
                        "ok": {"type": "noul", "noul": 1.0}
                      },
                      "usage": {"input_tokens": 20, "output_tokens": 5}
                    }
                    """.trimIndent()
                } else {
                    """
                    {
                      "model": "jev-1.13.0",
                      "answers": {
                        "literal_question": {"type": "noul", "noul": 0.05},
                        "true_intent": {"type": "choice", "choice": "confirm_you_care", "confidence": 0.95, "probabilities": {"confirm_you_care": 0.95}},
                        "danger_level": {"type": "score", "score": 5.0, "confidence": 0.90, "legend": {"0": "0", "9": "9"}},
                        "she_needs": {"type": "choice", "choice": "care", "confidence": 0.92, "probabilities": {"care": 0.92}},
                        "should_reply_now": {"type": "noul", "noul": 0.0},
                        "best_action": {"type": "choice", "choice": "check_history", "confidence": 0.89, "probabilities": {"check_history": 0.89}},
                        "tension_resolved": {"type": "noul", "noul": 0.0}
                      },
                      "usage": {"input_tokens": 296, "output_tokens": 20}
                    }
                    """.trimIndent()
                }
                val respBytes = systemOneResponse.toByteArray(Charsets.UTF_8)
                val respHeader = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nConnection: close\r\nContent-Length: ${respBytes.size}\r\n\r\n"
                output.write(respHeader.toByteArray(Charsets.UTF_8))
                output.write(respBytes)
                output.flush()
                return
            }

            val isJudgment = body.contains("研判核心准则") || body.contains("心理研判")
            val innerContent = if (isJudgment) {
                """
                ```json
                {
                  "true_intent": "confirm_you_care",
                  "confidence": 0.92,
                  "danger_level": 5,
                  "she_needs": "care",
                  "best_action": "check_history",
                  "should_reply_now": 0.0,
                  "tension_resolved": 0.0
                }
                ```
                """.trimIndent()
            } else {
                """
                ```json
                [
                  {"text": "记得，我翻一下聊天记录复述给你听", "prob": 0.65},
                  {"text": "今天有点忙，我马上去确认之前说的事", "prob": 0.25},
                  {"text": "别生气，我都在呢", "prob": 0.10}
                ]
                ```
                """.trimIndent()
            }

            val wrappedResponse = """
            {
              "id": "chatcmpl-test-123",
              "choices": [
                {
                  "index": 0,
                  "message": {
                    "role": "assistant",
                    "content": ${org.json.JSONObject.quote(innerContent)}
                  }
                }
              ]
            }
            """.trimIndent()

            val respBytes = wrappedResponse.toByteArray(Charsets.UTF_8)
            val respHeader = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nConnection: close\r\nContent-Length: ${respBytes.size}\r\n\r\n"
            output.write(respHeader.toByteArray(Charsets.UTF_8))
            output.write(respBytes)
            output.flush()
        } catch (_: Exception) {
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    @After
    fun tearDown() {
        running.set(false)
        try { serverSocket?.close() } catch (_: Exception) {}
    }

    @Test
    fun testApiProvidersCatalog() {
        val providers = ApiProviders.ALL
        assertTrue("Providers should not be empty", providers.size >= 7)

        val deepseek = ApiProviders.find("DEEPSEEK")
        assertEquals("DEEPSEEK", deepseek.id)
        assertEquals("https://api.deepseek.com/v1", deepseek.defaultBaseUrl)
        assertEquals("deepseek-chat", deepseek.defaultModel)

        val openai = ApiProviders.find("OPENAI")
        assertEquals("OPENAI", openai.id)
        assertEquals("https://api.openai.com/v1", openai.defaultBaseUrl)

        val silicon = ApiProviders.find("siliconflow")
        assertEquals("SILICONFLOW", silicon.id)

        // Case insensitivity
        assertEquals("DEEPSEEK", ApiProviders.find("deepseek").id)
    }

    @Test
    fun testJevClientJudgmentViaMockServer() {
        val client = JevClient(
            provider = "DEEPSEEK",
            baseUrl = "http://127.0.0.1:$serverPort/v1",
            key = "sk-test-mock-key-123",
            replyModel = "deepseek-chat"
        )

        val snapshot = ChatSnapshot(
            title = "测试会话",
            messages = listOf(
                Msg("other", "你今天是不是又忘了我跟你说过什么？"),
                Msg("me", "记得，你先别提示我，让我自己说。"),
                Msg("other", "那你最好是。")
            )
        )

        val analysis = client.judge(snapshot, "对方是我的伴侣")

        // 1. Verify Authorization Header
        assertEquals("Bearer sk-test-mock-key-123", lastReceivedAuth)

        // 2. Verify Parsed Analysis Results
        assertNotNull(analysis)
        assertEquals(null, analysis.error)
        assertEquals("confirm_you_care", analysis.trueIntent?.choice)
        assertEquals(0.92, analysis.trueIntent?.confidence ?: 0.0, 0.01)
        assertEquals(5.0, analysis.dangerLevel?.score ?: 0.0, 0.01)
        assertEquals("care", analysis.sheNeeds?.choice)
        assertEquals("check_history", analysis.bestAction?.choice)
        assertEquals(0.0, analysis.shouldReplyNow ?: 1.0, 0.01)
        assertEquals(0.0, analysis.tensionResolved ?: 1.0, 0.01)
    }

    @Test
    fun testJevClientDraftAndRankViaMockServer() {
        val client = JevClient(
            provider = "DEEPSEEK",
            baseUrl = "http://127.0.0.1:$serverPort/v1",
            key = "sk-test-mock-key-456",
            replyModel = "deepseek-chat",
            customStyle = "幽默机智"
        )

        val snapshot = ChatSnapshot(
            title = "测试会话",
            messages = listOf(
                Msg("other", "在吗？"),
                Msg("me", "在"),
                Msg("other", "你人呢？")
            )
        )

        val replies = client.draftAndRank(snapshot, "对方是我的伴侣")

        assertNotNull(replies)
        assertEquals(3, replies.size)
        assertEquals("记得，我翻一下聊天记录复述给你听", replies[0].text)
        assertEquals(0.65, replies[0].prob, 0.01)
        assertEquals(0.25, replies[1].prob, 0.01)
        assertEquals(0.10, replies[2].prob, 0.01)

        // Verify customStyle was included in the request body
        assertTrue(lastRequestBody?.contains("幽默机智") == true)
    }

    @Test
    fun testTypeSafeJevJudgmentViaMockServer() {
        val draftClient = JevClient(
            provider = "DEEPSEEK",
            baseUrl = "http://127.0.0.1:$serverPort",
            key = "sk-mock-deepseek",
            replyModel = "deepseek-chat"
        )

        val client = JevClient(
            provider = "TYPESAFE",
            baseUrl = "http://127.0.0.1:$serverPort",
            key = "apikey_mock_typesafe_123456",
            replyModel = "jev-latest"
        ).apply {
            this.draftClient = draftClient
        }

        val snapshot = ChatSnapshot(
            title = "测试会话",
            messages = listOf(
                Msg("other", "你今天是不是又忘了我跟你说过什么？"),
                Msg("me", "记得，你先别提示我，让我自己说。"),
                Msg("other", "那你最好是。")
            )
        )

        val analysis = client.judge(snapshot, "对方是我的伴侣")

        assertEquals("Bearer apikey_mock_typesafe_123456", lastReceivedAuth)
        assertNotNull(analysis)
        assertEquals(null, analysis.error)
        assertEquals("confirm_you_care", analysis.trueIntent?.choice)
        assertEquals(0.95, analysis.trueIntent?.confidence ?: 0.0, 0.01)
        assertEquals(5.0, analysis.dangerLevel?.score ?: 0.0, 0.01)
        assertEquals("care", analysis.sheNeeds?.choice)
        assertEquals("check_history", analysis.bestAction?.choice)
        assertEquals(0.0, analysis.shouldReplyNow ?: 1.0, 0.01)

        val replies = client.draftAndRank(snapshot, "对方是我的伴侣")
        assertNotNull(replies)
        assertEquals(3, replies.size)
        assertEquals("记得，我翻一下聊天记录复述给你听", replies[0].text)
        assertEquals(0.70, replies[0].prob, 0.01)
    }

    @Test
    fun testTypeSafePingMethod() {
        val pingRes = JevClient.pingTypeSafe("http://127.0.0.1:$serverPort", "apikey_mock_typesafe_ping")
        assertTrue("Ping should succeed with HTTP 200", pingRes.contains("200"))
    }

    @Test
    fun testChatCompletionMethod() {
        val compRes = JevClient.testChatCompletion("http://127.0.0.1:$serverPort", "sk-mock-key", "deepseek-chat")
        assertTrue("Chat completion test should succeed", compRes.contains("200"))
    }

    @Test
    fun testJevClientHttpErrorHandling() {
        val client = JevClient(
            provider = "CUSTOM",
            baseUrl = "http://127.0.0.1:$serverPort/v1/error_401",
            key = "invalid-key",
            replyModel = "test-model"
        )

        val snapshot = ChatSnapshot("测试", listOf(Msg("other", "hello")))
        val analysis = client.judge(snapshot, "伴侣")

        assertNotNull(analysis.error)
        assertTrue("Error should mention 401: ${analysis.error}",
            analysis.error?.contains("401") == true || analysis.error?.contains("密钥无效") == true)
    }
}

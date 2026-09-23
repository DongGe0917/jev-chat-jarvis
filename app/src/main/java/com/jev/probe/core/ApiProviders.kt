package com.jev.probe.core

data class ApiProvider(
    val id: String,
    val name: String,
    val defaultBaseUrl: String,
    val defaultModel: String,
    val recommendedModels: List<String>,
    val isOpenRouter: Boolean = false,
    val isTypeSafe: Boolean = false,
    val hintKey: String = "sk-..."
)

object ApiProviders {
    val TYPESAFE = ApiProvider(
        id = "TYPESAFE",
        name = "TypeSafe Jev (官方直连)",
        defaultBaseUrl = "https://api.typesafe.ai",
        defaultModel = "jev-latest",
        recommendedModels = listOf("jev-latest", "jev-1.13.0"),
        isTypeSafe = true,
        hintKey = "apikey_..."
    )

    val DEEPSEEK = ApiProvider(
        id = "DEEPSEEK",
        name = "DeepSeek (深度求索)",
        defaultBaseUrl = "https://api.deepseek.com/v1",
        defaultModel = "deepseek-chat",
        recommendedModels = listOf("deepseek-chat", "deepseek-reasoner"),
        hintKey = "sk-..."
    )

    val OPENAI = ApiProvider(
        id = "OPENAI",
        name = "OpenAI",
        defaultBaseUrl = "https://api.openai.com/v1",
        defaultModel = "gpt-4o-mini",
        recommendedModels = listOf("gpt-4o-mini", "gpt-4o", "gpt-3.5-turbo"),
        hintKey = "sk-..."
    )

    val OPENROUTER = ApiProvider(
        id = "OPENROUTER",
        name = "OpenRouter",
        defaultBaseUrl = "https://openrouter.ai/api/v1",
        defaultModel = "deepseek/deepseek-chat-v3.1",
        recommendedModels = listOf(
            "deepseek/deepseek-chat-v3.1",
            "openai/gpt-4o-mini",
            "google/gemini-2.0-flash-001",
            "anthropic/claude-3.5-haiku"
        ),
        isOpenRouter = true,
        hintKey = "sk-or-v1-..."
    )

    val SILICONFLOW = ApiProvider(
        id = "SILICONFLOW",
        name = "硅基流动 (SiliconFlow)",
        defaultBaseUrl = "https://api.siliconflow.cn/v1",
        defaultModel = "deepseek-ai/DeepSeek-V3",
        recommendedModels = listOf(
            "deepseek-ai/DeepSeek-V3",
            "deepseek-ai/DeepSeek-R1",
            "Qwen/Qwen2.5-72B-Instruct"
        ),
        hintKey = "sk-..."
    )

    val MOONSHOT = ApiProvider(
        id = "MOONSHOT",
        name = "月之暗面 (Moonshot / Kimi)",
        defaultBaseUrl = "https://api.moonshot.cn/v1",
        defaultModel = "moonshot-v1-8k",
        recommendedModels = listOf("moonshot-v1-8k", "moonshot-v1-32k"),
        hintKey = "sk-..."
    )

    val ZHIPU = ApiProvider(
        id = "ZHIPU",
        name = "智谱 AI (GLM)",
        defaultBaseUrl = "https://open.bigmodel.cn/api/paas/v4",
        defaultModel = "glm-4-flash",
        recommendedModels = listOf("glm-4-flash", "glm-4-plus", "glm-4"),
        hintKey = "api_key..."
    )

    val CUSTOM = ApiProvider(
        id = "CUSTOM",
        name = "自定义 API (OneAPI / Ollama 等)",
        defaultBaseUrl = "https://api.openai.com/v1",
        defaultModel = "gpt-4o-mini",
        recommendedModels = listOf("gpt-4o-mini", "deepseek-chat", "qwen2.5"),
        hintKey = "API Key / 令牌"
    )

    val ALL = listOf(TYPESAFE, DEEPSEEK, OPENAI, OPENROUTER, SILICONFLOW, MOONSHOT, ZHIPU, CUSTOM)
    val DRAFT_PROVIDERS = listOf(DEEPSEEK, OPENAI, OPENROUTER, SILICONFLOW, MOONSHOT, ZHIPU, CUSTOM)

    fun find(id: String?): ApiProvider {
        return ALL.find { it.id.equals(id, ignoreCase = true) } ?: DEEPSEEK
    }
}

package com.jev.probe.core

import android.content.Context

/**
 * App-private config store. Holds multi-platform API settings (Cherry Studio style),
 * model choices, the relationship description used in state, and conversation whitelist.
 *
 * Key handling: stored in app-private SharedPreferences (not world-readable,
 * never logged, never in code/git).
 */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("jev_assistant", Context.MODE_PRIVATE)

    /** Currently active provider ID (e.g. DEEPSEEK, OPENAI, OPENROUTER, etc.) */
    var apiProvider: String
        get() = sp.getString(K_PROVIDER, ApiProviders.DEEPSEEK.id) ?: ApiProviders.DEEPSEEK.id
        set(v) = sp.edit().putString(K_PROVIDER, v.trim()).apply()

    val currentProvider: ApiProvider
        get() = ApiProviders.find(apiProvider)

    fun getKey(providerId: String): String {
        val saved = sp.getString("key_${providerId.uppercase()}", "") ?: ""
        if (saved.isNotBlank()) return saved
        // Legacy fallback for OPENROUTER
        if (providerId.equals(ApiProviders.OPENROUTER.id, ignoreCase = true)) {
            return sp.getString(K_LEGACY_KEY, "") ?: ""
        }
        return ""
    }

    fun setKey(providerId: String, key: String) {
        val pid = providerId.uppercase()
        sp.edit().putString("key_$pid", key.trim()).apply()
        if (pid == ApiProviders.OPENROUTER.id) {
            sp.edit().putString(K_LEGACY_KEY, key.trim()).apply()
        }
    }

    fun getBaseUrl(providerId: String): String {
        val def = ApiProviders.find(providerId).defaultBaseUrl
        val url = sp.getString("url_${providerId.uppercase()}", "") ?: ""
        return if (url.isBlank()) def else url
    }

    fun setBaseUrl(providerId: String, url: String) {
        sp.edit().putString("url_${providerId.uppercase()}", url.trim()).apply()
    }

    fun getModel(providerId: String): String {
        val def = ApiProviders.find(providerId).defaultModel
        val m = sp.getString("model_${providerId.uppercase()}", "") ?: ""
        if (m.isNotBlank()) return m
        if (providerId.equals(ApiProviders.OPENROUTER.id, ignoreCase = true)) {
            val legacyModel = sp.getString(K_LEGACY_REPLY_MODEL, "") ?: ""
            if (legacyModel.isNotBlank()) return legacyModel
        }
        return def
    }

    fun setModel(providerId: String, model: String) {
        val pid = providerId.uppercase()
        sp.edit().putString("model_$pid", model.trim()).apply()
        if (pid == ApiProviders.OPENROUTER.id) {
            sp.edit().putString(K_LEGACY_REPLY_MODEL, model.trim()).apply()
        }
    }

    /** Active provider's key */
    var apiKey: String
        get() = getKey(apiProvider)
        set(v) = setKey(apiProvider, v)

    /** Active provider's Base URL */
    var apiBaseUrl: String
        get() = getBaseUrl(apiProvider)
        set(v) = setBaseUrl(apiProvider, v)

    /** Active provider's selected model */
    var apiModel: String
        get() = getModel(apiProvider)
        set(v) = setModel(apiProvider, v)

    /** Legacy compatibility for openRouterKey */
    var openRouterKey: String
        get() = getKey(ApiProviders.OPENROUTER.id)
        set(v) = setKey(ApiProviders.OPENROUTER.id, v)

    /** Legacy compatibility for replyModel */
    var replyModel: String
        get() = apiModel
        set(v) = setModel(apiProvider, v)

    /** Free-text describing who the other person is; goes into state. */
    var relationship: String
        get() = sp.getString(K_REL, DEFAULT_REL) ?: DEFAULT_REL
        set(v) = sp.edit().putString(K_REL, v).apply()

    /** Master on/off for showing the overlay + running analysis. */
    var enabled: Boolean
        get() = sp.getBoolean(K_ENABLED, true)
        set(v) = sp.edit().putBoolean(K_ENABLED, v).apply()

    /**
     * Conversation whitelist: titles the assistant is allowed to act on. Empty
     * set means "all conversations". Stored as a plain string set.
     */
    var whitelist: Set<String>
        get() = sp.getStringSet(K_WHITELIST, emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet(K_WHITELIST, v).apply()

    /** Overlay panel opacity, 60..100 (%). Lower lets the chat show through. */
    var overlayOpacity: Int
        get() = sp.getInt(K_OPACITY, 92).coerceIn(60, 100)
        set(v) = sp.edit().putInt(K_OPACITY, v.coerceIn(60, 100)).apply()

    /** Remembered vertical position of the bubble (px); -1 = default. */
    var bubbleY: Int
        get() = sp.getInt(K_BUBBLE_Y, -1)
        set(v) = sp.edit().putInt(K_BUBBLE_Y, v).apply()

    /** Remembered horizontal position of the bubble (px); -1 = default. */
    var bubbleX: Int
        get() = sp.getInt(K_BUBBLE_X, -1)
        set(v) = sp.edit().putInt(K_BUBBLE_X, v).apply()

    /** Auto-analyze on every incoming message; if false, user taps to analyze. */
    var autoAnalyze: Boolean
        get() = sp.getBoolean(K_AUTO, true)
        set(v) = sp.edit().putBoolean(K_AUTO, v).apply()

    /** Custom reply style / persona instructions for candidate drafts */
    var customStyle: String
        get() = sp.getString(K_CUSTOM_STYLE, DEFAULT_CUSTOM_STYLE) ?: DEFAULT_CUSTOM_STYLE
        set(v) = sp.edit().putString(K_CUSTOM_STYLE, v.trim()).apply()

    fun isAllowed(title: String?): Boolean {
        val wl = whitelist
        if (wl.isEmpty()) return true
        if (title == null) return false
        return wl.any { title.contains(it) }
    }

    fun hasKey(): Boolean = apiKey.isNotBlank()

    companion object {
        private const val K_PROVIDER = "api_provider"
        private const val K_LEGACY_KEY = "openrouter_key"
        private const val K_LEGACY_REPLY_MODEL = "reply_model"
        private const val K_REL = "relationship"
        private const val K_CUSTOM_STYLE = "custom_style"
        private const val K_ENABLED = "enabled"
        private const val K_WHITELIST = "whitelist"
        private const val K_OPACITY = "overlay_opacity"
        private const val K_BUBBLE_Y = "bubble_y"
        private const val K_BUBBLE_X = "bubble_x"
        private const val K_AUTO = "auto_analyze"

        const val DEFAULT_REL = "对方是我的伴侣；from=me 的是我发的，from=other 的是对方发的"
        const val DEFAULT_CUSTOM_STYLE = "自然口语、接地气、真诚真切，严禁假大空套话"
    }
}

package com.jev.probe

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.jev.probe.core.ApiProvider
import com.jev.probe.core.ApiProviders
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Msg
import com.jev.probe.core.Prefs
import com.jev.probe.jev.JevClient
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private val accent = Color.parseColor("#3A7AFE")
    private val ink = Color.parseColor("#111827")
    private val sub = Color.parseColor("#6B7280")
    private val green = Color.parseColor("#16A34A")

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).roundToInt()

    private var currentSelectedProvider: ApiProvider = ApiProviders.DEEPSEEK

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        currentSelectedProvider = prefs.currentProvider
        window.decorView.setBackgroundColor(Color.parseColor("#F2F3F5"))

        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(28))
        }
        scroll.addView(root)

        val appVersionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.5"
        } catch (_: Exception) { "1.5" }
        val appVersionCode = try {
            packageManager.getPackageInfo(packageName, 0).longVersionCode
        } catch (_: Exception) { 5L }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(0, 0, 0, dp(4))
        }
        headerRow.addView(text("设置", 24f, ink, bold = true))
        headerRow.addView(text("  v$appVersionName", 14f, accent, bold = true).apply {
            setPadding(dp(6), 0, 0, dp(4))
        })
        root.addView(headerRow)

        // --- 1. AI 文本补全与候选起草平台 ---
        root.addView(section("AI 文本补全与候选起草服务商"))
        val card1 = card()

        // 1. 服务商选择
        card1.addView(label("选择模型平台 (DeepSeek / OpenAI 等)"))
        val providers = ApiProviders.DRAFT_PROVIDERS
        val providerNames = providers.map { it.name }
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item, providerNames)
            background = round(dp(8), Color.parseColor("#F3F4F6"))
            setPadding(dp(10), dp(10), dp(10), dp(10))
            val initIdx = providers.indexOfFirst { it.id == currentSelectedProvider.id }.let { if (it >= 0) it else 0 }
            setSelection(initIdx)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2) }
        }
        card1.addView(spinner)

        // 2. Base URL
        val urlHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(4))
        }
        urlHeaderRow.addView(text("API 接口地址 (Base URL)", 13f, ink, bold = true).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val resetUrlBtn = text("恢复默认", 12f, accent, bold = false).apply {
            setPadding(dp(6), dp(2), dp(6), dp(2))
        }
        urlHeaderRow.addView(resetUrlBtn)
        card1.addView(urlHeaderRow)

        val urlEdit = edit(prefs.getBaseUrl(currentSelectedProvider.id), currentSelectedProvider.defaultBaseUrl)
        card1.addView(urlEdit)

        resetUrlBtn.setOnClickListener {
            urlEdit.setText(currentSelectedProvider.defaultBaseUrl)
            Toast.makeText(this, "已重置为平台默认地址", Toast.LENGTH_SHORT).show()
        }

        // 3. API Key
        val keyLabel = label("API 密钥 (API Key)")
        card1.addView(keyLabel)
        val keyEdit = edit(prefs.getKey(currentSelectedProvider.id), currentSelectedProvider.hintKey, password = true)
        card1.addView(keyEdit)

        // 4. 模型名称
        card1.addView(label("候选回复起草模型"))
        val modelEdit = edit(prefs.getModel(currentSelectedProvider.id), currentSelectedProvider.defaultModel)
        card1.addView(modelEdit)

        // 5. 快捷推荐模型芯片
        val chipsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4)
            }
        }
        val chipsBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        chipsScroll.addView(chipsBox)
        card1.addView(chipsScroll)

        fun refreshChips(provider: ApiProvider) {
            chipsBox.removeAllViews()
            chipsBox.addView(text("推荐: ", 11f, sub).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, dp(4), 0)
            })
            for (m in provider.recommendedModels) {
                chipsBox.addView(chipBtn(m) {
                    modelEdit.setText(m)
                })
            }
        }
        refreshChips(currentSelectedProvider)

        // 服务商下拉切换监听
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val newProvider = providers[position]
                if (newProvider.id != currentSelectedProvider.id) {
                    prefs.setKey(currentSelectedProvider.id, keyEdit.text.toString())
                    prefs.setBaseUrl(currentSelectedProvider.id, urlEdit.text.toString())
                    prefs.setModel(currentSelectedProvider.id, modelEdit.text.toString())

                    currentSelectedProvider = newProvider
                    urlEdit.setText(prefs.getBaseUrl(newProvider.id))
                    urlEdit.hint = newProvider.defaultBaseUrl
                    keyEdit.setText(prefs.getKey(newProvider.id))
                    keyEdit.hint = newProvider.hintKey
                    modelEdit.setText(prefs.getModel(newProvider.id))
                    modelEdit.hint = newProvider.defaultModel
                    refreshChips(newProvider)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val draftTestResult = text("未测试", 12f, sub).apply { setPadding(0, dp(6), 0, dp(4)) }
        val testDraftBtn = secondaryBtn("测试文本补全连接") {
            val key = keyEdit.text.toString().trim()
            val url = urlEdit.text.toString().trim().ifBlank { currentSelectedProvider.defaultBaseUrl }
            val model = modelEdit.text.toString().trim().ifBlank { currentSelectedProvider.defaultModel }
            if (key.isBlank()) {
                draftTestResult.text = "❌ 请先填入当前平台的 API 密钥"
                draftTestResult.setTextColor(Color.RED)
                return@secondaryBtn
            }
            draftTestResult.text = "正在连接 ${currentSelectedProvider.name} 测试…"
            draftTestResult.setTextColor(sub)
            worker.execute {
                try {
                    val msg = JevClient.testChatCompletion(url, key, model)
                    main.post {
                        draftTestResult.text = "✅ $msg"
                        draftTestResult.setTextColor(green)
                    }
                } catch (e: Exception) {
                    main.post {
                        draftTestResult.text = "❌ 失败：${e.message}"
                        draftTestResult.setTextColor(Color.RED)
                    }
                }
            }
        }
        card1.addView(testDraftBtn)
        card1.addView(draftTestResult)
        root.addView(card1)

        // --- 2. TypeSafe Jev 官方心理研判引擎 ---
        root.addView(section("TypeSafe Jev 官方心理研判引擎 (可选 / 推荐)"))
        val cardJev = card()

        val jevToggle = toggleRow("启用 TypeSafe Jev 官方研判引擎", prefs.jevEnabled)
        cardJev.addView(jevToggle)

        cardJev.addView(text("开启后，大模型起草的 3 条回复将由 TypeSafe 官方 System One 模型进行 7 维人际心理学研判与高置信度排序择优；关闭则使用通用大模型内置研判。", 12f, sub).apply {
            setPadding(0, dp(4), 0, dp(8))
        })

        cardJev.addView(label("TypeSafe API 密钥 (apikey_...)"))
        val jevKeyEdit = edit(prefs.jevKey, "apikey_...", password = true)
        cardJev.addView(jevKeyEdit)

        val jevTestResult = text("未测试", 12f, sub).apply { setPadding(0, dp(6), 0, dp(4)) }
        val testJevBtn = secondaryBtn("测试 Jev 研判连接") {
            val key = jevKeyEdit.text.toString().trim().ifBlank { prefs.jevKey }
            val url = prefs.jevBaseUrl
            if (key.isBlank()) {
                jevTestResult.text = "❌ 请先填入 TypeSafe 官方 API 密钥 (apikey_...)"
                jevTestResult.setTextColor(Color.RED)
                return@secondaryBtn
            }
            jevTestResult.text = "正在连接 TypeSafe 官方 System One 接口…"
            jevTestResult.setTextColor(sub)
            worker.execute {
                try {
                    val msg = JevClient.pingTypeSafe(url, key)
                    main.post {
                        jevTestResult.text = "✅ $msg"
                        jevTestResult.setTextColor(green)
                    }
                } catch (e: Exception) {
                    main.post {
                        jevTestResult.text = "❌ 失败：${e.message}"
                        jevTestResult.setTextColor(Color.RED)
                    }
                }
            }
        }
        cardJev.addView(testJevBtn)
        cardJev.addView(jevTestResult)
        root.addView(cardJev)

        // --- 3. 研判与人设 ---
        root.addView(section("研判与人设定制"))
        val card2 = card()
        card2.addView(label("关系设定（告诉模型对方是谁，怎么区分）"))
        val relEdit = edit(prefs.relationship, Prefs.DEFAULT_REL)
        card2.addView(relEdit)

        card2.addView(label("回复风格与人设定制（打造专属你的聊天副驾）"))
        val styleEdit = edit(prefs.customStyle, Prefs.DEFAULT_CUSTOM_STYLE)
        card2.addView(styleEdit)

        // 快捷人设风格标签
        val styleChipsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4)
            }
        }
        val styleChipsBox = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        styleChipsScroll.addView(styleChipsBox)
        card2.addView(styleChipsScroll)

        val presetStyles = listOf(
            "日常自然" to "自然口语、接地气、真诚真切，严禁假大空套话",
            "幽默机智" to "幽默接地气，说话轻松带点机智调侃，化解尴尬",
            "温柔暖心" to "温柔体贴，情绪感知细腻，善于提供情绪价值与暖心安抚",
            "职场严谨" to "对职场/领导客户，语气专业严谨、体现担当与敬语，利落干练",
            "简短克制" to "极简克制，字字珠玑，绝不多说一句废话"
        )
        for ((name, prompt) in presetStyles) {
            styleChipsBox.addView(chipBtn(name) {
                styleEdit.setText(prompt)
            })
        }

        card2.addView(label("会话白名单（每行一个关键词，空=所有会话）"))
        val wlEdit = edit(prefs.whitelist.joinToString("\n"), "留空则对所有会话生效").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE; minLines = 2
        }
        card2.addView(wlEdit)
        val autoRow = toggleRow("对方发消息时自动分析", prefs.autoAnalyze)
        card2.addView(autoRow)
        root.addView(card2)

        // --- 4. 外观 ---
        root.addView(section("外观"))
        val card3 = card()
        val opacityLabel = label("悬浮窗不透明度：${prefs.overlayOpacity}%")
        card3.addView(opacityLabel)
        card3.addView(text("越低越透，越能看清下面的聊天", 12f, sub))
        val seek = SeekBar(this).apply {
            max = 40; progress = prefs.overlayOpacity - 60  // 60..100
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) {
                    opacityLabel.text = "悬浮窗不透明度：${p + 60}%"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        card3.addView(seek)
        root.addView(card3)

        // --- 5. 保存配置 ---
        root.addView(primaryBtn("保存配置") {
            prefs.apiProvider = currentSelectedProvider.id
            prefs.setKey(currentSelectedProvider.id, keyEdit.text.toString())
            prefs.setBaseUrl(currentSelectedProvider.id, urlEdit.text.toString().ifBlank { currentSelectedProvider.defaultBaseUrl })
            prefs.setModel(currentSelectedProvider.id, modelEdit.text.toString().ifBlank { currentSelectedProvider.defaultModel })

            prefs.jevEnabled = (jevToggle.tag as? Boolean) ?: true
            prefs.jevKey = jevKeyEdit.text.toString().trim()

            prefs.relationship = relEdit.text.toString().ifBlank { Prefs.DEFAULT_REL }
            prefs.customStyle = styleEdit.text.toString().ifBlank { Prefs.DEFAULT_CUSTOM_STYLE }
            prefs.whitelist = wlEdit.text.toString().split("\n").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            prefs.autoAnalyze = (autoRow.tag as? Boolean) ?: true
            prefs.overlayOpacity = seek.progress + 60
            val jevStatus = if (prefs.jevEnabled && prefs.hasJevKey()) "已启用 (TypeSafe)" else "已停用"
            Toast.makeText(this, "配置已保存（补全: ${currentSelectedProvider.name}，Jev研判: $jevStatus）", Toast.LENGTH_SHORT).show()
        })

        root.addView(text("Jev 聊天助手 v$appVersionName (Build $appVersionCode)\n双引擎架构 · 文本补全 + Jev 研判", 12f, sub).apply {
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            setPadding(0, dp(18), 0, dp(10))
        })

        setContentView(scroll)
    }

    private fun chipBtn(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 12f
        setTextColor(accent)
        background = round(dp(6), Color.parseColor("#EEF4FF"), stroke = true)
        setPadding(dp(8), dp(4), dp(8), dp(4))
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            rightMargin = dp(6)
            topMargin = dp(4)
            bottomMargin = dp(4)
        }
        layoutParams = lp
        setOnClickListener { onClick() }
    }

    private fun toggleRow(labelText: String, initial: Boolean): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(2)); tag = initial
        }
        val lab = text(labelText, 14f, ink).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val sw = TextView(this).apply {
            text = if (initial) "开" else "关"; textSize = 13f; gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (initial) Color.WHITE else sub)
            background = round(dp(10), if (initial) accent else Color.parseColor("#E5E7EB"))
            setPadding(dp(18), dp(6), dp(18), dp(6))
        }
        sw.setOnClickListener {
            val now = !((row.tag as? Boolean) ?: true); row.tag = now
            sw.text = if (now) "开" else "关"
            sw.setTextColor(if (now) Color.WHITE else sub)
            sw.background = round(dp(10), if (now) accent else Color.parseColor("#E5E7EB"))
        }
        row.addView(lab); row.addView(sw)
        return row
    }

    // atoms
    private fun header(t: String) = text(t, 24f, ink, bold = true).apply { setPadding(0, 0, 0, dp(4)) }
    private fun section(t: String) = text(t, 12f, sub, bold = true).apply { setPadding(dp(2), dp(16), 0, dp(6)) }
    private fun label(t: String) = text(t, 13f, ink, bold = true).apply { setPadding(0, dp(12), 0, dp(4)) }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = round(dp(14), Color.WHITE)
        setPadding(dp(14), dp(4), dp(14), dp(14))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun edit(value: String, hint: String, password: Boolean = false) = EditText(this).apply {
        setText(value); this.hint = hint; textSize = 14f; setTextColor(ink)
        background = round(dp(8), Color.parseColor("#F3F4F6"))
        setPadding(dp(10), dp(10), dp(10), dp(10))
        if (password) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) }
    }

    private fun text(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun primaryBtn(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 15f; gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.WHITE); background = round(dp(12), accent)
        setPadding(dp(16), dp(13), dp(16), dp(13))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18) }
        setOnClickListener { onClick() }
    }

    private fun secondaryBtn(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 15f; gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD)
        setTextColor(accent); background = round(dp(12), Color.WHITE, stroke = true)
        setPadding(dp(16), dp(12), dp(16), dp(12))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
        setOnClickListener { onClick() }
    }

    private fun round(radius: Int, color: Int, stroke: Boolean = false) = GradientDrawable().apply {
        cornerRadius = radius.toFloat(); setColor(color); if (stroke) setStroke(dp(1), accent)
    }

    override fun onDestroy() { super.onDestroy(); worker.shutdownNow() }
}

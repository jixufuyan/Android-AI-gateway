package dev.aigw.core.provider.dumate

import dev.aigw.core.provider.ProviderModel

/**
 * 搭子的模型目录。
 *
 * **上游没有模型列表接口**（`/models`、`/model/list` 实测 404），所以这张表是静态的：
 * 名字与上下文/输出上限来自对上游的实际探测（400 错误体明示范围、二分夹逼、
 * `X-Dumate-Gw-Realmodel` 响应头回传真实后端）。
 *
 * 不编造字段：拿不到的上限留 0，界面显示「—」而不是猜一个看起来合理的数字——
 * 猜出来的上下文会让用户以为真的能塞 128K，直到请求失败才发现。
 */
internal object DumateModelCatalog {

    /**
     * 一个模型条目。
     *
     * [real] 是别名实际落到的后端模型名（仅用于展示与排查，不参与请求）。
     */
    private data class Entry(
        val id: String,
        val label: String,
        val real: String,
        val context: Long,
        val maxOutput: Long,
        val note: String = "",
        /** 展示用的档次短标签（模型页直接显示，用户不用猜 L0/L1/L2 是什么）。 */
        val tier: String = "",
    )

    /**
     * 固定模型表。
     *
     * `dm-auto-model` 系列（text.L0 / text.L1 / text.L2）的**档位编码在模型名里**，
     * 斜杠不是通道分隔符：调用时整串发给上游，由它按档位选后端。
     */
    private val ENTRIES = listOf(
        Entry(
            id = "model-text",
            label = "model-text（上游默认档）",
            real = "deepseek-v4.1-flash-low-latency",
            context = 1_048_576,
            maxOutput = 393_216,
            note = "上下文上限由上游 400 错误体明示",
            tier = "DeepSeek · 快",
        ),
        Entry(
            id = "glm-5",
            label = "glm-5",
            real = "glm-5.2",
            context = 1_048_576,
            maxOutput = 131_072,
            note = "上下文为二分夹逼估算（GLM 上游只报「Prompt 超长」）",
            tier = "智谱 GLM",
        ),
        Entry(
            id = "dm-auto-model/text.L0",
            label = "dm-auto text.L0（DeepSeek 低延迟档）",
            real = "deepseek-v4.1-flash-low-latency",
            context = 1_048_576,
            maxOutput = 393_216,
            tier = "DeepSeek · 快",
        ),
        Entry(
            id = "dm-auto-model/text.L1",
            label = "dm-auto text.L1（Kimi 档）",
            real = "kimi-k3",
            context = 1_048_576,
            maxOutput = 2_000_000,
            note = "输出上限为实测下界，未探顶",
            tier = "Kimi · 超长输出",
        ),
        Entry(
            id = "dm-auto-model/text.L2",
            label = "dm-auto text.L2（Qwen-Max 档）",
            real = "qwen3.8-max",
            context = 983_616,
            maxOutput = 131_072,
            tier = "通义千问 Max",
        ),
        Entry(
            id = "dm-auto-model/mm.L0",
            label = "dm-auto mm.L0（多模态，DeepSeek 后端）",
            real = "deepseek-v4.1-flash-low-latency",
            context = 1_048_576,
            maxOutput = 393_216,
            note = "视觉由文本后端承担",
            tier = "DeepSeek · 能看图",
        ),
        Entry(
            id = "dm-auto-model/mm.L2",
            label = "dm-auto mm.L2（多模态，Qwen-Max 后端）",
            real = "qwen3.8-max",
            context = 983_616,
            maxOutput = 131_072,
            tier = "通义千问 Max · 能看图",
        ),
    )

    /** 对外暴露的模型条目（id 不带 `dumate/` 前缀，由网关的 routePrefix 拼）。 */
    fun models(): List<ProviderModel> = ENTRIES.map { entry ->
        ProviderModel(
            id = entry.id,
            name = entry.label,
            contextWindow = entry.context,
            extra = buildMap {
                put("real", entry.real)
                // 让模型页直接标出「这是哪个档次/能不能看图」，不用用户去猜 L0/L1/L2
                if (entry.id.contains(".mm.")) put("vision", "true")
                put("tier", entry.tier)
                if (entry.maxOutput > 0) put("maxOutput", entry.maxOutput.toString())
                if (entry.note.isNotEmpty()) put("note", entry.note)
            },
        )
    }

    /** 别名 → 真实后端模型名；查不到返回空串。 */
    fun realModelOf(id: String): String =
        ENTRIES.firstOrNull { it.id.equals(id, ignoreCase = true) }?.real.orEmpty()

    /** 该模型的输出上限；未知返回 0。 */
    fun maxOutputOf(id: String): Long =
        ENTRIES.firstOrNull { it.id.equals(id, ignoreCase = true) }?.maxOutput ?: 0L

    /** 默认模型：上游的通用文本档。 */
    const val DEFAULT_MODEL = "model-text"
}


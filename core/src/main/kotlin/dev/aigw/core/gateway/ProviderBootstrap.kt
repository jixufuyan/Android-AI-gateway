package dev.aigw.core.gateway

import dev.aigw.core.provider.ProviderRegistry
import dev.aigw.core.provider.antigravity.AntigravityProvider
import dev.aigw.core.provider.autoglm.AutoglmProvider
import dev.aigw.core.provider.chatgpt.ChatGPTProvider
import dev.aigw.core.provider.chatglm.ChatglmProvider
import dev.aigw.core.provider.codebuddy.CodeBuddyProvider
import dev.aigw.core.provider.dumate.DumateProvider
import dev.aigw.core.provider.loomy.LoomyProvider
import dev.aigw.core.provider.raccoon.RaccoonProvider
import dev.aigw.core.provider.trae.TraeConstants
import dev.aigw.core.provider.trae.TraeProvider
import dev.aigw.core.provider.trae.TraeVersion

/**
 * 内置供应商的登记处。
 *
 * 新增一个内置供应商时，只在这里加一行 `register(...)`，网关与其它 provider 都不需要改。
 */
internal fun registerBuiltinProviders(engine: GatewayEngine) {
    val registry: ProviderRegistry = engine.registry

    registry.register(
        TraeProvider(
            store = engine.store(),
            version = {
                val settings = engine.providerSettings(TraeProvider.ID)
                TraeVersion(
                    ideVersion = settings.option("ideVersion", "0.1.52"),
                    ideVersionCode = settings.option("ideVersionCode", "20260811"),
                )
            },
            defaultModel = {
                engine.providerSettings(TraeProvider.ID).defaultModel
                    .ifEmpty { TraeConstants.DEFAULT_CONFIG_NAME }
            },
            hooks = engine.hooks,
            nowMillis = { engine.now() },
        ),
    )

    registry.register(
        LoomyProvider(
            defaultModel = { engine.providerSettings(LoomyProvider.ID).defaultModel },
            nowMillis = { engine.now() },
            hooks = engine.hooks,
        ),
    )

    registry.register(
        AntigravityProvider(
            hooks = engine.hooks,
            nowMillis = { engine.now() },
        ),
    )

    registry.register(
        ChatGPTProvider(
            hooks = engine.hooks,
            nowMillis = { engine.now() },
        ),
    )

    registry.register(
        CodeBuddyProvider(
            region = { engine.providerSettings(CodeBuddyProvider.ID).option("region", CodeBuddyProvider.REGION_CN) },
            nowMillis = { engine.now() },
            hooks = engine.hooks,
            promptRewrite = { engine.securitySettings().promptRewriteEnabled },
        ),
    )

    registry.register(
        RaccoonProvider(
            hooks = engine.hooks,
            nowMillis = { engine.now() },
        ),
    )

    // 百度搭子：网页 cookie → bearer token → 云端 OpenAI 兼容网关。
    // 放在最后注册不影响路由优先级——路由靠显式前缀 `dumate/` 与模型名特征判定，
    // 不是按注册顺序取第一个匹配。
    registry.register(
        DumateProvider(
            hooks = engine.hooks,
            nowMillis = { engine.now() },
        ),
    )

    // 智谱 AutoGLM：短信登录 → JWT 凭证 → OpenAI 兼容对话（X-Authorization + system 闸门）。
    registry.register(
        AutoglmProvider(
            hooks = engine.hooks,
            nowMillis = { engine.now() },
        ),
    )

    // 智谱清言：粘贴 cookie/JSON 导入 → 双 rotate 刷新 →
    // project/list 解析 agent → conversation → SSE 帧（增量/全量 + 10030 重连）。
    registry.register(
        ChatglmProvider(
            hooks = engine.hooks,
            nowMillis = { engine.now() },
        ),
    )
}

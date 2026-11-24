package dev.aigw.app.ui.providers.chatglm

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.aigw.app.ui.AppTextField
import dev.aigw.app.ui.OutlineActionButton
import dev.aigw.app.ui.SectionCard
import dev.aigw.app.ui.SectionLabel
import dev.aigw.app.ui.providers.ProviderUi
import dev.aigw.app.ui.providers.ProviderUiActions
import dev.aigw.core.pool.AccountStatus

/**
 * 智谱清言的界面组件：粘贴凭证导入（cookie 或 JSON）。
 *
 * 上游把 X-Device-Id 与令牌所在浏览器会话绑定，没有可回调的登录流程；
 * 与本地网关同款做法：在浏览器登录 chatglm.cn 后，从 DevTools 里复制
 * `chatglm_token` / `chatglm_refresh_token` 两个 cookie（可带 `chatglm-deid`
 * localStorage 值）粘贴导入，导入时会自动验活（user/info）。
 */
object ChatglmUi : ProviderUi {

    override val id: String = "chatglm"

    override fun description(): String = "智谱清言网页端，粘贴 cookie 或凭证 JSON 导入"

    @Composable
    override fun Icon(modifier: Modifier) {
        Icon(
            imageVector = Icons.Filled.AutoAwesome,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = modifier,
        )
    }

    @Composable
    override fun LoginEntry(accounts: List<AccountStatus>, actions: ProviderUiActions) {
        var expanded by remember { mutableStateOf(false) }
        var raw by remember { mutableStateOf("") }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionCard(enterIndex = 0) {
                SectionLabel("登录")
                Button(
                    onClick = { actions.onLoginWebView(id) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("打开智谱清言登录页")
                }
                OutlineActionButton(
                    text = if (expanded) "收起粘贴导入" else "粘贴凭证导入（兑底）",
                ) { expanded = !expanded }
                if (expanded) {
                    AppTextField(
                        value = raw,
                        onValueChange = { raw = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 6,
                        label = { Text("cookie（chatglm_token=…）或 JSON") },
                    )
                    Button(
                        onClick = {
                            actions.onImport(id, raw.trim())
                            raw = ""
                        },
                        enabled = raw.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("导入（自动验活）")
                    }
                }
            }
        }
    }
}

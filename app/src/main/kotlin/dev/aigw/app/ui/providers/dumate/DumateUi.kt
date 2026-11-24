package dev.aigw.app.ui.providers.dumate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.unit.dp
import com.google.gson.JsonObject
import dev.aigw.app.ui.AppTextField
import dev.aigw.app.ui.ChoiceChip
import dev.aigw.app.ui.OutlineActionButton
import dev.aigw.app.ui.SectionCard
import dev.aigw.app.ui.SectionLabel
import dev.aigw.app.ui.providers.ProviderUi
import dev.aigw.app.ui.providers.ProviderUiActions
import dev.aigw.core.pool.AccountStatus
import dev.aigw.core.provider.ACTION_CHECKIN
import dev.aigw.core.provider.dumate.DumateCookiePick
import dev.aigw.core.provider.dumate.DumateProvider

/**
 * 百度搭子的界面。
 *
 * 登录主路径是**应用内网页登录**：点按钮在应用里打开百度登录页，手机号验证码/密码
 * 都在这个页面里完成，登录成功后从同一个 WebView 的 cookie 罐里把整包登录态取出
 * 落池，用户不用再去电脑上按 F12 抄 cookie。
 *
 * 备用路径保留「粘贴 cookie」：应用内 WebView 被风控拦、或用户本来就在电脑上登录着
 * 搭子时，抄一条 cookie 反而更快。
 */
object DumateUi : ProviderUi {

    override val id: String = DumateProvider.ID

    override fun description(): String = "网页登录自动导入，直连云端模型网关"

    @Composable
    override fun Icon(modifier: Modifier) {
        Icon(
            Icons.Filled.Cloud,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = modifier,
        )
    }

    @Composable
    override fun LoginEntry(accounts: List<AccountStatus>, actions: ProviderUiActions) {
        // 已有账号时默认收起备用路径，避免每次进页面都被一大段说明占满
        var expanded by remember { mutableStateOf(false) }
        var raw by remember { mutableStateOf("") }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionCard(enterIndex = 0) {
                SectionLabel("登录")
                Text(
                    text = "点下面的按钮，在应用内打开百度登录页，用手机号验证码或账号密码登录。" +
                        "登录完成后会自动识别并添加账号，不需要手动抄 cookie。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = { actions.onLoginWebView(id) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (accounts.isEmpty()) "打开百度搭子登录页面" else "再登录一个账号")
                }

                OutlineActionButton(
                    text = if (expanded) "收起粘贴导入" else "粘贴 cookie 导入（备用）",
                ) { expanded = !expanded }
                if (expanded) {
                    // 说明要写到「复制哪一条、哪几个字段」，不然用户抄一堆无用的东西回来
                    Text(
                        text = "电脑浏览器打开 console.bce.baidu.com 并登录（要能看到搭子页面）→ " +
                            "F12 → Network → 点任意一个 console.bce.baidu.com 的请求 → " +
                            "复制 Request Headers 里的整条 Cookie，粘贴到下面。\n" +
                            "只需要这几个字段：BDUSS、bce-passport-stoken、bce-login-accountid、bce-sessionid" +
                            "（其余自动剔除）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    AppTextField(
                        value = raw,
                        onValueChange = { raw = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 8,
                        label = { Text("Cookie（BDUSS=…; STOKEN=…; …）") },
                    )
                    // 从电脑/浏览器抄来的 cookie 直接读剪贴板，省一次长按粘贴
                    val clipboard = LocalClipboardManager.current
                    OutlineActionButton("从剪贴板读取") {
                        clipboard.getText()?.text?.trim()?.let { if (it.isNotEmpty()) raw = it }
                    }
                    // 粘贴结果的即时反馈：缺哪个必需字段当场说清楚，别等换 token 失败才发现
                    var pasteHint by remember { mutableStateOf("") }
                    Button(
                        onClick = {
                            val compact = DumateCookiePick.pick(listOf(raw.trim()))
                            val missing = DumateCookiePick.missingKeys(raw)
                            if (missing.isNotEmpty()) {
                                pasteHint = "粘贴内容里缺：" + missing.joinToString("、")
                            } else {
                                pasteHint = "字段齐全（已压缩为 " +
                                    DumateCookiePick.parse(compact).size + " 项），正在验证…"
                                actions.onImport(id, compact)
                                raw = ""
                            }
                        },
                        enabled = raw.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("导入并验证")
                    }
                    if (pasteHint.isNotEmpty()) {
                        Text(
                            text = pasteHint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }

    @Composable
    override fun AccountExtra(status: AccountStatus, actions: ProviderUiActions) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (status.detail.isNotEmpty()) {
                Text(
                    text = status.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceChip("签到领积分", false) {
                    actions.onAction(id, status.uid, ACTION_CHECKIN, JsonObject())
                }
            }
        }
    }
}

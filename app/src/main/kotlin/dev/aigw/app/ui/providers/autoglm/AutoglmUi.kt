package dev.aigw.app.ui.providers.autoglm

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import dev.aigw.app.ui.AppTextField
import dev.aigw.app.ui.SectionCard
import dev.aigw.app.ui.SectionLabel
import dev.aigw.app.ui.providers.ProviderUi
import dev.aigw.app.ui.providers.ProviderUiActions
import dev.aigw.core.pool.AccountStatus

/**
 * 智谱 AutoGLM 的界面组件：短信验证码登录（与 Loomy 同一套交互）。
 *
 * 上游只对大陆手机号发码，国家码固定 86；沿用同一套「60 秒重发冷却」。
 */
object AutoglmUi : ProviderUi {

    override val id: String = "autoglm"

    private const val RESEND_SECONDS = 60

    override fun description(): String = "智谱 AutoGLM（autoclaw），手机号短信登录"

    @Composable
    override fun Icon(modifier: Modifier) {
        Icon(
            imageVector = Icons.Filled.SmartToy,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = modifier,
        )
    }

    @Composable
    override fun LoginEntry(accounts: List<AccountStatus>, actions: ProviderUiActions) {
        var phone by remember { mutableStateOf("") }
        var code by remember { mutableStateOf("") }
        var countdown by remember { mutableStateOf(0) }

        LaunchedEffect(countdown) {
            if (countdown > 0) {
                delay(1_000)
                countdown -= 1
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionCard(enterIndex = 0) {
                SectionLabel("登录")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppTextField(
                        value = "86",
                        onValueChange = {},
                        modifier = Modifier.width(88.dp),
                        singleLine = true,
                        readOnly = true,
                        label = { Text("国家码") },
                    )
                    AppTextField(
                        value = phone,
                        onValueChange = { phone = it.filter(Char::isDigit).take(11) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        label = { Text("手机号") },
                    )
                }
                AppTextField(
                    value = code,
                    onValueChange = { code = it.filter(Char::isDigit).take(6) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    label = { Text("验证码") },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = {
                            countdown = RESEND_SECONDS
                            actions.onSmsLogin(id, phone.trim(), "", "86") { error ->
                                if (error.isNotEmpty()) countdown = 0
                            }
                        },
                        enabled = phone.isNotBlank() && countdown == 0,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(if (countdown > 0) "$countdown 秒后重发" else "发送验证码")
                    }
                    Button(
                        onClick = {
                            actions.onSmsLogin(id, phone.trim(), code.trim(), "86") { }
                        },
                        enabled = phone.isNotBlank() && code.isNotBlank(),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("登录")
                    }
                }
            }
        }
    }
}

package dev.aigw.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

/**
 * 请求改写页。
 *
 * 只改发往上游的请求体，不碰客户端收到的东西。任一改写出错会被跳过、继续下一个，
 * 所以这里配错了最坏情况是「没生效」，不会让请求失败。
 */
@Composable
fun RequestRewriteScreen(state: AppUiState, viewModel: AppViewModel, onBack: () -> Unit) {
    var aliasesText by remember(state.middleware.aliases) {
        mutableStateOf(state.middleware.aliases.entries.joinToString("\n") { "${it.key} ${it.value}" })
    }
    var systemAppend by remember(state.middleware.systemPromptAppend) {
        mutableStateOf(state.middleware.systemPromptAppend)
    }
    val stripThinkTags = state.middleware.stripThinkTags
    // 输入框展开态：内容多时输入框会撑得很高，把卡片顶到几屏长；
    // 默认收起成 3 行，点「展开/收起」手动切换
    var appendExpanded by remember { mutableStateOf(false) }

    // 停止输入 700ms 后自动落盘。原先只有「保存」按钮能落盘，直接返回上一页就白填了 ——
    // 加载回来的 state 还是旧值，用户看到的现象就是「追加提示词填了但完全没生效」。
    // 内容被清空时同时把开关关掉：留一个「开着但没内容」的开关只会误导。
    LaunchedEffect(systemAppend) {
        val blank = systemAppend.isBlank()
        if (systemAppend != state.middleware.systemPromptAppend) {
            kotlinx.coroutines.delay(700)
            viewModel.updateMiddleware(
                if (blank) {
                    state.middleware.copy(
                        systemPromptAppend = systemAppend,
                        systemPromptAppendEnabled = false,
                    )
                } else {
                    state.middleware.copy(systemPromptAppend = systemAppend)
                },
            )
        }
    }

    PageScaffold(
        title = "请求改写",
        subtitle = "别名、system 提示词与思考标签",
        leading = { BackButton(onBack) },
    ) {
        SectionCard(enterIndex = 0) {
            Text(
                text = "改写只作用于发往上游的请求体。某个改写执行出错时会被自动跳过，" +
                    "不影响这次请求成功。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionLabel("模型别名")
        SectionCard(enterIndex = 1) {
            Text(
                text = "客户端写左边的名字，网关实际请求右边的模型。一行一条，用空格分隔。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AppTextField(
                value = aliasesText,
                onValueChange = { aliasesText = it },
                label = { Text("别名表") },
                placeholder = { Text("fast glm-5.3-flash\nopus claude-opus-5-5") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlineActionButton("保存别名") {
                viewModel.updateMiddleware(state.middleware.copy(aliases = parseAliases(aliasesText)))
            }
        }

        SectionLabel("system 提示词追加")
        SectionCard(enterIndex = 2) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "启用追加",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "关掉后内容保留但不再追加，临时停用免清空重填。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AppSwitch(
                    checked = state.middleware.systemPromptAppendEnabled,
                    onCheckedChange = { enabled ->
                        // 内容为空时不允许开启：空内容即便开了开关也不会产生任何追加，
                        // 开关亮着却什么都不发生，容易被误判成「功能坏了」。
                        if (enabled && systemAppend.isBlank()) {
                            viewModel.notice("先在「追加内容」里填写文本，再打开开关")
                        } else {
                            viewModel.updateMiddleware(
                                // 带上当前输入框里的文字：否则 state.middleware 里还是上一次保存的旧值，
                                // 切开关会把刚打的字冲掉（表现成「填了提示词，一碰开关就没了」）
                                state.middleware.copy(
                                    systemPromptAppendEnabled = enabled,
                                    systemPromptAppend = systemAppend,
                                ),
                            )
                        }
                    },
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "追加到第一条 system 消息的末尾；没有 system 消息时新建一条。留空表示不改。" +
                    "实测紧贴末尾的指令最容易被模型遵循，破甲 / 角色设定 / 格式要求都放这里。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AppTextField(
                value = systemAppend,
                onValueChange = { systemAppend = it },
                label = { Text("追加内容") },
                minLines = if (appendExpanded) 6 else 3,
                maxLines = if (appendExpanded) 12 else 3,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (appendExpanded) "收起" else "展开",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable { appendExpanded = !appendExpanded }
                        .padding(vertical = 4.dp),
                )
                Spacer(Modifier.weight(1f))
            }
            OutlineActionButton("保存追加内容") {
                viewModel.updateMiddleware(state.middleware.copy(systemPromptAppend = systemAppend))
            }
        }

        SectionLabel("思考标签")
        SectionCard(enterIndex = 3) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "从 assistant 消息里剥离 <think> 段",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "有些客户端会把上一轮的思考内容原样回传，导致上下文越来越长、也更容易触发上游审核。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AppSwitch(
                    checked = stripThinkTags,
                    onCheckedChange = { enabled ->
                        viewModel.updateMiddleware(state.middleware.copy(stripThinkTags = enabled))
                    },
                )
            }
        }

        val failures = state.middlewareFailures
        if (failures.isNotEmpty()) {
            SectionLabel("最近失败")
            SectionCard(enterIndex = 4) {
                failures.entries.forEachIndexed { index, (id, reason) ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(id, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.3f))
                        Text(
                            text = reason,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(0.7f),
                        )
                    }
                }
            }
        }
    }
}

/** 一行一条「别名 目标」，也接受用等号/箭头分隔。 */
private fun parseAliases(text: String): Map<String, String> {
    val result = LinkedHashMap<String, String>()
    for (line in text.lines()) {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) continue
        val parts = trimmed.replace("->", " ").replace("=>", " ").replace("=", " ")
            .split(Regex("\\s+"), limit = 2)
        if (parts.size != 2) continue
        val from = parts[0].trim()
        val to = parts[1].trim()
        if (from.isEmpty() || to.isEmpty()) continue
        result[from] = to
    }
    return result
}

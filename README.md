# AI 聚合网关（二改版）

Android 上运行的本地 AI 模型聚合网关：把多家私有 AI 服务账号统一翻译成 OpenAI 兼容接口，暴露在 `http://127.0.0.1:8790/v1`。

任何支持 OpenAI 协议的客户端（Cline、Cherry Studio、Continue、curl 脚本……）都能直接接上手机里的全部账号额度。

`Kotlin` · `Jetpack Compose` · `NanoHTTPD` · `Gradle` · `minSdk 26 / targetSdk 36`

---

## 这是什么

这是 [xibai6668/Android-AI-gateway](https://github.com/xibai6668/Android-AI-gateway) 的**二改版**，
在上游基础上加了几个供应商和一批修复。

- **上游主仓库**：https://github.com/xibai6668/Android-AI-gateway
- **本仓库**（二改）：https://github.com/jixufuyan/Android-AI-gateway
- **分支说明**：
  - `main` —— 干净跟随上游，不掺任何改动
  - `ide` —— 二改版，自动构建和发版都基于这个分支

---

## 二改加了什么

### 三个新供应商

| 供应商 | 说明 |
|---|---|
| **AutoGLM** | 智谱 AutoGLM 网页版，支持账号登录与模型列表自动同步 |
| **ChatGLM** | 智谱 ChatGLM 网页版，独立的登录流程与 SSE 流式解析 |
| **Dumate** | Dumate 网页版，支持 Cookie 选择、额度查询与模型能力识别 |

三者都实现了完整链路：登录 → 账号管理 → 模型目录 → 请求转发 → 错误分类 → 余额/额度查询。

### 重要修复

| 修复 | 说明 |
|---|---|
| **被限流不再死守一家** | 上游返回「HTTP 200 + 错误帧」时正确识别并切换供应商。原来只有小浣熊做了预读，其它供应商直接把错误帧透传给了客户端 |
| **同款模型合并** | 跨供应商同款模型名归一化（点/短横/大小写/前缀/快照号），避免同一模型被当成多个 |
| **失败不误记成功** | `passthrough` 在收到字节之前不再提前记成功；零字节响应判为失败 |

### 其它改动

- 请求重写规则可视化编辑与导入导出
- 模型定价页面、路由分组界面优化
- 崩溃日志与安全保护页调整
- 稳定的 debug 签名（容器重置后 debug 包仍可原地升级）

---

## 特性（继承自上游）

- **OpenAI 兼容网关**：`/v1/chat/completions` + `/v1/models`，流式 SSE / 非流式 / 多模态识图；另提供 `/v1/images/generations`（图片生成）与 `/v1/audio/transcriptions`（语音转文字）。
- **内置供应商**：Trae、WorkBuddy、Antigravity、ChatGPT、Loomy、小浣熊（商汤）、**AutoGLM**、**ChatGLM**、**Dumate**，以及任意自定义 OpenAI 兼容中转站。
- **同款模型 Failover**：首选供应商失败或无可用账号时，自动切换到拥有同款模型的其他供应商。
- **智能路由**：`供应商/模型` 前缀路由（含 `trae-cn/`、`codebuddy-global/` 这类区域前缀与 `google/`、`agy/` 等别名），兼支持无前缀的模型名自动推导。
- **账号管理**：同一供应商多账号按余额选号、失败自动换号、额度查询与额度包明细、跨供应商容灾、批量签到与任务中心。
- **模型开放名单**：只把勾选的模型暴露给 `/v1/models`，其余模型对客户端隐藏。

---

## 下载

去 [Releases](https://github.com/jixufuyan/Android-AI-gateway/releases) 拿最新 APK。

> ⚠️ **升级前先卸载旧版**。
> 本版版本号规则是「上游 patch + 1」，`versionCode` 比历史二改版小，
> 直接覆盖安装会报 `INSTALL_FAILED_VERSION_DOWNGRADE`。

---

## 自动构建

上游有新提交时，GitHub Actions 会自动：

1. 检查上游有没有新版（每 6 小时一次）
2. 三方合并（工具链文件冲突时保留本地版）
3. 核对二改补丁没被上游悄悄覆盖（19 条锚点）
4. 版本号按「上游 +1」计算
5. 跑 detekt + 单元测试 + 打包 + 发 Release

工作流文件：[`.github/workflows/auto-sync-upstream.yml`](.github/workflows/auto-sync-upstream.yml)
配置手册：[`docs/AUTO-SYNC.md`](docs/AUTO-SYNC.md)

如果合并遇到冲突、或者补丁被上游覆盖，会自动建 Issue 提醒，不会悄悄产出错误的包。

---

## 构建

需要 **JDK 17** 和 **Gradle 9.0.0**（二改锁定这个组合，不要跟上游升到 9.9.1）。

```bash
# 本地构建（debug 包）
gradle :app:assembleDebug

# 云端构建（release 包 + 签名）
# 推送到 ide 分支后由 Actions 自动完成
```

---

## 使用声明

本项目仅供个人学习与技术研究使用。

- 请遵守各 AI 服务提供方的服务条款
- 不要用于商业转售、批量薅取额度等行为
- 因使用本项目产生的任何后果由使用者自行承担

原项目版权归 [xibai6668](https://github.com/xibai6668) 所有，遵循原项目的 MIT + 附加使用声明。

#  AI 聚合网关

Android 上运行的本地 AI 模型聚合网关：把多家私有 AI 服务账号统一翻译成 **OpenAI** 与 **Anthropic** 双协议接口，暴露在 `http://127.0.0.1:8790`。

任何支持 OpenAI 协议的客户端（Cline、Cherry Studio、Continue、curl 脚本……）以及只认 Anthropic 协议的客户端（Claude Code、Anthropic SDK）都能直接接上手机里的账号额度。

`Kotlin` · `Jetpack Compose` · `NanoHTTPD` · `Gradle` · `minSdk 26 / targetSdk 36`

> **本仓库是 [xibai6668/Android-AI-gateway](https://github.com/xibai6668/Android-AI-gateway) 的分支（fork）。**
> 上游负责网关内核与大部分供应商协议，本分支在上游基础上补充了 **AutoGLM / ChatGLM / Dumate** 三家供应商、自动跟随上游的发布流水线、以及若干体验修正。
> 版本号跟随上游 patch +1 递增，与上游的 `2.x` 不是同一条线，**不要按版本号大小比较两者**。

---

## 目录

- [特性](#特性)
- [与上游的差异](#与上游的差异)
- [快速开始](#快速开始)
- [自动跟随上游](#自动跟随上游)
- [架构总览](#架构总览)
- [模块结构](#模块结构)
- [数据存储](#数据存储)
- [构建与发版](#构建与发版)
- [接口与鉴权](#接口与鉴权)
- [供应商](#供应商)
- [贡献](#贡献)
- [许可](#许可)

---

## 特性

**协议**

- **OpenAI 兼容网关**：`/v1/chat/completions` + `/v1/models`，流式 SSE / 非流式 / 多模态识图；另提供 `/v1/images/generations`（图片生成）与 `/v1/audio/transcriptions`（语音转文字）。
- **Anthropic 兼容入口**：`/v1/messages` 与 `/v1/messages/count_tokens`，请求在网关入口翻译成 OpenAI 形状后复用同一批 Provider，响应再翻回去——新增供应商不需要写两遍。
- **智能路由**：`供应商/模型` 前缀路由（含 `trae-cn/`、`codebuddy-global/` 这类区域前缀与 `google/`、`agy/` 等别名），兼支持无前缀的模型名自动推导。

**供应商**

- **内置**：Trae、WorkBuddy / CodeBuddy、Antigravity、ChatGPT、Loomy、小浣熊（商汤）、**AutoGLM（智谱）**、**ChatGLM（智谱）**、**Dumate**、GitHub Copilot，以及任意自定义 OpenAI 兼容中转站。
- **同款模型 Failover**：首选供应商失败或无可用账号时，自动切换到拥有同款模型的其他供应商。
- **倍率数据自动更新**：模型计费倍率（以及限时折扣）放在仓库的 `config/model-rates.json`，App 自启动后每 12 小时自动拉一次，失败退避 30 分钟且不清空旧值。

**账号**

- **账号池**：同一供应商多账号按余额选号、失败自动换号、跨供应商容灾。
- **额度与账本**：额度查询、额度包明细、积分流水、批量签到、任务中心。
- **模型开放名单**：只把勾选的模型暴露给 `/v1/models`，其余对客户端隐藏。

**运维**

- **用量与日志**：调用记录（请求/转发/返回全链路）、今日与累计用量趋势、运行日志、按前缀统计存储占用与保留期自动清理。
- **网络代理**：HTTP / SOCKS5，支持按供应商与按地址排除；国内上游始终直连。
- **安全防护**：system 提示词零宽字符脱敏 + 账号级最小间隔限速（带抖动）。
- **后台保活**：前台服务 + WakeLock / WifiLock + 控制中心磁贴，锁屏仍可服务。
- **应用内更新**：查本仓库 Release，提示下载新版本 APK。

---

## 与上游的差异

本分支相对上游 `838fe764` 的增量（119 个文件，+12049 / −549 行）：

| 能力 | 说明 |
|---|---|
| **AutoGLM 供应商** | 智谱 AutoGLM。登录走 `agent-send-code` → `agent-login`（两者必须复用同一 `device_id`）；对话是 OpenAI 兼容端点，但认证头是 **`X-Authorization`** 而非 `Authorization`；签名 API 用 `X-Auth-Sign = md5("100003&" + ts + "&" + 盐)`。模型表以服务端 `autoclaw-model-config` 为准，本地表只作兜底。 |
| **ChatGLM 供应商** | 智谱清言（`agentmore.chatglm.cn`）。会话走 `agent-api/conversation` + `agent-api/stream`，不是标准 chat/completions；签名盐随官方 Web 端分发，`project/list` 的 agent 列表带 TTL 缓存。 |
| **Dumate 供应商** | 百度搭子。走网页链路：浏览器登录 `www.dumate.cn` 拿 cookie，换 1 小时有效的 bearer token，再直连云端网关的 OpenAI 兼容端点（`dumate-svc.baidu.com/gateway/apis/v1`）。桌面客户端那条路是本地 `:8980` 的 Go 后端 + `%APPDATA%\qianfan-desktop-app\auth.json`，依赖 Windows 路径，Android 上无法复现，所以没有实现。 |
| **自动跟随上游**（本分支新增） | GitHub Actions 每 6 小时拉一次上游，自动三方合并、跑守卫校验、构建签名包并发 Release。详见 [自动跟随上游](#自动跟随上游)。 |
| **版本号规则**（本分支新增） | `versionName` = 上游 patch + 1，`versionCode` = 上游 code + 1。上游发新版本时由 CI 自动计算，不手工改。 |
| **Release 正文**（本分支新增） | 照抄上游 `android-release.yml` 的 `git log -- app core` 汇总逻辑，正文只列真实的应用相关提交，不额外添加说明。 |
| **自更新源改指本仓库** | `PROJECT_URL` 与 `UpdateSource.API` 均指向本 fork，避免版本号独立序列导致的误判与降级安装失败。 |
| **system 提示词追加真正发往上游**（修复） | 中间件链产物（含追加改写）此前只用于路由判断，真正拼进上游请求体的是**未改写**的原始 body（`withModel(body, …)`）。表现为设置页填了追加内容、保存成功、开关也开着，上游收到的 system 里一个字都没有。改为 `withModel(rewritten, …)`。 |
| **移除「前置注入」**（本分支新增，后删除） | 曾提供把文本拼在 system **最前面**的入口。实测三个模型（deepseek-v4.1-flash / kimi-k3 / gpt-5.4）里它全部**静默失效**——拼在前面的指令被后面的客户端 system 压住，效果比不开还弱，且界面上看不出没生效。相邻位置生效强的是追加（近因效应，紧贴 user 消息），因此只保留追加一个入口。字段 `systemPromptPrepend` 降级为仅保留老用户文本，不参与任何改写。 |
| **请求改写开关与胶囊对齐**（修复） | 入口胶囊原先只判断内容非空、不看开关，于是「填了字但没开开关」也显示成已启用，与真实发往上游的内容不符。改为**开关开着 && 内容非空**才显示。追加开关默认关闭；内容为空时点击会被拦下并提示先填内容；内容被清空时自动关掉开关。 |
| **长表单输入框不被键盘遮挡**（修复） | `enableEdgeToEdge()` 下 `windowSoftInputMode=adjustResize` 不再收缩窗口，而 `PageScaffold` 没有 `imePadding`，键盘直接盖住靠底部的输入框。给 `PageScaffold` 内容区加 `imePadding()`（置于 `verticalScroll` 之前，否则只加底部空白而视口不变）；`AppTextField` 再加聚焦后 `bringIntoView`（延迟 160ms 等键盘动画完成），21 个页面与全部输入框一次覆盖。 |
| **倍率数据自动更新**（本分支新增） | 上游原本把倍率写死在各家 provider 常量里，一次调价就得发版；且只有 CodeBuddy（目录 `credits` 串）、Trae（本地表）、Today 有值，其余供应商模型页一片空白。本分支补入 `core/rate/`：`config/model-rates.json` 远程表 + 12 小时自动拉取（失败 30 分钟退避、不清空旧值、多镜像×多仓库回退）+ 台账三层优先级（现场 > 远程 > 编译期兜底）。模型目录出网关前统一 `withRates()` 补字段，新增供应商不必自己维护倍率表。 |
| **倍率只显示一行脚注**（本分支取舍） | 上游把「倍率数据」做成列表第一条的整块 `RateSyncCard`，抢了模型列表的视线，也和行内「倍率 0.29×」是同一件事的两种形态。本分支改为页头下方一行极淡脚注（`RateFootnote`，可点刷新），数字本身仍在每个模型行里。 |

**刻意保留的上游行为**：账号池锁优化、SSE 终止帧补齐、模型级冷却、并发闸门、应用内更新、容错页、悬浮保活，均原样保留。

---

## 快速开始

1. 到 [Releases](https://github.com/jixufuyan/Android-AI-gateway/releases) 下载最新的 `ai-gateway-<版本>-release.apk` 安装。
2. 打开 App，首页点「启动网关服务」；首页显示「运行中」即表示端口已在监听。
3. 到「供应商」页登录要用的账号，或添加自定义 OpenAI 兼容中转站。
4. 客户端里填接入信息：
   - Base URL（OpenAI）：手机本机填 `http://127.0.0.1:8790/v1`；局域网内其它设备填首页复制的局域网地址（需先在「接入设置」里打开「同时监听局域网」）。
   - Base URL（Anthropic）：`http://127.0.0.1:8790`（不带 `/v1`，Claude Code 会自己拼 `/v1/messages`）。
   - 模型：可填 `供应商/模型`（如 `autoglm/glm-4.6`、`chatglm/glm-4-plus`），也可直接填模型名由网关推导。
   - API Key：默认开启「无 Key 调用」，随意填；关掉后需与「接入设置」里的 Key 一致。

> **版本升级注意**：本分支版本号 = 上游 +1，可能低于你已装的其它二改版本。若提示 `INSTALL_FAILED_VERSION_DOWNGRADE`，先卸载旧包再装。

---

## 自动跟随上游

本仓库自带 `.github/workflows/auto-sync-upstream.yml`，**不需要你手动 merge 上游**。

```
每 6 小时 / 手动触发
        ▼
   ① sync job
      拉上游 → 三方合并 → 校验 19 条 mod-guards → 计算版本号
      退出码：0 已最新 / 10 合并成功 / 78 需人工（冲突或补丁丢失）
        ▼
   ② build job（合并成功时）
      签名 → detekt → 单元测试 → 打包 → 发 Release
```

**关键设计**

| 项 | 说明 |
|---|---|
| **工具链锁版本** | `build.gradle.kts` / `gradle.properties` / `gradle/wrapper/*` 等 11 个文件**永远保留本分支版本**，不跟上游升级 |
| **守卫校验** | `tools/mod-guards.txt` 19 条特征串，合并后逐个核对，任何一条丢失即回滚并发 Issue |
| **回滚干净** | 出现冲突或守卫丢失时，工作区恢复到合并前状态，不留半成品 |
| **版本号自动** | 从上游 `app/build.gradle.kts` 读 `versionName`/`versionCode`，各 +1 |
| **Release 正文** | `git log -- app core`，过滤 `chore(release)` 与 merge 提交，只列应用相关变化 |

**手动触发**

```sh
gh workflow run auto-sync-upstream.yml -f dry_run=true
```

`dry_run=true` 只同步不构建，用来先看有没有冲突。

**本地跑同一套逻辑**

```sh
bash tools/ci-sync-upstream.sh
```

---

## 架构总览

一次请求的生命周期：

```
客户端 (Cline / Cherry Studio / Claude Code / curl)
   │  POST /v1/chat/completions  {model: "...", ...}
   │  或 POST /v1/messages       {model: "...", ...}   ← Anthropic 形状
   ▼
GatewayHttpServer   ① 鉴权   ② 解析 model（Anthropic 入口先翻译成 OpenAI 形状）
   ▼
GatewayEngine       ③ 解析候选路由
                    ④ 选号 → 预刷新凭证 → 发起上游请求
                    ⑤ 失败则换号，或跨供应商 Failover
   ▼
Provider 实现       上游私有协议 ⇄ OpenAI 协议（双向转换封死在 Provider 内）
   ▼
上游 API
```

**路由策略**：带 `供应商/模型` 前缀 = 用户显式指定，只在该供应商内换号；不带前缀 = 交给网关推导，才启用跨供应商 Failover。这条边界很重要——自动转移是功能，但它不能替用户花钱。

**核心不变量**：Provider 的边界是「OpenAI 进、OpenAI 出」。上游私有协议的双向转换全部封死在 Provider 内部，网关层对供应商差异零感知。Anthropic 协议只在入口/出口各做一次翻译，不穿透到 Provider。任何把上游协议细节泄漏到网关层的改动，都会破坏这套可维护性根基。

---

## 模块结构

| 模块 | 类型 | 职责 |
|---|---|---|
| `:core` | 纯 JVM，无 Android 依赖 | 网关引擎、HTTP 服务、账号池、路由与 Failover、各供应商协议实现、协议转换（`protocol/`）、存储抽象。可独立单测 |
| `:cli` | 纯 JVM 命令行 | 在本机直接起网关做端到端联调，或不打开 App 也能导入账号 / 签到 / 查用量与日志；可执行名 `aigw` |
| `:app` | Android 应用 | Compose UI、前台服务与保活、加密存储实现、控制中心磁贴 |

供应商协议实现位于 `core/src/main/kotlin/dev/aigw/core/provider/`，每家的私有协议细节都封装在各自子包内（`trae/`、`codebuddy/`、`antigravity/`、`chatgpt/`、`loomy/`、`raccoon/`、`autoglm/`、`chatglm/`、`dumate/`、`custom/`），互不干扰。

协议转换层位于 `core/src/main/kotlin/dev/aigw/core/protocol/`：`OpenAiGemini.kt`（OpenAI ⇄ Gemini）、`AnthropicProtocol.kt`（Anthropic ⇄ OpenAI）。

---

## 数据存储

账号凭证与状态**按账号独立存储**：

```
account/<供应商>/<账号>            # 账号凭证（ProviderAccount.toJson）
pool/state/<供应商>/<账号>.json    # 账号状态（额度 / 启用 / 禁用 / 原因）
settings/...                      # 网关、代理、安全、供应商与自定义供应商等设置
logs/calls/<id>                   # 调用记录，每条一个键
logs/requests.json                # 运行日志（环形缓冲）
usage/...                         # 用量统计
```

- **一个账号一个状态文件**：改某个账号（刷新额度、启用/停用）只重写它自己的键，不整包重写该供应商的全部账号。
- **落盘在锁外**：内存状态在写锁内更新后，落盘移到锁外执行；同账号落盘用一把独立串行锁，避免「持写锁做整文件加密提交」把选号读全堵住。
- Android 端凭证经 Android Keystore 加密后存入应用私有目录；命令行端（`aigw`）以明文存于 `--data` 目录（默认 `~/.aigw`）。

---

## 构建与发版

本地构建（产出 debug 包，可与线上 release 包同机共存）：

```sh
./gradlew :app:assembleDebug --console=plain   # 出 debug 包
./gradlew :core:test --console=plain           # 单元测试（:core 是唯一有测试的模块）
./gradlew :cli:installDist --console=plain     # 命令行工具，产出 cli/build/install/aigw/bin/aigw
```

- 产物：`app/build/outputs/apk/debug/app-debug.apk`（包名 `dev.aigw.app.debug`）。
- **本地不产 release 包**：release 仅由云端 GitHub Actions 构建并发版。
- **版本号不要手工改**：由 CI 从上游版本号 +1 计算。本地构建用当前 `app/build.gradle.kts` 里的值。

命令行联调（`:cli`，无需 Android 设备）：

```sh
./gradlew :cli:run --args="serve" --console=plain       # 起网关（默认端口 8790，数据目录 ~/.aigw）
./gradlew :cli:run --args="providers" --console=plain   # 列出供应商
```

`aigw` 子命令：`serve` / `providers` / `accounts` / `credits` / `checkin` / `login` / `browser-login` / `sms` / `models` / `usage` / `logs`。

---

## 接口与鉴权

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/v1/chat/completions` | OpenAI 对话补全，支持流式 SSE / 非流式 / 多模态 |
| `POST` | `/v1/messages` | Anthropic Messages，支持流式 SSE / 非流式 |
| `POST` | `/v1/messages/count_tokens` | Anthropic token 估算，按约 3 字符 1 token 估算 |
| `POST` | `/v1/images/generations` | 图片生成（OpenAI Images API） |
| `POST` | `/v1/audio/transcriptions` | 语音转文字（multipart 上传） |
| `GET` | `/v1/models` | 模型列表（并发拉取各供应商后聚合返回；按模型开放名单过滤） |
| `GET` | `/v1/credits` | 额度查询（别名 `/credits`；本机免 Key） |
| `GET` | `/healthz` | 健康检查，返回纯文本 `ok`（不带鉴权） |
| `GET` | `/authorize` | Trae 网页登录回调入口 |

鉴权支持三种方式，任一即可：`Authorization: Bearer <key>`、`x-goog-api-key: <key>`、查询参数 `?key=<key>`。可在设置中开启「无 Key 调用」。服务同时处理 `OPTIONS` 预检并返回 CORS 头。

默认只绑定 `127.0.0.1`；打开「接入设置」里的「同时监听局域网」后才会监听全部网卡。

```sh
# OpenAI 形状
curl http://127.0.0.1:8790/v1/chat/completions \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <key>" \
  -d '{"model":"autoglm/glm-4.6","stream":true,"messages":[{"role":"user","content":"你好"}]}'

# Anthropic 形状（Claude Code 用的就是这个）
curl http://127.0.0.1:8790/v1/messages \
  -H "Content-Type: application/json" \
  -H "x-api-key: <key>" \
  -H "anthropic-version: 2023-06-01" \
  -d '{"model":"chatglm/glm-4-plus","max_tokens":1024,"messages":[{"role":"user","content":"你好"}]}'
```

---

## 供应商

| id | 供应商 | 上游 | 备注 |
|---|---|---|---|
| `trae` | Trae（字节） | SOLO 私有通道 | 协议转换最重 |
| `codebuddy` | WorkBuddy / CodeBuddy（腾讯） | 国内 `copilot.tencent.com` / 国际 `workbuddy.ai` | 上游拒绝非流式，强制 `stream:true` |
| `antigravity` | Antigravity（Google） | `cloudcode-pa.googleapis.com` | Gemini 风格协议 |
| `chatgpt` | ChatGPT（OpenAI） | `chatgpt.com/backend-api/codex` | Codex 订阅额度；OAuth 回调端口 1455 |
| `loomy` | Loomy（讯飞） | `xfinfr.com` | 鉴权失败为 HTTP 200 + 业务码；登录用短信验证码 |
| `raccoon` | 小浣熊（商汤） | `xiaohuanxiong.com` | OpenAI 结构被包在 `data` 里 |
| `autoglm` | **AutoGLM（智谱）** | `autoglm-acceleration-api.zhipuai.cn` | **本分支新增**。认证头是 `X-Authorization`；登录需复用同一 `device_id` |
| `chatglm` | **ChatGLM / 智谱清言** | `agentmore.chatglm.cn` | **本分支新增**。走 `agent-api/conversation` + `agent-api/stream`，非标准 chat/completions |
| `dumate` | **Dumate（百度搭子）** | `dumate-svc.baidu.com/gateway/apis/v1` | **本分支新增**。仅实现网页链路：浏览器登录 `www.dumate.cn` 拿 cookie，换 1 小时 bearer token 后直连云端 OpenAI 兼容网关。桌面客户端那条路依赖 Windows 路径，Android 上无法复现 |
| `github-copilot` | GitHub Copilot | `api.githubcopilot.com` | 设备码登录 → 换短期 token；**必须带官方客户端指纹头**，否则 401 |
| `custom:<key>` | 自定义 | 用户填写的 baseUrl | 任意 OpenAI 兼容中转站，一个 key = 一个账号 |

---

## 贡献

1. **新增供应商**只需三步：`core` 实现 `Provider` 接口 → 在 `ProviderBootstrap` 登记 → `app` 实现并登记 `ProviderUi`。框架自动获得账号池、Failover、代理分流、日志与保活。
2. 保持 `:core:test` 全绿。
3. **合并上游时的纪律**：以上游为基线，只搬「功能」（新增文件 + 注册点 + 自己写的 UI），**不要整段贴旧分支的实现**。旧分支里有些 bug 上游已修，整段覆盖会把它们带回来。
4. 改动工具链文件（`build.gradle.kts` / `gradle.properties` / `gradle/wrapper/*`）会破坏自动同步，请先读 [自动跟随上游](#自动跟随上游)。

---

## 许可

本项目基于 [MIT License](./LICENSE)，可自由使用、修改与分发，须保留版权声明。原始版权归上游作者 [xibai6668](https://github.com/xibai6668/Android-AI-gateway) 所有，本分支的修改同样置于该许可之下。

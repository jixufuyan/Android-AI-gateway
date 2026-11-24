# 上游同步手册：一条命令把二改跟到最新源码

适用对象：本仓库的二改分支（`ide`）。上游 = `https://github.com/xibai6668/Android-AI-gateway`（默认远端名 `upstream`，分支 `main`）。

> **当前二改基线**：2.39.3 / versionCode 242（工具链 AGP 8.13.0 / Kotlin 2.1.0 / Gradle 9.0.0 / compileSdk 36 / haze 1.6.10 / detekt 1.23.8）。
> 每次同步前先确认这个基线没被上游悄悄抬高——脚本第 2 步会拦住。

---

## 一、日常用法（就这一条）

```bash
git checkout ide
tools/sync-upstream.sh
```

跑完看最后一段：**`单测 + debug 包通过`** 就说明这次同步是好的；实机装一遍没问题再 `git push origin ide`。

| 命令 | 作用 |
|---|---|
| `tools/sync-upstream.sh` | 全流程：拉上游 → 合并 → 工具链适配 → 隐患扫描 → 提交 → 单测 + 打 debug 包 |
| `tools/sync-upstream.sh --no-build` | 只到提交为止（**手工解完冲突后用这条接着跑**） |
| `tools/sync-upstream.sh --dry-run` | 演练：试合并 + 打印会做什么，然后自动回滚，不产生任何提交 |

首次使用只需配一次远端：

```bash
git remote add upstream https://github.com/xibai6668/Android-AI-gateway.git
```

分支约定：`main` 只跟上游（用来看改动），`ide` 是你的二改，所有同步都往 `ide` 里合。**不要**把二改直接写在 `main` 上，否则以后没法对照。

---

## 二、脚本按顺序做了 8 件事

```
0 环境检查     工作区必须干净；若上次合并没收尾（MERGE_HEAD 还在）就接着处理，不重跑合并
1 拉上游       git fetch upstream main；把上游的 app/core/cli 构建文件导出到 .sync-upstream/ 备比对
2 三方合并     git merge --no-commit --no-ff upstream/main
   ├─ 工具链文件冲突      → 自动保留本地版（第三节）
   ├─ detekt.yml 冲突     → 自动取上游版，第 3 步再把语法退回 1.23
   └─ 其它文件冲突        → 停下来列出清单，等你手工合并（脚本不替你决定业务逻辑）
3 工具链适配   detekt 属性名回退；上游新增依赖按字面量补进本地构建脚本；不兼容依赖只提示不补
4 隐患扫描     ① haze 2.x API 残留 ② 函数自递归 ③ 二改补丁存活核对（tools/mod-guards.txt）
               ——这三类都能编译、跑起来才炸，只能靠特征串查
5 提交         merge 提交，消息带上游短哈希
6 构建验证     ./gradlew :core:test :app:assembleDebug
7 摘要         上游版本 / 合并前后 SHA / 改动统计 / 推送与回滚命令
```

---

## 三、工具链文件清单（冲突一律保留本地）

```
build.gradle.kts            app/build.gradle.kts        core/build.gradle.kts
cli/build.gradle.kts        gradle.properties           settings.gradle.kts
gradle/libs.versions.toml   gradle/wrapper/gradle-wrapper.properties
gradle/wrapper/gradle-wrapper.jar                       gradlew / gradlew.bat
```

**为什么必须保留本地**：上游把工具链抬到了 AGP 9.5.1 / Kotlin 2.4.21 / Gradle 9.9.1 / compileSdk 37 / 写死 build-tools 36.0.0 / haze 2.1.0 / detekt 2.0，而手机端 Android Code Studio 只能跑 **AGP 8.13.0 / Kotlin 2.1.0 / Gradle 9.0.0 / compileSdk 36 / 不写死 build-tools / haze 1.6.10 / detekt 1.23.8 / 版本目录 libs.versions.toml**。跟上游走的话，项目在 IDE 里连初始化都过不去（不是编译报错，是 sync 阶段直接失败）。

要改这条清单：编辑 `tools/sync-upstream.sh` 里的 `TOOLCHAIN_FILES`。

---

## 四、代码侧适配：脚本能自动的 / 必须你手动确认的

| 项目 | 处理 | 说明 |
|---|---|---|
| detekt 配置属性名 | **自动** | `allowedLines/allowedComplexity/allowedDepth/allowedConditions` → `threshold`；`UnusedPrivateFunction`+`UnusedPrivateProperty` → `UnusedPrivateMember`（幂等，已是 1.23 语法就不动） |
| 上游新增第三方依赖 | **自动** | 以字面量补进 `app/core/cli` 的 `build.gradle.kts`，带 `// [sync-upstream]` 注释；已在版本目录里声明过的不重复补 |
| 新工具链专属依赖 | **只提示** | `SKIP_DEPS` 名单（当前：`dev.chrisbanes.haze:haze-blur`）——这些是 Kotlin 2.4 / haze 2.x 专属，补进来要么编不过要么和 1.x 打架 |
| haze 2.x API | **只提示** | 见 4.1 |
| 函数自递归 | **只提示** | 见 4.2 |
| 二改补丁是否还在 | **只提示** | 见 4.3 |

### 4.1 haze 2.x → 1.x（脚本会指出文件行号，改动要手做）

```kotlin
// 上游 2.x
val style = remember { HazeBlurStyle { backgroundColor(Color.Transparent); blurRadius(40.dp) } }
Modifier.hazeBlur(input = HazeInput.Sources(hazeState), style = style)

// 本地 1.6.10
val style = HazeStyle(
    backgroundColor = Color.Transparent,
    tints = listOf(HazeTint(color = surfaceColor.copy(alpha = tintAlpha))),
    blurRadius = 40.dp,
    noiseFactor = 0.04f,
)
Modifier.hazeEffect(state = hazeState, style = style)
```

常见位置：`app/src/main/kotlin/dev/aigw/app/MainActivity.kt`（悬浮玻璃底栏）。

### 4.2 函数自递归（历史上真崩过一次）

批量替换最容易踩：把 `Switch(` 全换成 `AppSwitch(` 时，`AppSwitch` 自己的函数体也被换了，变成自己调自己——编译完全正常，一点「设置」就 `StackOverflowError`，日志里全是 `v4.q3.b`。

修法：包装函数内部**写全限定名**调用真实现。

```kotlin
@Composable
fun AppSwitch(...) {
    // 必须写全限定名：裸调用 Switch( 在批量替换后会变成 AppSwitch(，自己调自己
    androidx.compose.material3.Switch(checked = checked, onCheckedChange = onCheckedChange, ...)
}
```

**这条规则对所有「批量替换出来的包装组件」都成立**：`AppSwitch` / `AppTextField` 内部一律写全限定名。

脚本每次跑都会扫 `app/src/main`（`tools/recursion_scan.py`）：块体做花括号配对、表达式体按语句边界取、import 同名符号（如 `Icon`）自动排除。`core` 里的 `walk()` / `write()` 是**刻意的递归**，所以扫描范围只放 `app`。

### 4.2b 主题色别拿 `outline` 当边框（同一个坑踩过两次）

本项目亮色的 `outline` = `HairlineBorder` `#ECECF0`，深色是 `#2A2A2A`——都是「几乎等于背景」的发丝线色。Material3 拿它当**未激活元素的边框**，于是：

| 组件 | 症状 | 对比度 | 修法 |
|---|---|---|---|
| `Switch` 关闭态圆点 | 圆点糊在轨道上 | — | `AppSwitch` + `appSwitchColors()` |
| `OutlinedTextField` 未聚焦边框 | **对话框里完全看不见边框** | 对白卡 1.18:1、对对话框底 1.04:1 | `AppTextField` + `appTextFieldColors()` + `InputOutline` |

描边属于「非文本元素」，WCAG 1.4.11 要求 ≥ 3:1。现在用的 `InputOutline` = `#6E737C`：对白卡 **4.77:1**、对对话框底 **3.89:1**，两头都达标，又比正文浅，不喧宾夺主。

> 以后凡是「默认取 `colorScheme.outline`」的组件（`OutlinedTextField`、`OutlinedButton`、`OutlinedCard` 等），**先算一遍对比度再决定用不用它**。

### 4.3 二改补丁存活核对（`tools/mod-guards.txt`）

> 这是最容易被忽略的一类回归：**上游重构了同一段代码 → 三方合并把你的修补安静地冲掉 → 编译过、启动正常 → 只有真跑到那个场景才发现又不工作了。**

清单格式（一行一条）：

```
路径|必须存在的特征串|说明
```

- 路径不存在 → 报「补丁丢失」
- 路径存在但找不到特征串 → 报「补丁可能被覆盖」

当前覆盖的锚点（19 条，按功能归 13 行）：

| 补丁 | 核对位置 | 特征串 |
|---|---|---|
| 小浣熊错误分类器 | `RaccoonErrors.kt` | `object RaccoonErrors` |
| `classify()` 走分类器 | `RaccoonProvider.kt` | `RaccoonErrors.classifyHttp` |
| HTTP 200 业务码分支 | `RaccoonProvider.kt` | `RaccoonErrors.classifyBusiness(bizCode` |
| 流头预读 | `RaccoonProvider.kt` | `peekHead(stream)` |
| 模型名归一化 | `ModelNameKey.kt` | `object ModelNameKey` |
| 候选匹配走归一化 | `GatewayEngine.kt` | `ModelNameKey.matchTier` |
| 通用模型名推导顺序 | `GatewayEngine.kt` | `MODEL_INFER_ORDER` |
| 小浣熊前缀别名 | `GatewayEngine.kt` | `"raccoon", "xiaohuanxiong", "xhx", "sn", "sensenova"` |
| 统一开关组件 | `Components.kt` | `fun AppSwitch(` / `androidx.compose.material3.Switch(` |
| 统一输入框 | `Components.kt` | `fun AppTextField(` / `androidx.compose.material3.OutlinedTextField(` / `fun appTextFieldColors` |
| 输入框描边色 | `Theme.kt` | `val InputOutline` |
| 二改四家供应商注册 | `ProviderBootstrap.kt` | `DumateProvider(` / `AutoglmProvider(` / `ChatglmProvider(` / `RaccoonProvider(` |
| 首页局域网指标 | `GatewayEngine.kt` | `fun lanUrls` |

**给二改加新的关键修复时，就在 `tools/mod-guards.txt` 里加一行。** 上游确实重写了那段、你确认新写法等价时，**改特征串，别删行**——删了就再也没人替你看着它了。

---

## 五、冲突处理手册

| 冲突文件 | 怎么办 |
|---|---|
| 第三节清单里的构建文件 | 脚本自动保留本地，不用管 |
| `config/detekt/detekt.yml` | 脚本自动取上游 + 回退语法，不用管 |
| `README.md` / `ARCHITECTURE.md` | 取上游（文档跟上游走最省心） |
| `core/**`、`app/src/**` 业务代码 | **手工**：上游新功能 + 你的二改通常都要，冲突块就是两边动了同一段 |
| `SYNC-UPSTREAM.md` / `tools/**` | **手工，一律保留本地**：这是二改自己的工具，上游没有 |

手工合并流程：

```bash
# 1. 按脚本列出的清单逐个文件处理，搜 <<<<<<< / ======= / >>>>>>>
vim <冲突文件>
# 2. 标记行删干净、内容两边都保留
git add -A
# 3. 接着跑（不会从头再来，MERGE_HEAD 还在）
tools/sync-upstream.sh --no-build
```

---

## 六、验证与回滚

```bash
# 脚本第 6 步已经跑过；要更彻底就：
./gradlew :core:test :core:detekt :app:assembleDebug :app:assembleRelease

# 回滚到本次同步之前（脚本失败时会把这条命令原样打印出来）
git reset --hard <合并前 SHA>
```

调试期想装机验证，又不想覆盖正式版：

```bash
./gradlew :app:assembleDebug     # 包名 dev.aigw.app.debug，与正式版可共存
```

release 包签名（sandbox 里 aarch64 可用）：

```bash
/tmp/sdkhome/build-tools/36.0.0/apksigner sign --ks ~/.android/debug.keystore \
  --ks-pass pass:android --key-pass pass:android --ks-key-alias androiddebugkey \
  app/build/outputs/apk/release/app-release-unsigned.apk
```

---

## 七、常见卡点

- **`git fetch` 很慢/失败**：GitHub 直连不稳，挂代理或 `git config --global http.proxy ...`。
- **卡在下载 Gradle 9.0.0**：首次要下 ~130MB，之后缓存在 `~/.gradle/wrapper/dists`。
- **提示 build-tools 没装**：`app/build.gradle.kts` 里刻意**不写** `buildToolsVersion`，让 AGP 自己挑已装的那套，别手贱加回去。
- **崩溃日志全是 `a.b.c`**：R8 混淆后的正常现象，用同版本 `app/build/outputs/mapping/release/mapping.txt` 还原：`tools/retrace.sh crash.log`。**每次发版都要把 mapping.txt 跟 APK 一起归档**，丢了就永远还原不了。
- **能不能直接拿上游覆盖？** 不行。二改里有四家供应商（百度搭子 / 智谱 AutoGLM / 智谱清言 / 小浣熊网页登录）、应用内网页登录、Tab 隐藏、首页局域网指标、卡片化 UI、统一开关配色、模型名跨供应商归一化，覆盖即丢；只有三方合并能同时保住两边。
- **`--dry-run` 之后发现 `upstream` 指向了本地路径**：之前拿本仓当假上游测脚本会改 `upstream` 的 URL，测完记得改回来：
  `git remote set-url upstream https://github.com/xibai6668/Android-AI-gateway.git`

---

## 八、脚本自身的坑（改脚本时注意）

- 判断是否有冲突**不能**写 `git ls-files -u | grep -q .`：脚本开了 `pipefail`，`grep -q` 提前退出会让管道返回 141，条件恒假。已改成 `[ -n "$(git ls-files -u -- 文件)" ]`。
- 演练模式（`--dry-run`）也要照样解决工具链冲突，只是最后整单 `git merge --abort`，这样「剩余冲突」列表才是真的需要你处理的那些。
- 补依赖时 `awk` 找的是文件**最后一个顶格 `}`**，所以别在 `dependencies {}` 之后再写顶格花括号的块。
- 补丁核对用 `grep -F`（固定串，不当正则）：特征串里有 `(`、`"`、`|` 这类字符不会炸；`|` 作为字段分隔符时**特征串里不能再出现 `|`**，真要匹配它就拆成两条。
- 核对清单里的路径是**相对仓库根**的（脚本已 `cd` 到根），别写绝对路径。

---

## 九、实测记录

### 9.1 上游更新的四种场景（第一轮）

| 场景 | 上游改动 | 脚本表现 |
|---|---|---|
| 纯文档更新 | 只改 `README.md` | 无冲突直接合，`单测 + debug 包通过` |
| 依赖 + 配置 | 新增 `okio` 依赖、`detekt.yml` 用 2.0 属性名、改 `GatewayEngine.kt` | `app/build.gradle.kts` 冲突保留本地并补进 `okio`（带缩进）；`haze-blur` 被跳过并提示人工决定；`detekt.yml` 取上游后自动退回 `threshold` / `UnusedPrivateMember`；构建通过 |
| 工具链冲突 | 改 `app/build.gradle.kts` 的 compileSdk / 版本号 | 一律保留本地版，不跟上游抬工具链 |
| 业务代码冲突 | 改了二改也改过的 `lanUrls()` | 停下来列出冲突文件 → 手工合并 → `git add -A && tools/sync-upstream.sh --no-build` 续跑，正常提交 |

### 9.2 补丁存活核对（第二轮，2.39.2 后新增）

造了一个「上游把小浣熊业务码分支改回 `ErrorKind.CLIENT`」的场景：

| 步骤 | 结果 |
|---|---|
| 全清单对照（当前仓库） | 锚点全部命中，无输出 |
| 删掉 `ModelNameKey.kt` + 把 `peekHead(stream)` 改成别的名字 | 分别报「补丁丢失」和「补丁可能被覆盖」，且**指出具体是哪条功能** |
| 真跑 `sync-upstream.sh`（上游覆盖了业务码分支） | 三方合并**无冲突**、构建照过——正是这类回归最危险的地方，只能靠第 4 步的核对抓 |

> 结论：上游重构同一段代码时 `git merge` 不会冲突，**补丁会静默消失**。这条核对是唯一防线。

### 9.3 主题色对比度（第三轮，2.39.3）

「并发与容错 → 修改并发参数」弹窗里，输入框未选中时边框看不见。实测对比度：

| 颜色 | 用途 | 对白卡 `#FFFFFF` | 对对话框底 `#ECE6F0` |
|---|---|---|---|
| `#ECECF0` | 旧（`colorScheme.outline`） | 1.18:1 ❌ | **1.04:1 ❌ 等于没有** |
| `#8A8F99` | 第一版方案（`onSurfaceVariant`） | 3.25:1 | 2.65:1 ❌ 仍不达标 |
| **`#6E737C`** | **现在（`InputOutline`）** | **4.77:1 ✓** | **3.89:1 ✓** |

深色主题同样有问题：`outline` 是 `#2A2A2A`，对深色对话框底 `#2B2930` 只有 **1.00:1**——完全消失。所以配色函数里按 `background.luminance()` 判断明暗，深色走 `onSurfaceVariant`（4.42:1）。

替换范围：**68 处 `OutlinedTextField` 全量换成 `AppTextField`**（22 个文件），不只是出事的那一个弹窗——同一个坑不可能只在一个页面踩到。

跑完后 `git log --oneline` 会多一条 `merge: 同步上游 <短哈希>`，随时能用摘要里给的 SHA 回滚。

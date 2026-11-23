# 上游同步手册：一条命令把二改跟到最新源码

适用对象：本仓库的二改分支（`ide`）。上游 = `https://github.com/xibai6668/Android-AI-gateway`（默认远端名 `upstream`，分支 `main`）。

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

## 二、脚本按顺序做了 7 件事

```
0 环境检查     工作区必须干净；若上次合并没收尾（MERGE_HEAD 还在）就接着处理，不重跑合并
1 拉上游       git fetch upstream main；把上游的 app/core/cli 构建文件导出到 .sync-upstream/ 备比对
2 三方合并     git merge --no-commit --no-ff upstream/main
   ├─ 工具链文件冲突      → 自动保留本地版（第三节）
   ├─ detekt.yml 冲突     → 自动取上游版，第 3 步再把语法退回 1.23
   └─ 其它文件冲突        → 停下来列出清单，等你手工合并（脚本不替你决定业务逻辑）
3 工具链适配   detekt 属性名回退；上游新增依赖按字面量补进本地构建脚本；不兼容依赖只提示不补
4 隐患扫描     haze 2.x API 残留、函数自递归（这两类能编译，跑起来才炸）
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
| haze 2.x API | **只提示** | 见下 |
| 函数自递归 | **只提示** | 见下 |

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

脚本每次跑都会扫 `app/src/main`（`tools/recursion_scan.py`）：块体做花括号配对、表达式体按语句边界取、import 同名符号（如 `Icon`）自动排除。`core` 里的 `walk()` / `write()` 是**刻意的递归**，所以扫描范围只放 `app`。

---

## 五、冲突处理手册

| 冲突文件 | 怎么办 |
|---|---|
| 第三节清单里的构建文件 | 脚本自动保留本地，不用管 |
| `config/detekt/detekt.yml` | 脚本自动取上游 + 回退语法，不用管 |
| `README.md` / `ARCHITECTURE.md` | 取上游（文档跟上游走最省心） |
| `core/**`、`app/src/**` 业务代码 | **手工**：上游新功能 + 你的二改通常都要，冲突块就是两边动了同一段 |

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

---

## 七、常见卡点

- **`git fetch` 很慢/失败**：GitHub 直连不稳，挂代理或 `git config --global http.proxy ...`。
- **卡在下载 Gradle 9.0.0**：首次要下 ~130MB，之后缓存在 `~/.gradle/wrapper/dists`。
- **提示 build-tools 没装**：`app/build.gradle.kts` 里刻意**不写** `buildToolsVersion`，让 AGP 自己挑已装的那套，别手贱加回去。
- **崩溃日志全是 `a.b.c`**：R8 混淆后的正常现象，用同版本 `app/build/outputs/mapping/release/mapping.txt` 还原：`tools/retrace.sh crash.log`。**每次发版都要把 mapping.txt 跟 APK 一起归档**，丢了就永远还原不了。
- **能不能直接拿上游覆盖？** 不行。二改里有三家供应商（百度搭子 / 智谱 AutoGLM / 智谱清言）、应用内网页登录、Tab 隐藏、首页局域网指标、卡片化 UI、统一开关配色，覆盖即丢；只有三方合并能同时保住两边。

---

## 八、脚本自身的坑（改脚本时注意）

- 判断是否有冲突**不能**写 `git ls-files -u | grep -q .`：脚本开了 `pipefail`，`grep -q` 提前退出会让管道返回 141，条件恒假。已改成 `[ -n "$(git ls-files -u -- 文件)" ]`。
- 演练模式（`--dry-run`）也要照样解决工具链冲突，只是最后整单 `git merge --abort`，这样「剩余冲突」列表才是真的需要你处理的那些。
- 补依赖时 `awk` 找的是文件**最后一个顶格 `}`**，所以别在 `dependencies {}` 之后再写顶格花括号的块。

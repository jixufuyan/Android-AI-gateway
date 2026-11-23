# 上游同步手册（SYNC-UPSTREAM）

> 目标：上游 `xibai6668/Android-AI-gateway` 一更新，你这边**一条命令**就能把二改合并到最新源码，且不用重新踩一遍工具链的坑。

---

## 一、一次性配置（只做一次）

```bash
cd 你的仓库目录

# 1. 加一个上游远端（origin 是你自己的 fork）
git remote add upstream https://github.com/xibai6668/Android-AI-gateway.git

# 2. 你的二改放在独立分支上，固定叫 ide（脚本默认就是它）
git branch -a                    # 确认当前在 ide 分支
git push -u origin ide           # 推到自己的 fork 备份
```

分支模型（重要）：

| 分支 | 作用 |
|---|---|
| `main` | 上游原样，只读，任何时候都能 `git checkout main && git pull upstream main` 看上游改了啥 |
| `ide`  | **你的二改**，所有改动都在这里；每次上游更新都往这个分支合并 |

> 只用一个分支也行（二改直接在 main 上），但那样就没法对照上游了，不推荐。

---

## 二、每次上游更新（日常）

```bash
git checkout ide
tools/sync-upstream.sh
```

跑完看最后几行：`单测 + debug 包通过` 就说明合并成功，实机验证没问题再 `git push origin ide`。

三个开关：

| 命令 | 用途 |
|---|---|
| `tools/sync-upstream.sh` | 全流程：合并 → 适配 → 提交 → 单测 + 打 debug 包 |
| `tools/sync-upstream.sh --no-build` | 只要合并和提交（冲突处理完后接着跑用这个） |
| `tools/sync-upstream.sh --dry-run` | 演练：试合并 + 打印会改什么，然后自动回滚，不动任何提交 |

---

## 三、脚本到底做了什么

```
0/7 环境检查      工作区必须干净；检测是否有上次没合完的 MERGE_HEAD
1/7 拉取上游      git fetch upstream main，并把上游的构建文件导出到 .sync-upstream/
2/7 三方合并      git merge --no-commit --no-ff upstream/main
      ├─ 冲突文件在「工具链清单」里 → 自动保留本地版（见第四节）
      └─ 其它冲突 → 停下来列出文件，等你手工合并（脚本不替你决定业务逻辑）
3/7 工具链适配    detekt 配置属性名回退 1.23 语法；上游新增依赖按字面量补进本地构建脚本
4/7 隐患扫描      haze 2.x API 残留 / 函数自递归（这两类能编译，跑起来才炸）
5/7 提交          merge 提交（消息带上游版本号）
6/7 构建验证      ./gradlew :core:test :app:assembleDebug
7/7 摘要          改动统计 + 推送/回滚命令
```

### 工具链清单（这些文件永远保留你的版本）

```
build.gradle.kts
app/build.gradle.kts
core/build.gradle.kts
cli/build.gradle.kts
gradle.properties
settings.gradle.kts
gradle/libs.versions.toml
gradle/wrapper/gradle-wrapper.properties
gradle/wrapper/gradle-wrapper.jar
gradlew
gradlew.bat
```

**为什么**：上游把工具链抬到了 AGP 9.5.1 / Kotlin 2.4.21 / Gradle 9.9.1 / compileSdk 37 / build-tools 36.0.0 / haze 2.1.0 / detekt 2.0，而手机端的 Android Code Studio 只能跑 **AGP 8.13.0 / Kotlin 2.1.0 / Gradle 9.0.0 / compileSdk 36 / 不写死 build-tools / haze 1.6.10 / detekt 1.23.8**。这些文件一旦跟上游走，项目在 IDE 里连初始化都过不去。

---

## 四、代码侧的四条固定适配（脚本能自动做的已经做了，剩下的要手改）

### 1. haze 2.x → 1.x（**必须手改**，脚本只报警）

上游把模糊能力拆成了 `haze` + `haze-blur` 两个 artifact，API 也换了：

```kotlin
// 上游 2.x
val style = remember { HazeBlurStyle { backgroundColor(Color.Transparent); blurRadius(40.dp) } }
Modifier.hazeBlur(input = HazeInput.Sources(hazeState), style = style)

// 本地 1.x（1.6.10）
val style = HazeStyle(
    backgroundColor = Color.Transparent,
    tints = listOf(HazeTint(color = surfaceColor.copy(alpha = tintAlpha))),
    blurRadius = 40.dp,
    noiseFactor = 0.04f,
)
Modifier.hazeEffect(state = hazeState, style = style)
```

涉及文件：`app/src/main/kotlin/dev/aigw/app/MainActivity.kt`（悬浮底栏）。

### 2. detekt 配置语法（**脚本自动**）

`config/detekt/detekt.yml` 里 2.0 的属性名在 1.23 里算「拼错」，会直接失败：

| detekt 2.0 | detekt 1.23 |
|---|---|
| `allowedLines` / `allowedComplexity` / `allowedDepth` / `allowedConditions` | `threshold` |
| `UnusedPrivateFunction` + `UnusedPrivateProperty` | `UnusedPrivateMember` |

### 3. 上游新增的第三方依赖（**脚本自动**）

脚本读上游 `app/core/cli` 的 `build.gradle.kts`，把本地没有的 `implementation("g:a:v")` 以字面量形式补到本地同名文件的 `dependencies` 末尾，带 `// [sync-upstream]` 注释，方便下次 review。

### 4. 函数自递归（**脚本只报警**）

用批量替换/重构时最容易踩：一个 composable 里调用自己，编译能过，一进组合就 `StackOverflowError`。日志会被 R8 混淆成 `v4.q3.b`，要靠 `mapping.txt` + retrace 才能看出是哪个函数。

> 真实事故：把全仓 `Switch(` 批量换成 `AppSwitch(` 时，`AppSwitch` 自己的函数体也被换了，变成自己调自己，点「设置」直接崩。修法是函数体里写全限定名 `androidx.compose.material3.Switch(...)`。

---

## 五、冲突处理手册（按文件类型）

| 冲突文件 | 处理 |
|---|---|
| `build.gradle.kts` / `gradle.properties` / `settings.gradle.kts` / wrapper | 取本地（脚本自动） |
| `app/build.gradle.kts` / `core/build.gradle.kts` | 取本地，然后看脚本第 3 步有没有补进上游新增依赖 |
| `config/detekt/detekt.yml` | 取本地，脚本会自动回退语法 |
| `README.md` / `ARCHITECTURE.md` | 取上游（文档跟着上游走最省心） |
| **业务代码**（`core/**`、`app/src/**`） | 逐个看：上游新功能 + 你的二改**都要**；冲突块通常就是两边改了同一处 |

手工合并完之后：

```bash
git add -A
tools/sync-upstream.sh --no-build     # 接着跑适配 + 提交
```

---

## 六、验证与回滚

```bash
# 完整验证（脚本第 6 步已自动跑）
./gradlew :core:test :core:detekt :app:assembleDebug :app:assembleRelease

# 回滚到合并前（脚本失败时会打印这条命令）
git reset --hard <合并前的 commit>
```

发版包（release）需要 keystore，本地没有时只能出 debug 包：

```bash
./gradlew :app:assembleDebug      # 包名 dev.aigw.app.debug，可与正式版共存
```

---

## 七、常见问题

**Q：第一次跑 `git fetch upstream` 特别慢？**
GitHub 直连不稳，配个代理或者 `git config --global http.proxy` 再拉。

**Q：脚本报「没有名为 upstream 的远端」？**
按第一节加一次 `git remote add upstream ...`。

**Q：构建卡在下载 Gradle 9.0.0？**
首次会下 ~130MB 的发行版，网络差就多等；下完缓存在 `~/.gradle/wrapper/dists`。

**Q：`build-tools` 没装？**
`app/build.gradle.kts` 里刻意**不写** `buildToolsVersion`，让 AGP 自己挑已装的那套——所以别随手加回去。

**Q：合并后崩溃日志全是 `a.b.c`？**
R8 混淆后的正常现象。用该版本的 `app/build/outputs/mapping/release/mapping.txt` 还原：

```bash
tools/retrace.sh crash.log
```

**Q：能不能不合并，直接拿上游源码覆盖？**
不行。你的二改包含三家供应商（百度搭子 / 智谱 AutoGLM / 智谱清言）、应用内网页登录、Tab 隐藏、首页局域网指标、卡片化 UI 等，覆盖即丢。三方合并才是正解。

---

## 八、手动流程（不想跑脚本时）

```bash
git fetch upstream
git merge upstream/main                 # 冲突按第五节处理
# 工具链文件冲突时：
git checkout --ours -- app/build.gradle.kts core/build.gradle.kts build.gradle.kts \
    gradle.properties settings.gradle.kts gradle/libs.versions.toml \
    gradle/wrapper/gradle-wrapper.properties gradle/wrapper/gradle-wrapper.jar gradlew gradlew.bat
git add -A
./gradlew :core:test :app:assembleDebug
git commit -m "merge: 同步上游"
```

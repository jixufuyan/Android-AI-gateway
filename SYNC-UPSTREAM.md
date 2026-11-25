# 上游同步工具 3.0（手机可用）

把上游新版本合进你的二改分支，**一键跑完**，冲突自动解决。

---

## 一、先搞清楚「上游」是哪几个

这是上次失败的根因 —— 你只配了 `xibai6668` 一个远端，而**新供应商不在那儿**：

| 仓库 | 有什么 | 说明 |
|---|---|---|
| `xibai6668/Android-AI-gateway` | Trae / WorkBuddy / **Antigravity** / **ChatGPT** / Loomy / 小浣熊 / CodeBuddy | 原上游，`838fe76` |
| `alarmedvine/Android-AI-gateway` | 以上全部 + **newapi（New-API 面板自动签到，如 AgentRouter）** | 别人 fork 后加的，`14b259e` |

两个都基于同一个祖先，**合 alarmedvine 就等于把两边的更新都拿到**（它没有落后原上游）。
工具默认两个远端都拉，自动挑「分叉最少」的那个合。

> 上次你说的「新增了一个供应商」= `antigravity`（在原上游里）和 `newapi`（在 fork 里）。
> 你这版二改（2.39.3）**已经有 antigravity / chatgpt / autoglm / chatglm / dumate**，
> 真正缺的是 `newapi` 那套自动签到。

---

## 二、三种执行方式，按你手上的条件挑

### 方式 A：MT 管理器一键（最接近你要的）

```
Android-AI-gateway/
├── .git/                  ← 必须有；MT 里要开「显示隐藏文件」才看得到
├── app/
├── core/
├── run.sh                 ← 长按 → 执行
└── sync-upstream.sh
```

MT 管理器里**长按 `run.sh` → 执行**即可。它会自己找仓库、自己判断要不要构建。

⚠️ 两个前提（缺一就会报「没有 git」）：

1. **MT 自带的终端通常没有 git**。真要一键，用 Termux：`pkg install git`
2. **仓库别放 `/sdcard`**。Android 的 scoped storage 不让应用写 `.git`，git 会在
   `index.lock` 上失败。放到应用私有目录（如 `/data/local/tmp/gw` 或 Termux 家目录）再跑。

### 方式 B：Termux 里两行

```bash
pkg install git
cd /path/to/Android-AI-gateway && sh sync-upstream.sh
```

### 方式 C：丢给 AI 一键跑（**最推荐，也最省事**）

把这一整段连同仓库一起给 AI：

> 这是 Android-AI-gateway 的二改仓库，当前分支 `ide`（版本 2.39.3 / versionCode 242）。
> 目录里有 `sync-upstream.sh`。请：
> 1. 执行 `sh sync-upstream.sh --no-build`
> 2. 如果它列出「需要手工合并的文件」，逐个打开把 `<<<<<<<` / `=======` / `>>>>>>>`
>    之间的内容按「两边都要的合并保留」处理掉，删掉三行标记，然后重新执行
>    `sh sync-upstream.sh --no-build` 接着跑（不会从头再来）
> 3. 全部跑完后执行 `./gradlew :core:test :app:assembleDebug`
> 4. 把最终的 `git log --oneline -3`、构建结果、以及
>    `app/build/outputs/apk/debug/app-debug.apk` 给我
>
> 注意：不要动 `app/build.gradle.kts` 里的 `versionCode/versionName`（保持 242 / 2.39.3），
> 那是手机 IDE 的版本基线。构建失败时先跑 `git reset --hard` 回到合并前再说。

---

## 三、脚本到底自动做了什么

| 步骤 | 动作 |
|---|---|
| 1 | 检查环境（git、`.git` 可写、工作区干净、能否续跑上次合并） |
| 2 | **两个上游都 fetch**，按「分叉最少」挑目标 |
| 3 | 三方合并 |
| 4 | 冲突自动解决（见下表） |
| 5 | 工具链适配（detekt 语法回退 1.23 + 上游新增依赖按字面量补入） |
| 6 | 隐患扫描（haze 2.x API / 函数自递归 / **二改补丁存活**） |
| 7 | 提交 |
| 8 | `:core:test` + `:app:assembleDebug` |

### 冲突自动解决规则

| 文件 | 处理 | 为什么 |
|---|---|---|
| `app/build.gradle.kts` 等 11 个工具链文件 | **保留本地版** | 手机 IDE 只吃 AGP 8.13 / Kotlin 2.1 / Gradle 9.0；上游是 AGP 9.5 / Kotlin 2.4 |
| `app/src/main/AndroidManifest.xml` | **两边都保留 + 按 `android:name` 去重** | 两边各自新增 Activity，语义上都要；同名的只能留一份，否则 aapt2 报 duplicate |
| `config/detekt/detekt.yml` | 取上游内容 + 自动回退 1.23 语法 | 规则要跟上，属性名要退回 |
| 其它业务文件 | **不替你做决定**，列出来告诉你怎么合 | 业务逻辑冲突机器猜不准 |

### 二改补丁存活核对（这个最值钱）

上游重构同一段代码时，`git merge` **不会冲突，补丁会静默消失，编译还照样过**。
`tools/mod-guards.txt` 里 19 条特征串逐条核对，缺了就明确告诉你哪条功能被冲掉了：

```
补丁丢失：core/.../RaccoonErrors.kt 不存在（小浣熊 200 业务码分类）
补丁可能被覆盖：core/.../ModelNameKey.kt 里找不到 [matchTier]（模型名归一化）
```

---

## 四、常用命令

```bash
sh sync-upstream.sh                # 全套（含构建，约 5-10 分钟）
sh sync-upstream.sh --no-build     # 只合并，手机上快
sh sync-upstream.sh --dry-run      # 演练：只试合并，然后整单回滚
sh sync-upstream.sh --status       # 看状态：在不在合并中、19 条补丁在不在
sh sync-upstream.sh --repo /path   # 指定仓库（不用把脚本挪进去）
bash tools/selftest.sh             # 工具自测（14 条，不需要联网）
```

---

## 五、出问题怎么办

| 现象 | 原因 | 解法 |
|---|---|---|
| `[中止] 当前目录不是 git 仓库` | 脚本没放进仓库根，或 `.git` 被 MT 隐藏了 | 用 `--repo` 指路径；MT 里开「显示隐藏文件」 |
| `[中止] .git 目录不可写` | 仓库在 `/sdcard` 上 | 把仓库移到应用私有目录/Termux 家目录 |
| `[中止] 工作区有未提交改动` | 有没提交的修改 | `git add -A && git commit -m "存档"` 或 `git stash` |
| 两个上游都拉取失败 | 手机连不上 github | 换网络 / 开加速器再试 |
| 构建失败 | 上游用了新工具链 API | 按提示补适配，或 `git reset --hard <合并前 sha>` |
| 想整个撤销 | — | `git reset --hard <合并前 sha>`（脚本最后会打印这个 sha） |

---

## 六、工具文件清单

```
sync-upstream.sh        主脚本（8 步）
run.sh                  MT 管理器一键入口
tools/merge_manifest.py AndroidManifest 合并器（两边都保留 + 按 name 去重）
tools/recursion_scan.py 函数自递归扫描（能区分匿名类重写，不误报）
tools/mod-guards.txt    二改补丁特征串（19 条）
tools/selftest.sh       工具自测（14 条断言）
SYNC-UPSTREAM.md        本文档
```

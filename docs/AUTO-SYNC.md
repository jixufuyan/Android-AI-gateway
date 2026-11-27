# 自动跟上游（GitHub Actions）

Fork 一份之后，主仓库有新版会自动合并 + 自动构建 APK，不用再在手机上点。

---

## 一、先搞清楚三件事

### 1.「fork」和「二改」是同一个仓库

不需要两个仓库。你的 fork 就是二改仓库：

```
xibai6668/Android-AI-gateway   ← 上游（只读，别人的）
        ↓ fork
你的账号/Android-AI-gateway     ← 你的二改仓库（main 跟随 / ide 二改）
```

### 2. 为什么用 `ide` 分支

| 分支 | 用途 |
|---|---|
| `main` | 干净跟随上游，不掺二改。方便对比、随时重建 |
| `ide` | 二改版。自动同步 + 构建都跑这个 |

这样合并出问题时 `main` 还是干净的。

### 3. 自动化的边界

**能自动**：
- 检查上游新提交
- 三方合并
- 工具链文件冲突 → 保留本地版（你的 Gradle 9.0.0 不能跟上游升 9.9.1）
- 构建 + 测试 + 发 Release

**不能自动**（会建 Issue 停下来）：
- 业务代码真冲突
- 二改补丁被上游静默覆盖（最危险那种）

---

## 二、你要做的 5 步

### 第 1 步：Fork

https://github.com/xibai6668/Android-AI-gateway → 右上角 **Fork**。

### 第 2 步：把你现在的二改推上去

**注意**：你本地 `origin` 现在指向的是上游（xibai6668），不是你自己的 fork。先改：

```bash
cd /var/minis/workspace/gw/repo
git remote set-url origin https://github.com/你的用户名/Android-AI-gateway.git
git remote set-url upstream https://github.com/xibai6668/Android-AI-gateway.git

git push origin ide
git push origin main
```

> 第一次推要输 GitHub 用户名 + Personal Access Token（当密码用）。
> 没有 token 就去 Settings → Developer settings → Personal access tokens → 生成，勾 `repo` 权限。

### 第 3 步：配签名密钥（可选）

不配只出 debug 包，也能装。配了才有正式签名。

```bash
# 生成
keytool -genkeypair -v -keystore release.keystore -alias aigw \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass 你的密码 -keypass 你的密码 \
  -dname "CN=ai-gateway, OU=dev, O=dev, L=, S=, C=CN"

# 转 base64（手机上没有 -w0 用第二条）
base64 -w0 release.keystore > keystore.b64
base64 release.keystore | tr -d '\n' > keystore.b64
```

仓库 → Settings → Secrets and variables → Actions → New repository secret，加四个：

| 名字 | 值 |
|---|---|
| `KEYSTORE_BASE64` | keystore.b64 整个内容 |
| `KEYSTORE_PASSWORD` | storepass |
| `KEY_ALIAS` | `aigw` |
| `KEY_PASSWORD` | keypass |

### 第 4 步：开 Actions 权限

Settings → Actions → General → **Workflow permissions**：

- 选 **Read and write permissions**
- 勾 **Allow GitHub Actions to create and approve pull requests**

不勾推不回去、发不了 Release。

### 第 5 步：手动试跑

Actions → **Auto Sync Upstream & Build** → **Run workflow**：

- `dry_run` = 勾上（只检查不推送，安全试水）
- 看日志确认结果

没问题后再跑 `dry_run` 不勾，或等定时。

---

## 三、跑起来什么样

### 定时

每 6 小时（`cron: '17 */6 * * *'`）。改频率编辑 yml 里的 cron。

> GitHub 定时高峰期可能延迟几分钟到半小时，正常。
> 仓库连续 60 天无活动 GitHub 会停掉定时任务（手动跑一次恢复）。

### 一次成功同步

```
1. 检出 ide
2. fetch 上游 → 有新提交
3. merge → 工具链冲突？保留本地
4. 补丁核对 → 19 条锚点全存活
5. 提交 chore(sync): 自动同步上游 xxxxxxx
6. 推回 ide
7. detekt + 单测 + assembleRelease
8. 发 Release（tag: v2.39.3-auto）
```

### 被拦住时

建一个带 `upstream-sync` 标签的 Issue，含问题类型、涉及文件、手工处理命令。
下次同步成功自动关闭。

---

## 四、两个必须知道的坑

### 坑一：补丁静默消失

```
二改在 RaccoonProvider.kt 加一行「限流时换家」
上游重构这个文件，把那段整段重写
→ git merge 不冲突，安静取上游版
→ 编译通过、启动正常
→ 只有真遇到「小浣熊被限流」才发现又不换家了
```

**防法**：`tools/mod-guards.txt` 每条「文件|特征串」都核对。现有 19 条。

新增二改时加一条：

```
路径|必须存在的特征串|这条干嘛的
```

### 坑二：工具链版本别跟

二改锁 **Gradle 9.0.0 + JDK 17**，上游 **9.9.1**。
跟上去大概率能编译，但二改其它改动是按 9.0.0 调的，可能悄悄坏。

所以 11 个工具链文件**永远保留本地版**，工作流里 `gradle-version` 也写死 `9.0.0`。

---

## 五、手动操作

| 想干嘛 | 怎么做 |
|---|---|
| 立刻同步 | Run workflow，`dry_run` 不勾 |
| 只检查冲突不动代码 | `dry_run` 勾上 |
| 只重新构建 | `force_build` 勾上 |
| 本地跑同一套 | `bash tools/ci-sync-upstream.sh` |

退出码：`0`=已最新 / `10`=合并成功 / `78`=被拦需人工 / `1`=错误

---

## 六、三个工作流的关系

| 文件 | 触发 | 干嘛 |
|---|---|---|
| `auto-sync-upstream.yml` | 定时 + 手动 | **新增**：同步上游 → 自动构建发版 |
| `android-release.yml` | 手动 | 手动发正式版（自己控版本号） |
| `build-release.yml` | 手动 | 只构建不发布，产物进 artifact |

`concurrency` 分组不同，不会互相打架。

# 你的 fork 怎么改（jixufuyan/Android-AI-gateway）

一句话：**推两个分支上去，然后开权限、试跑。**

---

## 一、本地已经帮你改好的

我已经把 remote 改对了：

```
origin    → https://github.com/jixufuyan/Android-AI-gateway.git   ✓ 你的 fork
upstream  → https://github.com/xibai6668/Android-AI-gateway.git   ✓ 上游
```

验证：

```bash
cd /var/minis/workspace/gw/repo
git remote -v
```

**注意**：`origin` 原来是 xibai6668，我改掉了。你如果在别的设备上也操作过，记得同步改。

---

## 二、你要做的 4 件事

### 第 1 步：准备 Personal Access Token

本地没有 GitHub 凭据，推送时要认证。

1. 打开 https://github.com/settings/tokens
2. **Generate new token (classic)**
3. Note 随便写，比如 `aigw-push`
4. Expiration 选 90 天
5. 勾 **`repo`**（整个 repo 组，含下面的子项）
6. 生成后**立刻复制**，页面关掉就看不到了

> 也可以生成 Fine-grained token，但要勾 Repository access → All repositories，
> 以及 Contents: Read and write、Actions: Read and write、Issues: Read and write。

### 第 2 步：推送（两种方式选一个）

**方式 A：用我写的脚本（推荐）**

在 Minis 里打开终端跑：

```bash
cd /var/minis/workspace/gw/repo
sh tools/push-to-fork.sh
```

它会自动检查 remote、问你要不要继续，然后推 `ide` 和 `main`。
提示 `Password` 时**粘 token**（不是账号密码）。

**方式 B：手动**

```bash
cd /var/minis/workspace/gw/repo

git push -u origin ide
# Username: jixufuyan
# Password: <粘 token>

git push -u origin main
```

> `main` 和上游完全一致（都是 `838fe76`），推的时候可能提示 "Everything up-to-date"，
> 这很正常 —— fork 出来就已经有了。

**如果 SSH 更顺手**：

```bash
git remote set-url origin git@github.com:jixufuyan/Android-AI-gateway.git
```

前提是你已经配好 SSH key。

### 第 3 步：开 Actions 权限 ⚠️ 最容易漏

打开 https://github.com/jixufuyan/Android-AI-gateway/settings/actions

**Workflow permissions** 区域：

- ☑ 选 **Read and write permissions**
- ☑ 勾 **Allow GitHub Actions to create and approve pull requests**

点保存。

> **不勾的后果**：工作流跑完推不回去、发不了 Release，
> 而且**不会报错**，你会以为是工作流坏了。这是最坑的一步。

### 第 4 步：配签名（可选，不配出 debug 包）

要出正式签名包，加 4 个 Secret：
https://github.com/jixufuyan/Android-AI-gateway/settings/secrets/actions

| 名字 | 值 |
|---|---|
| `KEYSTORE_BASE64` | keystore 转 base64 的整个内容 |
| `KEYSTORE_PASSWORD` | storepass |
| `KEY_ALIAS` | 比如 `aigw` |
| `KEY_PASSWORD` | keypass |

生成 keystore（在信任的设备上做，别用别人的机器）：

```bash
keytool -genkeypair -v -keystore release.keystore -alias aigw \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass 你的密码 -keypass 你的密码 \
  -dname "CN=ai-gateway, OU=dev, O=dev, L=, S=, C=CN"

# 转 base64
base64 -w0 release.keystore > keystore.b64
# 手机没 -w0 就这样
base64 release.keystore | tr -d '\n' > keystore.b64
```

> ⚠️ `release.keystore` 和 `keystore.b64` **不要提交到仓库**。
> 丢了就再也签不出同签名的包，只能让用户重装。

---

## 三、试跑

推上去之后，工作流文件就在 `ide` 分支里了。

打开 https://github.com/jixufuyan/Android-AI-gateway/actions

左侧选 **Auto Sync Upstream & Build** → 右侧 **Run workflow**：

- Branch 选 **`ide`**
- `dry_run` **勾上**（只检查不推送，安全）
- 点绿色按钮

跑完点进去看日志，重点是这两行：

```
[ci-sync] 上游 HEAD: 838fe764
[ci-sync] 已经包含上游 838fe764，无需同步
```

看到「无需同步」就说明逻辑对了（因为你刚从上游 fork，肯定是最新的）。

再跑一次，`dry_run` **不勾**，看它会不会：

- 直接跳「已最新」→ 正确，exit 0，不触发构建
- 这时 `build` job 显示 **skipped** → 正确

---

## 四、跑起来之后

### 定时

每 6 小时检查一次（`17 */6 * * *` UTC）。

### 上游真有更新时会怎样

```
1. fetch → 发现新提交
2. merge → 工具链文件冲突就保留本地版
3. 补丁核对 → 19 条锚点逐个查
4. 提交 "chore(sync): 自动同步上游 xxxxxxx"
5. 推回 ide
6. detekt + 单测 + assembleRelease
7. 发 Release（tag: v2.39.3-auto）
```

### 被拦住时

会建一个带 `upstream-sync` 标签的 Issue。下次成功自动关闭。

---

## 五、随时手动做

| 想干嘛 | 怎么做 |
|---|---|
| 立刻同步 | Run workflow，`dry_run` 不勾 |
| 只检查冲突 | `dry_run` 勾上 |
| 只重新构建 | `force_build` 勾上 |
| 本地跑同一套 | `bash tools/ci-sync-upstream.sh` |

退出码：`0`=已最新 / `10`=合并成功 / `78`=被拦需人工 / `1`=错误

---

## 六、出问题怎么查

### 推不上去

```
remote: Permission to jixufuyan/Android-AI-gateway.git denied
```

→ token 没勾 `repo`，或者粘错了。重新生成一个。

### 工作流跑完没反应

→ 大概率是第 3 步的权限没开。去看日志最后几行有没有 `Resource not accessible by integration`。

### build job 一直是 skipped

→ 这是**正常的**。只有同步产生新提交、或者你勾了 `force_build`，它才会跑。

### 想立刻验证构建能不能过

Run workflow → `force_build` 勾上 → 不勾 `dry_run`。这样即使没同步也会构建一次。

---

## 七、以后加二改时记得

新加一个功能后，如果这个功能容易被上游重构冲掉，就在 `tools/mod-guards.txt` 加一条：

```
路径|必须存在的特征串|这条是干嘛的
```

**特征串怎么选**（这个很关键）：

- ✅ 选整段有辨识度的代码，比如 `if (limited) failover()`
- ❌ 别选短前缀。`GUARD_LINE` 会匹配到 `GUARD_LINE_GONE`，检查就白做了

现有 19 条可以照着改。

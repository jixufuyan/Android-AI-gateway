# 上传签名密钥到 GitHub（出正式包）

有三种方式，按你手上文件的形态挑一个。

---

## 先搞清楚要填什么

工作流要 4 个 Secret：

| Secret 名 | 内容 |
|---|---|
| `KEYSTORE_BASE64` | keystore 文件转成 base64 的一长串 |
| `KEYSTORE_PASSWORD` | storepass（keystore 密码） |
| `KEY_ALIAS` | 别名，比如 `aigw` |
| `KEY_PASSWORD` | keypass（密钥密码） |

> 很多 keystore 的 storepass 和 keypass 是同一个，那就两个填一样的。

**为什么是 base64 而不是直接传文件**：
GitHub Secret 只能存文本，存不了二进制文件。所以 keystore 要先转 base64。

---

## 方式一：keystore 文件在手机上

### 1. 找到文件

常见位置：

```bash
# 用 Termux 或文件管理器找
find /sdcard -name '*.keystore' -o -name '*.jks' 2>/dev/null
```

或者你知道自己当初存哪了。

### 2. 用我写的脚本一键搞定

```bash
cd /path/to/repo          # 或者把脚本单独拿出来
sh tools/extract-keystore.sh /sdcard/你的.keystore aigw
```

它会输出：

```
=== 这个 keystore 里有哪些别名 ===
Alias name: aigw

=== KEYSTORE_BASE64（整个复制这一行）===
--- 开始 ---
MIIKNgIBAzCCCeAGCSqGSIb3DQEHAaCCCdEEggnNMIIJyTCCBcAG...
--- 结束 ---

这段有 3492 个字符，必须全部复制
```

### 3. 手动做也行

```bash
# 看别名
keytool -list -keystore 你的.keystore

# 转 base64（Android/Termux 上没有 -w0 就用第二条）
base64 -w0 你的.keystore > ks.b64
base64 你的.keystore | tr -d '\n' > ks.b64

# 检查长度（应该 2000~5000 字符）
wc -c < ks.b64
```

### 4. 填进 GitHub

https://github.com/jixufuyan/Android-AI-gateway/settings/secrets/actions

点 **New repository secret**，逐个填 4 个。

> 手机浏览器上粘贴长字符串可能很卡，但能用。
> 如果实在粘不进去，用方式二。

---

## 方式二：用 GitHub CLI（推荐，能自动填）

`gh` 可以一条命令设 Secret，不用手动粘贴。

```bash
# 装 gh（Termux）
pkg install gh

# 登录
gh auth login

# 设 4 个 Secret
gh secret set KEYSTORE_BASE64 \
  --repo jixufuyan/Android-AI-gateway \
  < ks.b64

gh secret set KEYSTORE_PASSWORD \
  --repo jixufuyan/Android-AI-gateway \
  --body '你的storepass'

gh secret set KEY_ALIAS \
  --repo jixufuyan/Android-AI-gateway \
  --body 'aigw'

gh secret set KEY_PASSWORD \
  --repo jixufuyan/Android-AI-gateway \
  --body '你的keypass'
```

**注意** `KEYSTORE_BASE64` 用的是 `<` 输入重定向（从文件读），
其它三个用 `--body`（直接给值）。别搞混。

验证：

```bash
gh secret list --repo jixufuyan/Android-AI-gateway
```

应该看到 4 个名字（GitHub 不显示值，正常）。

---

## 方式三：我已经有现成的 keystore.properties

如果你本地已经有 `keystore.properties`，直接用里面的值：

```bash
cd /var/minis/workspace/gw/repo
cat keystore.properties
```

输出长这样：

```properties
storeFile=/path/to/release.keystore
storePassword=你的密码
keyAlias=aigw
keyPassword=你的密码
```

照着填 GitHub Secret 就行（`storeFile` 那个路径不用填，工作流自己会放）。

---

## 填完之后

### 1. 触发一次构建验证

```bash
curl -X POST \
  -H "Authorization: Bearer 你的token" \
  -H "Accept: application/vnd.github+json" \
  https://api.github.com/repos/jixufuyan/Android-AI-gateway/actions/workflows/auto-sync-upstream.yml/dispatches \
  -d '{"ref":"ide","inputs":{"dry_run":false,"force_build":true}}'
```

或者网页：Actions → Auto Sync Upstream & Build → Run workflow → `force_build` 勾上

### 2. 看日志里这两行

```
✓ 还原 release keystore
✓ 生成 keystore.properties    ← 这次不该是 skipped 了
```

之前没配的时候，`生成 keystore.properties` 是 `skipped`。配了之后应该变成 `✓`。

### 3. 确认出的是 release 包

```
✓ 打包
✓ 发 Release（仅 release 包）    ← 这次会真的发
```

产物名字会变成 `ai-gateway-2.39.3-release.apk`（不再是 `-debug`）。

---

## 三个必须注意的坑

### 坑一：keystore 千万别提交到仓库

`.gitignore` 里已经有：

```
*.keystore
keystore.properties
```

**千万别用 `git add -f` 强推**。一旦推到公开仓库，等于把签名密钥送人了 ——
别人可以签出和你一样签名的 APK，你也没法追究。

推到过的话：立刻换 keystore（新签名 = 用户要卸载重装）。

### 坑二：base64 不能有换行

`base64` 默认每 76 字符换行，粘到 Secret 里会变成多行，
还原出来的 keystore 就是坏的，构建时报：

```
KeytoolException: Failed to read key from keystore
```

或者 `Invalid keystore format`。

**所以一定要 `-w0` 或者 `tr -d '\n'`**。

验证方法：

```bash
# 这个应该输出 1（只有一行）
base64 -w0 你的.keystore | wc -l
```

### 坑三：密码里有特殊字符

如果密码含 `$`、`!`、`\` 等，在 shell 里单引号包起来：

```bash
gh secret set KEYSTORE_PASSWORD --body 'p@ss$w0rd!'   # 单引号
```

别用双引号，`$w0rd` 会被当变量展开成空。

---

## 配好之后的完整流程

```
上游出新版
   ↓
自动同步（工具链冲突保留本地）
   ↓
19 条锚点核对补丁
   ↓
detekt + 单测
   ↓
assembleRelease（用你的签名）
   ↓
自动发 Release（tag: v2.39.3-auto）
   ↓
你从 Releases 页面直接下载装上
```

**不用再手动打签名包了。**

---

## 安全建议

1. keystore 和密码**分开放**（密钥在一台设备、密码记在密码管理器）
2. 本地备份 keystore 至少两份（丢了就永远签不出同签名的包）
3. GitHub Secret 一旦设置就看不到值，只能覆盖
4. 别把 `ks.b64` 留在公共目录 —— 那就是明文密钥

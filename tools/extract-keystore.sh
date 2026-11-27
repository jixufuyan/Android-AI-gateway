#!/bin/sh
# =============================================================
# extract-keystore.sh —— 把 keystore 转成 GitHub Secret 要的 4 个值
#
# 用法：
#   sh extract-keystore.sh <你的.keystore 路径> [别名]
#
# 例：
#   sh extract-keystore.sh ~/release.keystore aigw
#
# 会输出：
#   1. KEYSTORE_BASE64   （一长串，直接复制整行）
#   2. KEY_ALIAS
#   3. 提示手动输入两个密码
#
# 为什么密码不自动读：
#   keystore 的密码是加密存的，脚本没法反解。
#   你自己知道，直接填进 GitHub Secret 就行。
# =============================================================
set -u

KS="${1:-}"
ALIAS_HINT="${2:-}"

if [ -z "$KS" ]; then
  cat <<'USAGE'
用法：sh extract-keystore.sh <keystore 路径> [别名]

  sh extract-keystore.sh ~/release.keystore aigw
  sh extract-keystore.sh /sdcard/mykey.jks myalias
USAGE
  exit 1
fi

if [ ! -f "$KS" ]; then
  echo "✗ 找不到文件：$KS"
  exit 1
fi

echo "文件：$KS"
echo "大小：$(wc -c < "$KS") 字节"
echo

# ---------- 1. 列出别名 ----------
echo "=== 这个 keystore 里有哪些别名 ==="
if command -v keytool >/dev/null 2>&1; then
  echo "（如果 keystore 有密码，这里可能列不出来，别名你自己知道就行）"
  keytool -list -v -keystore "$KS" 2>/dev/null | grep -E '别名名称|Alias name' || \
  keytool -list -keystore "$KS" 2>&1 | head -20
else
  echo "（没有 keytool，跳过。你可以自己知道别名）"
fi
echo

# ---------- 2. 转 base64 ----------
echo "=== KEYSTORE_BASE64（整个复制这一行）==="
echo "--- 开始 ---"
if base64 --help 2>&1 | grep -q '\-w'; then
  base64 -w0 "$KS"
else
  base64 "$KS" | tr -d '\n'
fi
echo
echo "--- 结束 ---"
echo

B64LEN=$({ if base64 --help 2>&1 | grep -q '\-w'; then base64 -w0 "$KS"; else base64 "$KS" | tr -d '\n'; fi; } | wc -c)
echo "这段有 $B64LEN 个字符，必须全部复制，一个都不能少。"
echo

# ---------- 3. 提醒 ----------
cat <<'NEXT'
=== 接下来去 GitHub 填 4 个 Secret ===

打开：
  https://github.com/你的用户名/Android-AI-gateway/settings/secrets/actions

点 New repository secret，逐个添加：

  名字                   值
  --------------------- --------------------------------------
  KEYSTORE_BASE64       上面那一长串（完全复制）
  KEYSTORE_PASSWORD     你的 storepass（keystore 密码）
  KEY_ALIAS             别名（比如 aigw）
  KEY_PASSWORD          你的 keypass（密钥密码）

  如果 storepass 和 keypass 是同一个，两个都填一样的。

⚠️ 填完删掉本地的 base64 输出记录
⚠️ 千万别把 keystore 文件本身提交到仓库
NEXT

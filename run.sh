#!/system/bin/sh
# =============================================================================
# MT 管理器「一键执行」入口
#
# 用法（在 MT 管理器里）：
#   长按本文件 → 执行 → 选「Root」或「Shizuku」/「终端」都可以
#   或者：MT 管理器打开仓库目录 → 长按 run.sh → 执行
#
# 它会自动找到仓库根目录（本文件所在目录，或往上找 .git），然后调用同步脚本。
# 没有任何参数、不需要记命令。
# =============================================================================

# MT 管理器的执行环境 path 经常很干净，先把常见位置补上
PATH="$PATH:/system/bin:/system/xbin:/vendor/bin:/data/adb/magisk:/data/data/com.termux/files/usr/bin:$HOME/bin"
export PATH
export LANG=zh_CN.UTF-8
export LC_ALL=zh_CN.UTF-8

HERE=$(cd "$(dirname "$0")" 2>/dev/null && pwd)
cd "$HERE" 2>/dev/null || { echo "进不去脚本目录"; exit 1; }

echo "=============================================="
echo " Android-AI-gateway 上游同步"
echo " 目录：$HERE"
echo "=============================================="
echo

# 找 git
GIT=""
for c in git /system/bin/git /system/xbin/git /data/data/com.termux/files/usr/bin/git; do
  if command -v "$c" >/dev/null 2>&1; then GIT="$c"; break; fi
done
if [ -z "$GIT" ]; then
  cat <<'EOF'
[没有 git]

MT 管理器自带的终端一般没有 git。三个办法：

  1) 用 Termux（推荐）
       pkg install git
       cd <仓库目录>
       sh sync-upstream.sh

  2) 用 MT 管理器内置终端，先装 git（部分设备可行）
       在 MT 里：右上角菜单 → 终端 → 输入 pkg install git

  3) 丢给 AI 跑
       把整个仓库目录打包发给 AI，让它执行 sync-upstream.sh

EOF
  printf '按回车关闭…'; read _ 2>/dev/null
  exit 1
fi
echo "git: $($GIT --version 2>/dev/null)"
echo

# 仓库根：本脚本所在目录，或往上找 .git
REPO="$HERE"
if [ ! -d "$REPO/.git" ]; then
  d="$HERE"
  while [ -n "$d" ] && [ "$d" != "/" ]; do
    if [ -d "$d/.git" ]; then REPO="$d"; break; fi
    d=$(dirname "$d")
  done
fi

if [ ! -d "$REPO/.git" ]; then
  cat <<EOF
[没找到 git 仓库]

本文件在：$HERE
往上找也没有 .git 目录。

请把 run.sh 和 sync-upstream.sh 一起放进 Android-AI-gateway 的源码根目录：
    Android-AI-gateway/
    ├── .git/                ← 必须有（MT 里要开「显示隐藏文件」才看得到）
    ├── app/
    ├── core/
    ├── run.sh               ← 这个
    └── sync-upstream.sh

或者在 MT 里手动指定：
    sh sync-upstream.sh --repo /sdcard/Android-AI-gateway

EOF
  printf '按回车关闭…'; read _ 2>/dev/null
  exit 1
fi

cd "$REPO" || exit 1
echo "仓库：$REPO"
echo

SYNC=""
for f in "$REPO/sync-upstream.sh" "$REPO/tools/sync-upstream.sh"; do
  [ -f "$f" ] && { SYNC="$f"; break; }
done
if [ -z "$SYNC" ]; then
  echo "[缺少 sync-upstream.sh] 请把它和本文件放在同一目录。"
  printf '按回车关闭…'; read _ 2>/dev/null
  exit 1
fi

sh "$SYNC" "$@"
RC=$?

echo
if [ "$RC" = "0" ]; then
  echo "=============================================="
  echo " 完成"
  echo "=============================================="
  APK="$REPO/app/build/outputs/apk/debug/app-debug.apk"
  if [ -f "$APK" ]; then
    echo "APK：$APK"
    echo "大小：$(du -h "$APK" 2>/dev/null | cut -f1)"
  fi
else
  echo "=============================================="
  echo " 有中断（退出码 $RC），按上面的提示处理"
  echo "=============================================="
fi

printf '\n按回车关闭…'
read _ 2>/dev/null
exit "$RC"

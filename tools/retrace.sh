#!/usr/bin/env bash
# 崩溃日志反混淆（retrace）
#
# 用法：
#   tools/retrace.sh <crash.log> [mapping.txt]
#
# 说明：
#   - 默认读 app/build/outputs/mapping/release/mapping.txt
#   - 每次发版必须把该版本的 mapping.txt 与 APK 一起归档，否则事后无法还原
#   - retrace 类随 build-tools 的 d8.jar 分发，不需要额外装 R8
set -e

LOG=${1:?用法: tools/retrace.sh <crash.log> [mapping.txt]}
MAP=${2:-app/build/outputs/mapping/release/mapping.txt}

if [ ! -f "$MAP" ]; then
  echo "找不到 mapping.txt：$MAP" >&2
  echo "（重新打一次 release 能再生成，但新映射表对旧崩溃日志无效）" >&2
  exit 1
fi

SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}
JAR=""
if [ -d "$SDK/build-tools" ]; then
  BT=$(ls -1 "$SDK/build-tools" | sort -V | tail -1)
  [ -f "$SDK/build-tools/$BT/lib/d8.jar" ] && JAR="$SDK/build-tools/$BT/lib/d8.jar"
fi
if [ -z "$JAR" ]; then
  JAR=$(find "$HOME/.gradle/caches" -name 'r8-*.jar' 2>/dev/null | head -1)
fi
[ -n "$JAR" ] || { echo "找不到含 retrace 的 jar：把 ANDROID_HOME 指向 SDK 目录" >&2; exit 1; }

exec java -cp "$JAR" com.android.tools.r8.retrace.Retrace "$MAP" "$LOG"

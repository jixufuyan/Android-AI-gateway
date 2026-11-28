#!/bin/sh
# 守卫清单自检：逐条核对 mod-guards.txt 里每个「路径|特征串」是否真的在源码树里
#
# 为什么需要它：守卫清单本身写错（路径拼错、特征串写成了别的地方才有的文本）
# 会静默失效 —— 合并时永远报「补丁丢失」，或者永远报「都在」，两种都没意义。
# 这个脚本可以先对一份「已知正确的源码树」跑一遍，确认清单本身是对的。
#
# 用法：
#   sh check-guards.sh <源码根目录> [守卫文件]
#
# 退出码：0 = 全部命中；1 = 有缺失

ROOT="${1:-.}"
GUARDS="${2:-$(dirname "$0")/../mod-guards.txt}"

if [ ! -d "$ROOT" ]; then
  echo "源码目录不存在：$ROOT" >&2
  exit 1
fi
if [ ! -f "$GUARDS" ]; then
  echo "守卫文件不存在：$GUARDS" >&2
  exit 1
fi

TOTAL=0
OK=0
MISS=0
MISSING_FILE=0

echo "== 守卫清单自检 =="
echo "   源码: $ROOT"
echo "   清单: $GUARDS"
echo

while IFS= read -r line; do
  case "$line" in
    ''|'#'*) continue ;;
  esac
  TOTAL=$((TOTAL + 1))

  f=$(printf '%s' "$line" | cut -d'|' -f1)
  pat=$(printf '%s' "$line" | cut -d'|' -f2)
  desc=$(printf '%s' "$line" | cut -d'|' -f3)

  full="$ROOT/$f"
  if [ ! -f "$full" ]; then
    printf '  \033[1;31m✗ 文件不存在\033[0m %s\n      %s\n' "$f" "$desc"
    MISSING_FILE=$((MISSING_FILE + 1))
    continue
  fi

  if grep -qF -- "$pat" "$full" 2>/dev/null; then
    OK=$((OK + 1))
    printf '  \033[1;32m✓\033[0m %s\n' "$desc"
  else
    printf '  \033[1;31m✗ 特征串缺失\033[0m %s\n      文件: %s\n      期望包含: %s\n' \
      "$desc" "$f" "$pat"
    MISS=$((MISS + 1))
  fi
done < "$GUARDS"

echo
echo "  合计 $TOTAL 条：命中 $OK，特征串缺失 $MISS，文件缺失 $MISSING_FILE"

if [ "$MISS" -gt 0 ] || [ "$MISSING_FILE" -gt 0 ]; then
  printf '\033[1;31m守卫清单与源码不一致\033[0m\n'
  exit 1
fi
printf '\033[1;32m守卫清单与源码全部一致\033[0m\n'
exit 0

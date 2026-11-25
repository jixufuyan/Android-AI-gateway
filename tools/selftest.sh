#!/usr/bin/env bash
# =============================================================================
# 同步工具自测（不需要联网、不需要真仓库）
#
#   bash tools/selftest.sh
#
# 覆盖三件事，都是「错了也能跑、只有到真机上才炸」的那类：
#   1. merge_manifest.py：两边都保留、按 name 去重、重复 name 能拦住
#   2. recursion_scan.py：真递归要报出、匿名类重写/表达式体不能误报
#   3. sync-upstream.sh：不在 git 仓库里要给出人话提示，而不是裸报错
# =============================================================================
set -uo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
PASS=0
FAIL=0

ok()   { printf '  \033[1;32m✓\033[0m %s\n' "$*"; PASS=$((PASS+1)); }
bad()  { printf '  \033[1;31m✗\033[0m %s\n' "$*"; FAIL=$((FAIL+1)); }
check(){ if [ "$2" = "$3" ]; then ok "$1"; else bad "$1（得到 $2，期望 $3）"; fi; }

W=$(mktemp -d)
trap 'rm -rf "$W"' EXIT

# ---------------------------------------------------------------------------
echo "==> 1/3 AndroidManifest 合并"
# ---------------------------------------------------------------------------
cat > "$W/m.xml" <<'XML'
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application>
        <activity android:name=".Main" />
<<<<<<< HEAD
        <!-- 本地新增：智谱登录 -->
        <activity android:name=".ChatglmLogin" android:exported="false" />
=======
        <activity android:name=".CheckinWeb" android:exported="false" />
        <activity android:name=".Main" />
>>>>>>> upstream/main
        <service android:name=".Gw" />
    </application>
</manifest>
XML
python3 "$HERE/merge_manifest.py" "$W/m.xml" >/dev/null 2>&1
check "合并后无冲突标记" "$(grep -c '<<<<<<<' "$W/m.xml")" "0"
check "保留本地 activity" "$(grep -c 'ChatglmLogin' "$W/m.xml")" "1"
check "带上上游 activity" "$(grep -c 'CheckinWeb' "$W/m.xml")" "1"
check "同名不重复（Main 只留一份）" "$(grep -c 'android:name=".Main"' "$W/m.xml")" "1"
check "service 没被吃掉" "$(grep -c 'android:name=".Gw"' "$W/m.xml")" "1"

# 同一个 activity 两边都新增 → 只能留一份（否则 aapt2 报 duplicate）
cat > "$W/dup.xml" <<'XML'
<manifest><application>
<<<<<<< HEAD
        <activity android:name=".Same" />
=======
        <activity android:name=".Same" />
>>>>>>> other
</application></manifest>
XML
python3 "$HERE/merge_manifest.py" "$W/dup.xml" >/dev/null 2>&1
check "两边同名只留一份" "$(grep -c 'android:name=".Same"' "$W/dup.xml")" "1"

# ---------------------------------------------------------------------------
echo "==> 2/3 自递归扫描"
# ---------------------------------------------------------------------------
mkdir -p "$W/kt"
cat > "$W/kt/Good.kt" <<'KT'
fun outer() {
    val v = object : Foo {
        override fun onCreate() { outer() }
    }
    helper(v)
}
private fun helper(x: Any) = println(x)
fun exprBody(n: Int) = n + 1
KT
OUT=$(python3 "$HERE/recursion_scan.py" "$W/kt" 2>/dev/null)
check "匿名类里调外层函数不算递归" "$(echo "$OUT" | grep -c outer)" "0"
check "表达式体函数不误报" "$(echo "$OUT" | grep -c exprBody)" "0"

cat > "$W/kt/Bad.kt" <<'KT'
fun boom(n: Int): Int { return boom(n + 1) }
fun deep(a: List<String>): List<String> = deep(a)
KT
OUT2=$(python3 "$HERE/recursion_scan.py" "$W/kt" 2>/dev/null)
check "带返回类型的真递归能报出" "$(echo "$OUT2" | grep -c 'boom')" "1"
check "表达式体的真递归能报出" "$(echo "$OUT2" | grep -c 'deep')" "1"

# 字符串里的花括号不能打乱配对
cat > "$W/kt/Str.kt" <<'KT'
fun inner() { }
fun show(): String {
    val s = """{"a": {"b": 1}}"""
    return s
}
KT
OUT3=$(python3 "$HERE/recursion_scan.py" "$W/kt" 2>/dev/null)
check "原始字符串里的花括号不误判" "$(echo "$OUT3" | grep -c 'inner\|show')" "0"

# ---------------------------------------------------------------------------
echo "==> 3/3 不在 git 仓库时的提示"
# ---------------------------------------------------------------------------
# 注意：这里必须把脚本复制到「仓库外」的目录再执行。
# 直接从 $HERE 调用会从仓库内部往上找到真仓库，测不出「不在仓库里」这条路径。
mkdir -p "$W/notrepo"
# 优先用根目录的新脚本：老版本仓库里 tools/ 下可能还留着 2.0 的旧脚本，
# 路径解析错了就会测到旧工具，测出来的绿是假的。
SYNC_SCRIPT="$HERE/../sync-upstream.sh"
[ -f "$SYNC_SCRIPT" ] || SYNC_SCRIPT="$HERE/sync-upstream.sh"
cp "$SYNC_SCRIPT" "$W/notrepo/script.sh"
OUT4=$(cd "$W/notrepo" && sh ./script.sh 2>&1)
RC=$?
check "退出码非 0" "$RC" "1"
check "提示包含「不是 git 仓库」" "$(echo "$OUT4" | grep -c '不是 git 仓库')" "1"
HASREPO=0; printf '%s' "$OUT4" | grep -qF -- '--repo' && HASREPO=1
check "提示里给出 --repo 用法" "$HASREPO" "1"

# ---------------------------------------------------------------------------
echo
if [ "$FAIL" = "0" ]; then
  printf '\033[1;32m全部通过\033[0m（%s 条）\n\n' "$PASS"
  exit 0
else
  printf '\033[1;31m有 %s 条失败\033[0m（通过 %s 条）\n\n' "$FAIL" "$PASS"
  exit 1
fi

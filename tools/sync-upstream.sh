#!/usr/bin/env bash
# =============================================================================
# AI-Gateway 二改 × 上游同步（一键）
#
#   tools/sync-upstream.sh              # 合并 + 适配 + 提交 + 构建验证
#   tools/sync-upstream.sh --no-build   # 只要合并，不构建
#   tools/sync-upstream.sh --dry-run    # 只演练：合并试跑 + 打印会做什么，然后回滚
#
# 处理顺序：
#   1. 拉上游 → 2. 三方合并 → 3. 工具链文件冲突一律保留本地版
#   4. detekt 配置回退 1.23 语法 → 5. 上游新增依赖补进本地构建脚本
#   6. 隐患扫描（haze 2.x API / 函数自递归）→ 7. 提交 → 8. 单测 + debug 包
#
# 规则与人工步骤见根目录 SYNC-UPSTREAM.md
# =============================================================================
set -uo pipefail

REPO_ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$REPO_ROOT" || exit 1

UPSTREAM_REMOTE=${UPSTREAM_REMOTE:-upstream}
UPSTREAM_BRANCH=${UPSTREAM_BRANCH:-main}
SNAPSHOT_DIR=.sync-upstream

DO_BUILD=1
DRY_RUN=0
for arg in "$@"; do
  case "$arg" in
    --no-build) DO_BUILD=0 ;;
    --dry-run)  DRY_RUN=1; DO_BUILD=0 ;;
    -h|--help)  sed -n '2,18p' "$0"; exit 0 ;;
    *) echo "未知参数：$arg（可用：--no-build / --dry-run）"; exit 2 ;;
  esac
done

step() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
ok()   { printf '  \033[1;32m✓\033[0m %s\n' "$*"; }
warn() { printf '  \033[1;33m!\033[0m %s\n' "$*"; }
die()  { printf '\n\033[1;31m[中止] %s\033[0m\n' "$*" >&2; exit 1; }

# 构建工具链文件：这些永远保留本地版（手机 IDE 只能跑 AGP 8.13 / Kotlin 2.1 / Gradle 9.0）
TOOLCHAIN_FILES="
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
"

# ---------------------------------------------------------------------------
step "0/7 环境检查"
# ---------------------------------------------------------------------------
[ -d .git ] || die "当前目录不是 git 仓库：$REPO_ROOT"
git remote get-url "$UPSTREAM_REMOTE" >/dev/null 2>&1 || die "没有名为 $UPSTREAM_REMOTE 的远端，先执行：
  git remote add $UPSTREAM_REMOTE https://github.com/xibai6668/Android-AI-gateway.git"

BRANCH=$(git rev-parse --abbrev-ref HEAD)
ok "仓库 $REPO_ROOT"
ok "分支 $BRANCH（二改分支，建议就叫 ide）"

if [ -n "$(git status --porcelain --untracked-files=no)" ]; then
  die "工作区有未提交改动，先 commit 或 git stash"
fi

RESUMING=0
if git rev-parse -q --verify MERGE_HEAD >/dev/null 2>&1; then
  RESUMING=1
  warn "上次合并没有收尾，本次接着处理（跳过合并步骤）"
fi

# ---------------------------------------------------------------------------
step "1/7 拉取上游 $UPSTREAM_REMOTE/$UPSTREAM_BRANCH"
# ---------------------------------------------------------------------------
git fetch "$UPSTREAM_REMOTE" "$UPSTREAM_BRANCH" || die "拉取失败：检查网络 / 远端地址"
UPSTREAM_REF="$UPSTREAM_REMOTE/$UPSTREAM_BRANCH"
UPSTREAM_SHA=$(git rev-parse --short "$UPSTREAM_REF")
BEFORE=$(git rev-parse HEAD)
ok "上游 HEAD = $UPSTREAM_SHA，本地 HEAD = $(echo "$BEFORE" | cut -c1-7)"

# 上游构建文件快照：合并后拿它比对「新增了哪些依赖」
mkdir -p "$SNAPSHOT_DIR"
for f in app/build.gradle.kts core/build.gradle.kts cli/build.gradle.kts; do
  snap="$SNAPSHOT_DIR/$(echo "$f" | tr '/' '_')"
  git show "$UPSTREAM_REF:$f" > "$snap" 2>/dev/null || rm -f "$snap"
done
ok "上游构建文件已导出到 $SNAPSHOT_DIR/"

# ---------------------------------------------------------------------------
step "2/7 三方合并"
# ---------------------------------------------------------------------------
if [ "$RESUMING" = "1" ]; then
  ok "延续上次合并，跳过"
else
  git merge --no-commit --no-ff "$UPSTREAM_REF" >/dev/null 2>&1
  MERGE_RC=$?
  if [ "$MERGE_RC" -ne 0 ]; then
    warn "存在冲突，先自动处理工具链文件"
    for f in $TOOLCHAIN_FILES; do
      if git ls-files -u -- "$f" 2>/dev/null | grep -q .; then
        # 演练时也照样解决（只动索引，最后整单回滚），这样「剩余冲突」列表才是准的
        if git checkout --ours -- "$f" 2>/dev/null; then
          git add -- "$f" && warn "保留本地版：$f"
        fi
      fi
    done
  else
    ok "三方合并无冲突"
  fi
fi

REMAIN=$(git diff --name-only --diff-filter=U 2>/dev/null)
if [ -n "$REMAIN" ]; then
  printf '\n\033[1;33m还有 %s 个业务文件需要你手工合并\033[0m（脚本不替你决定业务逻辑）：\n' "$(echo "$REMAIN" | wc -l | tr -d ' ')"
  echo "$REMAIN" | sed 's/^/    - /'
  cat <<'TIP'

  怎么处理：
    1) 打开这些文件，搜 <<<<<<< / ======= / >>>>>>> 三处标记
    2) 两边都要的合并保留，删掉标记行
    3) 全改完执行：  git add -A && tools/sync-upstream.sh --no-build
       （脚本会接着跑适配 + 提交，不会从头再来）
TIP
  if [ "$DRY_RUN" = "1" ]; then git merge --abort 2>/dev/null; fi
  exit 1
fi
ok "无遗留冲突"

# ---------------------------------------------------------------------------
step "3/7 工具链适配（detekt 语法 + 上游新增依赖）"
# ---------------------------------------------------------------------------
ADAPT_LOG=$(mktemp)

# 3.1 detekt 2.0 属性名 → 1.23
if [ -f config/detekt/detekt.yml ]; then
  if grep -qE '^[[:space:]]*allowed(Lines|Complexity|Depth|Conditions):' config/detekt/detekt.yml 2>/dev/null ||
     grep -qE '^  UnusedPrivate(Function|Property):' config/detekt/detekt.yml 2>/dev/null; then
    sed -i -E 's/^([[:space:]]*)allowedLines:/\1threshold:/; s/^([[:space:]]*)allowedComplexity:/\1threshold:/; s/^([[:space:]]*)allowedDepth:/\1threshold:/; s/^([[:space:]]*)allowedConditions:/\1threshold:/' config/detekt/detekt.yml
    if grep -qE '^  UnusedPrivate(Function|Property):' config/detekt/detekt.yml; then
      awk '
        /^  UnusedPrivate(Function|Property):/ { if (!done) { print "  UnusedPrivateMember:"; print "    active: true"; done=1 }; next }
        done && /^    active:[[:space:]]*(true|false)/ { next }
        { print }
      ' config/detekt/detekt.yml > config/detekt/detekt.yml.tmp && mv config/detekt/detekt.yml.tmp config/detekt/detekt.yml
      echo "detekt: UnusedPrivateFunction/Property → UnusedPrivateMember" >> "$ADAPT_LOG"
    fi
    echo "detekt: allowed* 属性名 → threshold（1.23 语法）" >> "$ADAPT_LOG"
  fi
fi

# 3.2 上游新增依赖：按字面量补进本地构建脚本（不动版本目录结构）
sync_deps() {
  upstream="$1"; local_file="$2"
  [ -f "$upstream" ] && [ -f "$local_file" ] || return 0

  grep -E '^[[:space:]]*(implementation|api|compileOnly|runtimeOnly|testImplementation|androidTestImplementation|kapt|ksp)\("' "$upstream" 2>/dev/null \
  | sed -E 's/^[[:space:]]+//' \
  | while IFS= read -r line; do
      ga=$(printf '%s' "$line" | sed -E 's/.*"([^"]+)".*/\1/' | awk -F: '{print $1":"$2}')
      case "$ga" in *:*:*) continue ;; esac   # 三元坐标说明解析错了，跳过
      [ -n "$ga" ] || continue
      if grep -qF "$ga" "$local_file" 2>/dev/null || grep -qF "module = \"$ga\"" gradle/libs.versions.toml 2>/dev/null; then
        continue
      fi
      echo "新增依赖 $ga → $local_file" >> "$ADAPT_LOG"
      awk -v note="    // [sync-upstream] 上游新增依赖，按字面量补入" -v dep="$line" '
        { buf[NR]=$0 }
        END {
          last=0
          for (i=NR; i>=1; i--) if (buf[i] ~ /^}/) { last=i; break }
          for (i=1; i<=NR; i++) {
            if (i==last) { print note; print dep }
            print buf[i]
          }
        }
      ' "$local_file" > "$local_file.tmp" && mv "$local_file.tmp" "$local_file"
    done
}

if [ "$DRY_RUN" = "0" ]; then
  sync_deps "$SNAPSHOT_DIR/app_build.gradle.kts"  app/build.gradle.kts
  sync_deps "$SNAPSHOT_DIR/core_build.gradle.kts" core/build.gradle.kts
  sync_deps "$SNAPSHOT_DIR/cli_build.gradle.kts"  cli/build.gradle.kts
fi

if [ -s "$ADAPT_LOG" ]; then sed 's/^/  /' "$ADAPT_LOG"; else ok "无需适配"; fi
rm -f "$ADAPT_LOG"

# ---------------------------------------------------------------------------
step "4/7 隐患扫描（能编译、跑起来才炸的那几类）"
# ---------------------------------------------------------------------------
FOUND=0

# 4.1 haze 2.x API：IDE 侧只能用 1.6.10
HAZE_HITS=$(grep -rnE 'hazeBlur\(|HazeBlurStyle|HazeInput|HazeColorEffect' app/src/main/kotlin 2>/dev/null || true)
if [ -n "$HAZE_HITS" ]; then
  warn "发现 haze 2.x API（本项目只能用 1.6.10 的 1.x 写法），改成："
  echo "      hazeEffect(state = haze, style = HazeStyle(backgroundColor = Color.Transparent,"
  echo "          tints = listOf(HazeTint(color = …)), blurRadius = 40.dp, noiseFactor = 0.04f))"
  echo "$HAZE_HITS" | head -10 | sed 's/^/      /'
  FOUND=1
fi

# 4.2 函数自递归：编译能过，一进组合就 StackOverflowError（AppSwitch 就是这么崩的）
if command -v python3 >/dev/null 2>&1; then
  REC=$(python3 - <<'PY'
import re, glob
bad = []
for f in glob.glob('app/src/main/**/*.kt', recursive=True):
    src = open(f, encoding='utf-8').read()
    for m in re.finditer(r'\bfun\s+(\w+)\s*\(', src):
        name = m.group(1)
        i = src.find('{', m.end())
        if i < 0:
            continue
        depth, j = 0, i
        while j < len(src):
            if src[j] == '{':
                depth += 1
            elif src[j] == '}':
                depth -= 1
                if depth == 0:
                    break
            j += 1
        body = src[i:j]
        body = re.sub(r'//[^\n]*', '', body)                 # 去行注释
        body = re.sub(r'/\*.*?\*/', '', body, flags=re.S)    # 去块注释
        if re.search(r'(?<![A-Za-z0-9_.])' + re.escape(name) + r'\s*\(', body):
            bad.append(f"{f}:{src[:m.start()].count(chr(10)) + 1}: {name}()")
print("\n".join(bad))
PY
)
  if [ -n "$REC" ]; then
    warn "疑似自递归函数（自己调自己 → 无限递归）："
    echo "$REC" | sed 's/^/      /'
    FOUND=1
  fi
else
  warn "没有 python3，跳过自递归扫描（建议装一个：pkg install python）"
fi

[ "$FOUND" = "0" ] && ok "未发现已知隐患"
[ "$FOUND" = "1" ] && warn "上面这些改完再提交（脚本不自动改业务代码）"

# ---------------------------------------------------------------------------
step "5/7 提交"
# ---------------------------------------------------------------------------
if [ "$DRY_RUN" = "1" ]; then
  warn "演练结束：不提交、不构建，已回滚合并"
  git merge --abort 2>/dev/null || true
  exit 0
fi

git add -A
if git diff --cached --quiet; then
  ok "没有需要提交的改动"
else
  git commit -q -m "merge: 同步上游 $UPSTREAM_SHA

自动处理：工具链文件保留本地版、上游新增依赖按字面量补入、detekt 配置回退 1.23 语法。"
  ok "已提交 $(git log -1 --format='%h %s')"
fi

# ---------------------------------------------------------------------------
step "6/7 构建验证"
# ---------------------------------------------------------------------------
if [ "$DO_BUILD" = "0" ]; then
  warn "跳过构建（--no-build）"
else
  if ./gradlew :core:test :app:assembleDebug --console=plain; then
    ok "单测 + debug 包通过"
    ok "APK：app/build/outputs/apk/debug/app-debug.apk"
  else
    printf '\n\033[1;31m构建失败\033[0m，两种可能：\n'
    echo "  a) 上游代码用到你工具链里没有的 API → 按 SYNC-UPSTREAM.md「适配清单」补，或把该功能摘掉"
    echo "  b) 合并逻辑错了 → 一键回滚："
    echo "       git reset --hard $BEFORE"
    exit 1
  fi
fi

# ---------------------------------------------------------------------------
step "7/7 摘要"
# ---------------------------------------------------------------------------
echo "  上游版本：$UPSTREAM_SHA"
echo "  合并前  ：$(echo "$BEFORE" | cut -c1-7)"
echo "  合并后  ：$(git rev-parse --short HEAD)"
echo
echo "  改动统计："
git diff --stat "$BEFORE" HEAD 2>/dev/null | tail -20 | sed 's/^/    /'
echo
echo "  下一步：实机验证没问题 → git push origin $BRANCH"
echo "  要回滚  ：git reset --hard $BEFORE"

#!/usr/bin/env bash
# =============================================================================
# AI-Gateway 二改 × 上游同步（一键）
#
#   把脚本放进「二改仓库的根目录」直接执行即可，不用记参数：
#
#     ./sync-upstream.sh              # 合并 + 自动解冲突 + 适配 + 隐患扫描 + 提交 + 构建
#     ./sync-upstream.sh --no-build   # 只合并，不构建（手机上 5 分钟能跑完）
#     ./sync-upstream.sh --dry-run    # 演练：只试合并并打印会发生什么，然后整单回滚
#     ./sync-upstream.sh --status     # 看当前状态（是不是在合并中、补丁在不在）
#
# 也可以从任意位置指定仓库：
#     ./sync-upstream.sh --repo /path/to/Android-AI-gateway
#
# 关键设计：
#   • 上游有两个仓库，都试：
#       xibai6668/Android-AI-gateway  原上游（新供应商都在这儿）
#       alarmedvine/Android-AI-gateway 分支（多了 newapi 自动签到站点）
#     默认合「两个都试，能快进就先快进，否则三方合并」。
#   • 工具链文件冲突 → 永远保留本地版（手机 IDE 只吃 AGP 8.13 / Kotlin 2.1 / Gradle 9.0）
#   • AndroidManifest.xml 冲突 → 自动「两边都保留 + 按 name 去重」（tools/merge_manifest.py）
#   • build.gradle.kts 里的 versionCode/versionName → 保留本地版
#   • 其余业务文件冲突 → 不替你做决定，列出来告诉你怎么合，然后可以接着跑
# =============================================================================
set -uo pipefail

# ---------- 定位自己 ----------
SELF_DIR=$(cd "$(dirname "$0")" 2>/dev/null && pwd)

# 找仓库：优先「当前目录往上找」，其次才看脚本自己所在的目录。
#
# 为什么是这个顺序：把脚本放在 /sdcard/kit/ 里、从任意位置执行是常见用法；
# 如果先看脚本目录，就会沿着 kit 的父目录往上爬到毫不相干的仓库里去。
find_repo_up() {
  d=$(pwd)
  while [ -n "$d" ] && [ "$d" != "/" ]; do
    if [ -d "$d/.git" ]; then echo "$d"; return 0; fi
    d=$(dirname "$d")
  done
  return 1
}
REPO_ROOT=""
if [ -d "$(pwd)/.git" ]; then
  REPO_ROOT=$(pwd)
else
  REPO_ROOT=$(find_repo_up 2>/dev/null || true)
fi

# ---------- 参数 ----------
DO_BUILD=1
DRY_RUN=0
STATUS_ONLY=0
for arg in "$@"; do
  case "$arg" in
    --no-build) DO_BUILD=0 ;;
    --dry-run)  DRY_RUN=1; DO_BUILD=0 ;;
    --status)   STATUS_ONLY=1 ;;
    --repo)     shift; REPO_ROOT="$1" ;;
    --repo=*)   REPO_ROOT="${arg#--repo=}" ;;
    -h|--help)  sed -n '2,28p' "$0"; exit 0 ;;
    *) ;;   # 忽略未知参数，MT 管理器带过来的杂项不要中断
  esac
done

# ---------- 远端配置（要换上游改这里） ----------
primary_remote()   { echo "${UPSTREAM_REMOTE:-upstream}"; }
primary_url()      { echo "${UPSTREAM_URL:-https://github.com/xibai6668/Android-AI-gateway.git}"; }
primary_branch()   { echo "${UPSTREAM_BRANCH:-main}"; }
fork_remote()      { echo "${FORK_REMOTE:-alarmedvine}"; }
fork_url()         { echo "${FORK_URL:-https://github.com/alarmedvine/Android-AI-gateway.git}"; }
fork_branch()      { echo "${FORK_BRANCH:-main}"; }

STEP=0
TOTAL=8
step() { STEP=$((STEP+1)); printf '\n\033[1;36m==> %s/%s %s\033[0m\n' "$STEP" "$TOTAL" "$*"; }
ok()   { printf '  \033[1;32m✓\033[0m %s\n' "$*"; }
warn() { printf '  \033[1;33m!\033[0m %s\n' "$*"; }
info() { printf '    %s\n' "$*"; }
die()  { printf '\n\033[1;31m[中止] %b\033[0m\n\n' "$*" >&2; exit 1; }

git_ok() { command -v git >/dev/null 2>&1; }

# ---------------------------------------------------------------------------
# 环境检查
# ---------------------------------------------------------------------------
if ! git_ok; then
  die "找不到 git。MT 管理器自带的终端可能没有 git：\n  • 用 Termux 执行：pkg install git\n  • 或者把仓库放电脑上跑同一份脚本"
fi

# 没找到仓库时，给出明确指引（MT 管理器最容易错的一步）
if [ -z "$REPO_ROOT" ] || [ ! -d "$REPO_ROOT/.git" ]; then
  printf '\n\033[1;31m[中止] 当前目录不是 git 仓库\033[0m\n\n' >&2
  echo "  脚本位置：${SELF_DIR:-未知}"
  echo "  当前目录：$(pwd)"
  echo
  echo "  请把本脚本放到「Android-AI-gateway 源码根目录」里再执行，那里应该有 .git 文件夹："
  echo "      Android-AI-gateway/"
  echo "      ├── .git/                  ← 必须有这个"
  echo "      ├── app/"
  echo "      ├── core/"
  echo "      └── sync-upstream.sh       ← 放这里"
  echo
  echo "  或者直接指定路径（推荐，省得挪文件）："
  echo "      sh $(basename "$0") --repo /sdcard/Android-AI-gateway"
  echo
  echo "  注意：文件名开头是「点」的目录（.git）在 MT 管理器里默认隐藏，"
  echo "        需要在 MT 里点右上角菜单 → 显示隐藏文件，但【不要】把 .git 复制丢。"
  exit 1
fi

cd "$REPO_ROOT" || die "进不去 $REPO_ROOT"

printf '\033[1;36m    仓库：%s\033[0m\n' "$REPO_ROOT"

BRANCH=$(git rev-parse --abbrev-ref HEAD 2>/dev/null)
[ -n "$BRANCH" ] || die "读取分支名失败（可能是空仓库）"

# ---------------------------------------------------------------------------
# --status：只看状态
# ---------------------------------------------------------------------------
if [ "$STATUS_ONLY" = "1" ]; then
  printf '\n\033[1;36m==> 当前状态\033[0m\n'
  ok "分支 $BRANCH"
  ok "HEAD $(git rev-parse --short HEAD) $(git log -1 --format=%s | cut -c1-50)"
  if git rev-parse -q --verify MERGE_HEAD >/dev/null 2>&1; then
    warn "正处于合并中（MERGE_HEAD 存在）"
  else
    ok "不在合并状态"
  fi
  U=$(git diff --name-only --diff-filter=U 2>/dev/null)
  if [ -n "$U" ]; then
    warn "未解决冲突："; echo "$U" | sed 's/^/      /'
  else
    ok "无未解决冲突"
  fi
  if [ -f tools/mod-guards.txt ]; then
    BAD=0
    while IFS='|' read -r p need note; do
      case "$p" in ''|'#'*) continue ;; esac
      [ -n "$need" ] || continue
      if [ ! -f "$p" ]; then info "补丁丢失：$p（$note）"; BAD=$((BAD+1))
      elif ! grep -qF -- "$need" "$p" 2>/dev/null; then info "补丁被覆盖：$p [$need]（$note）"; BAD=$((BAD+1)); fi
    done < tools/mod-guards.txt
    if [ "$BAD" = "0" ]; then
      N=$(grep -vcE '^\s*(#|$)' tools/mod-guards.txt 2>/dev/null || echo 0)
      ok "二改补丁 $N 条全部在位"
    fi
  else
    warn "没有 tools/mod-guards.txt，无法核对二改补丁"
  fi
  echo
  exit 0
fi

# ---------------------------------------------------------------------------
step "环境检查"
# ---------------------------------------------------------------------------
ok "仓库 $REPO_ROOT"
ok "分支 $BRANCH（二改分支，建议就叫 ide）"

# .git 写权限：/sdcard 是 scoped storage，脚本能读能跑，但 git 写索引/对象常直接失败，
# 报错还很难懂（"fatal: Unable to create ...index.lock: Permission denied"）。
# 与其合到一半炸，不如一开始就说清楚。
if [ -d .git ] && [ ! -w .git ]; then
  die ".git 目录不可写（位置：$(pwd)/.git）\n\n这通常是仓库放在 /sdcard 上导致的，Android 的存储限制不允许应用直接写 .git。\n三个解法，任选一个：\n  1) 把仓库复制到应用私有目录再跑（最简单，跑完再把 APK 拷出来）\n  2) 在 MT 管理器里给当前目录「授予全部权限」后重试\n  3) 用电脑跑同一份脚本（结果一样）"
fi

if ! git remote get-url "$(primary_remote)" >/dev/null 2>&1; then
  git remote add "$(primary_remote)" "$(primary_url)" 2>/dev/null \
    && ok "已添加远端 $(primary_remote) → $(primary_url)" \
    || die "添加远端失败"
fi
if ! git remote get-url "$(fork_remote)" >/dev/null 2>&1; then
  git remote add "$(fork_remote)" "$(fork_url)" 2>/dev/null \
    && ok "已添加远端 $(fork_remote) → $(fork_url)"
fi

# RESUMING 必须在干净检查之前判定：手工解完冲突必然是「已暂存未提交」的样子
RESUMING=0
if git rev-parse -q --verify MERGE_HEAD >/dev/null 2>&1; then
  RESUMING=1
fi

if [ "$RESUMING" = "1" ]; then
  UNDONE=$(git diff --name-only --diff-filter=U 2>/dev/null)
  [ -n "$UNDONE" ] && die "还有没解完的冲突文件：\n$UNDONE"
  if ! git diff --quiet 2>/dev/null; then
    info "未暂存："; git diff --name-only | sed 's/^/      /'
    die "合并中还有未暂存改动，先 git add 再重跑"
  fi
  warn "延续上次未收尾的合并"
elif [ -n "$(git status --porcelain --untracked-files=no 2>/dev/null)" ]; then
  info "有改动的文件："; git status --porcelain --untracked-files=no | head -10 | sed 's/^/      /'
  die "工作区有未提交改动。先执行下面之一：\n  git add -A && git commit -m \"存档\"\n  git stash"
fi

# ---------------------------------------------------------------------------
step "拉取上游（两个仓库都试）"
# ---------------------------------------------------------------------------
BEFORE=$(git rev-parse HEAD)
FETCHED=""
git fetch -q "$(primary_remote)" "$(primary_branch)" 2>/dev/null \
  && FETCHED="$FETCHED $(primary_remote)/$(primary_branch)"
git fetch -q "$(fork_remote)" "$(fork_branch)" 2>/dev/null \
  && FETCHED="$FETCHED $(fork_remote)/$(fork_branch)"

[ -n "$FETCHED" ] || die "两个上游都拉取失败：检查网络\n（手机连不上 github 时，先用加速器/换网络再试）"

for ref in $FETCHED; do
  ok "$ref = $(git rev-parse --short "$ref") $(git log -1 --format=%s "$ref" | cut -c1-46)"
done

# 挑一个真正能带来新东西的目标：
#   ① 若某个上游是本地 HEAD 的直系后代 → 用它（可以快进，零冲突）
#   ② 否则两个都和本地有分叉 → 合并分叉最少的那个
TARGET=""
BEST=99999
for ref in $FETCHED; do
  if git merge-base --is-ancestor "$ref" HEAD 2>/dev/null; then
    info "$ref 已经包含在本地（无新东西），跳过"
    continue
  fi
  BEHIND=$(git rev-list --count "$ref"..HEAD 2>/dev/null || echo 99999)
  AHEAD=$(git rev-list --count HEAD.."$ref" 2>/dev/null || echo 0)
  info "$ref：新增 $AHEAD 个提交，本地领先 $BEHIND 个"
  if [ "$BEHIND" -lt "$BEST" ]; then BEST=$BEHIND; TARGET=$ref; fi
done

if [ -z "$TARGET" ]; then
  ok "上游没有新提交，已经是最新"
  echo
  exit 0
fi
UPSTREAM_REF="$TARGET"
UPSTREAM_SHA=$(git rev-parse --short "$UPSTREAM_REF")
ok "本次合并目标：$UPSTREAM_REF（$UPSTREAM_SHA）"

# 上游构建文件快照：合并后用来比对「新增了哪些依赖」
SNAPSHOT_DIR=.sync-upstream
mkdir -p "$SNAPSHOT_DIR"
for f in app/build.gradle.kts core/build.gradle.kts cli/build.gradle.kts; do
  snap="$SNAPSHOT_DIR/$(echo "$f" | tr '/' '_')"
  git show "$UPSTREAM_REF:$f" > "$snap" 2>/dev/null || rm -f "$snap"
done

# ---------------------------------------------------------------------------
step "合并"
# ---------------------------------------------------------------------------
if [ "$RESUMING" = "1" ]; then
  ok "延续上次合并，跳过 merge"
else
  git merge --no-commit --no-ff "$UPSTREAM_REF" >/dev/null 2>&1
  RC=$?
  if [ "$RC" -eq 0 ]; then
    ok "三方合并无冲突"
  else
    warn "存在冲突，开始自动处理"
  fi
fi

CONFLICTED() { git diff --name-only --diff-filter=U 2>/dev/null; }

# 3.1 工具链文件：永远保留本地版
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
for f in $TOOLCHAIN_FILES; do
  # 注意：不能写 `git ls-files -u | grep -q .`——pipefail 下 grep 提前退出会让管道返回 141，条件恒假
  if [ -n "$(git ls-files -u -- "$f" 2>/dev/null)" ]; then
    if git checkout --ours -- "$f" 2>/dev/null && git add -- "$f" 2>/dev/null; then
      warn "保留本地版：$f"
    fi
  fi
done

# 3.2 detekt 配置：取上游内容，稍后回退 1.23 语法
if [ -n "$(git ls-files -u -- config/detekt/detekt.yml 2>/dev/null)" ]; then
  git checkout --theirs -- config/detekt/detekt.yml 2>/dev/null \
    && git add -- config/detekt/detekt.yml 2>/dev/null \
    && warn "取上游版（稍后回退 detekt 1.23 语法）：config/detekt/detekt.yml"
fi

# 3.3 AndroidManifest.xml：两边都保留 + 按 name 去重
if [ -n "$(git ls-files -u -- app/src/main/AndroidManifest.xml 2>/dev/null)" ]; then
  if command -v python3 >/dev/null 2>&1 && [ -f tools/merge_manifest.py ]; then
    if python3 tools/merge_manifest.py app/src/main/AndroidManifest.xml; then
      git add -- app/src/main/AndroidManifest.xml 2>/dev/null
      warn "自动解决：app/src/main/AndroidManifest.xml"
    else
      warn "manifest 自动合并没完全成功，需要手工看一下"
    fi
  else
    warn "没有 python3 或 tools/merge_manifest.py，manifest 需要手工合并"
  fi
fi

# 3.4 build.gradle.kts 里除版本号外的冲突标记（极少见）
if [ -n "$(git ls-files -u -- app/build.gradle.kts 2>/dev/null)" ]; then
  git checkout --ours -- app/build.gradle.kts 2>/dev/null \
    && git add -- app/build.gradle.kts 2>/dev/null \
    && warn "保留本地版：app/build.gradle.kts"
fi

REMAIN=$(CONFLICTED)
if [ -n "$REMAIN" ]; then
  printf '\n\033[1;33m还有 %s 个业务文件需要你手工合并\033[0m（脚本不替你决定业务逻辑）：\n' \
    "$(echo "$REMAIN" | wc -l | tr -d ' ')"
  echo "$REMAIN" | sed 's/^/    - /'
  cat <<'TIP'

  怎么处理（手机上也能做）：
    1) 在 MT 管理器里打开上面这些文件
    2) 搜 <<<<<<< 找到冲突段，一共三行标记：
         <<<<<<< HEAD          ← 本地（二改）那版
         =======
         >>>>>>> xxxxx         ← 上游那版
       两边都要的就把两段都留下，然后把三行标记删掉
    3) 全部改完，保存后重新执行本脚本（会自动接着往下跑，不会从头再来）：
         sh sync-upstream.sh --no-build
TIP
  if [ "$DRY_RUN" = "1" ]; then git merge --abort 2>/dev/null; fi
  exit 1
fi
ok "冲突全部解决"

# ---------------------------------------------------------------------------
step "工具链适配"
# ---------------------------------------------------------------------------
ADAPT_LOG=$(mktemp)

# 4.1 detekt 2.0 属性名 → 1.23
if [ -f config/detekt/detekt.yml ]; then
  NEED=0
  grep -qE '^[[:space:]]*allowed(Lines|Complexity|Depth|Conditions):' config/detekt/detekt.yml 2>/dev/null && NEED=1
  grep -qE '^  UnusedPrivate(Function|Property):' config/detekt/detekt.yml 2>/dev/null && NEED=1
  if [ "$NEED" = "1" ]; then
    sed -i -E 's/^([[:space:]]*)allowedLines:/\1threshold:/; s/^([[:space:]]*)allowedComplexity:/\1threshold:/; s/^([[:space:]]*)allowedDepth:/\1threshold:/; s/^([[:space:]]*)allowedConditions:/\1threshold:/' config/detekt/detekt.yml
    if grep -qE '^  UnusedPrivate(Function|Property):' config/detekt/detekt.yml; then
      awk '
        /^  UnusedPrivate(Function|Property):/ { if (!done) { print "  UnusedPrivateMember:"; print "    active: true"; done=1 }; next }
        done && /^    active:[[:space:]]*(true|false)/ { next }
        { print }
      ' config/detekt/detekt.yml > config/detekt/detekt.yml.tmp && mv config/detekt/detekt.yml.tmp config/detekt/detekt.yml
      echo "detekt: UnusedPrivateFunction/Property → UnusedPrivateMember" >> "$ADAPT_LOG"
    fi
    echo "detekt: allowed* → threshold（1.23 语法）" >> "$ADAPT_LOG"
  fi
fi

# 4.2 上游新增依赖按字面量补进来
SKIP_DEPS=" dev.chrisbanes.haze:haze-blur "
sync_deps() {
  up="$1"; loc="$2"
  [ -f "$up" ] && [ -f "$loc" ] || return 0
  grep -E '^[[:space:]]*(implementation|api|compileOnly|runtimeOnly|testImplementation|androidTestImplementation|kapt|ksp)\("' "$up" 2>/dev/null \
  | sed -E 's/^[[:space:]]+//' \
  | while IFS= read -r line; do
      ga=$(printf '%s' "$line" | sed -E 's/.*"([^"]+)".*/\1/' | awk -F: '{print $1":"$2}')
      case "$ga" in *:*:*) continue ;; esac
      [ -n "$ga" ] || continue
      grep -qF "$ga" "$loc" 2>/dev/null && continue
      [ -f gradle/libs.versions.toml ] && grep -qF "module = \"$ga\"" gradle/libs.versions.toml 2>/dev/null && continue
      case "$SKIP_DEPS" in *" $ga "*) echo "跳过 $ga（本地工具链不兼容）" >> "$ADAPT_LOG"; continue ;; esac
      echo "新增依赖 $ga → $loc" >> "$ADAPT_LOG"
      awk -v note="    // [sync-upstream] 上游新增依赖，按字面量补入" -v dep="    $line" '
        { buf[NR]=$0 }
        END { last=0; for (i=NR;i>=1;i--) if (buf[i] ~ /^}/) { last=i; break }
              for (i=1;i<=NR;i++) { if (i==last) { print note; print dep } print buf[i] } }
      ' "$loc" > "$loc.tmp" && mv "$loc.tmp" "$loc"
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
step "隐患扫描"
# ---------------------------------------------------------------------------
FOUND=0

# 5.1 haze 2.x API（IDE 侧只能吃 1.6.10）
HAZE=$(grep -rnE 'hazeBlur\(|HazeBlurStyle|HazeInput|HazeColorEffect' app/src/main/kotlin 2>/dev/null || true)
if [ -n "$HAZE" ]; then
  warn "发现 haze 2.x API（本项目只能用 1.6.10 的写法）"
  echo "$HAZE" | head -8 | sed 's/^/      /'
  FOUND=1
fi

# 5.2 函数自递归（编译过、一进组合就 StackOverflowError）
if command -v python3 >/dev/null 2>&1 && [ -f tools/recursion_scan.py ]; then
  REC=$(python3 tools/recursion_scan.py app/src/main 2>/dev/null)
  if [ -n "$REC" ]; then
    warn "疑似自递归函数："; echo "$REC" | sed 's/^/      /'; FOUND=1
  fi
else
  warn "没有 python3，跳过自递归扫描（pkg install python）"
fi

# 5.3 二改补丁存活核对 —— 上游重构同一段代码时 merge 不会冲突，补丁会静默消失
if [ -f tools/mod-guards.txt ]; then
  GUARD_LOG=$(mktemp)
  GCOUNT=0
  while IFS='|' read -r gpath gneed gnote; do
    case "$gpath" in ''|'#'*) continue ;; esac
    [ -n "$gneed" ] || continue
    GCOUNT=$((GCOUNT+1))
    if [ ! -f "$gpath" ]; then
      echo "补丁丢失：$gpath 不存在（$gnote）" >> "$GUARD_LOG"
    elif ! grep -qF -- "$gneed" "$gpath" 2>/dev/null; then
      echo "补丁可能被覆盖：$gpath 里找不到 [$gneed]（$gnote）" >> "$GUARD_LOG"
    fi
  done < tools/mod-guards.txt
  if [ -s "$GUARD_LOG" ]; then
    warn "二改补丁核对未通过："
    sed 's/^/      /' "$GUARD_LOG"
    info "要么按新代码结构把修补重做一遍，要么确认上游新写法等价后改 tools/mod-guards.txt"
    FOUND=1
  else
    ok "二改补丁 $GCOUNT 条全部在位"
  fi
  rm -f "$GUARD_LOG"
fi

[ "$FOUND" = "0" ] && ok "未发现已知隐患"

# ---------------------------------------------------------------------------
step "提交"
# ---------------------------------------------------------------------------
if [ "$DRY_RUN" = "1" ]; then
  warn "演练结束：不提交、不构建，已回滚"
  git merge --abort 2>/dev/null || true
  exit 0
fi

git add -A 2>/dev/null
if git diff --cached --quiet 2>/dev/null; then
  ok "没有需要提交的改动"
else
  git commit -q -m "merge: 同步上游 $UPSTREAM_SHA

自动处理：工具链文件保留本地版、manifest 两边都保留并按 name 去重、
上游新增依赖按字面量补入、detekt 配置回退 1.23 语法。" 2>/dev/null \
    && ok "已提交 $(git log -1 --format='%h %s' | cut -c1-60)" \
    || warn "提交失败（可能是没配 git user.name/email）：\n      git config user.name 你的名字\n      git config user.email 你的邮箱"
fi

# ---------------------------------------------------------------------------
step "构建验证"
# ---------------------------------------------------------------------------
if [ "$DO_BUILD" = "0" ]; then
  warn "跳过构建（--no-build）"
else
  if [ -x ./gradlew ]; then
    if ./gradlew :core:test :app:assembleDebug --console=plain; then
      ok "单测 + debug 包通过"
      [ -f app/build/outputs/apk/debug/app-debug.apk ] && \
        ok "APK：app/build/outputs/apk/debug/app-debug.apk"
    else
      printf '\n\033[1;31m构建失败\033[0m，两种可能：\n'
      echo "  a) 上游用到你工具链里没有的 API → 按 SYNC-UPSTREAM.md「适配清单」补"
      echo "  b) 合并逻辑错了 → 一键回滚："
      echo "       git reset --hard $BEFORE"
      exit 1
    fi
  else
    warn "没有 gradlew 或不可执行，跳过构建"
  fi
fi

# ---------------------------------------------------------------------------
step "完成"
# ---------------------------------------------------------------------------
echo "  上游版本：$UPSTREAM_REF（$UPSTREAM_SHA）"
echo "  合并前  ：$(echo "$BEFORE" | cut -c1-7)"
echo "  合并后  ：$(git rev-parse --short HEAD)"
echo
echo "  改动统计："
git diff --stat "$BEFORE" HEAD 2>/dev/null | tail -15 | sed 's/^/    /'
echo
echo "  回滚：git reset --hard $(echo "$BEFORE" | cut -c1-7)"
echo

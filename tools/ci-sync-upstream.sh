#!/usr/bin/env bash
# =============================================================
# ci-sync-upstream.sh —— 在 GitHub Actions 里自动同步上游
#
# 和本地 sync-upstream.sh 的区别：
#   本地版给人用（交互、问要不要推进基线、跑完停下来看）
#   这版给 CI 用（无人值守、失败要明确退出码、绝不产生半个合并）
#
# 做的事：
#   1. fetch 上游
#   2. 已经是最新 → 直接退出（exit 0，不浪费构建时间）
#   3. 三方合并
#   4. 冲突处理：工具链文件保留本地版；如果还有其它冲突 → 放弃本次（exit 78）
#   5. 跑补丁存活核对（tools/mod-guards.txt）
#   6. 都没问题 → 提交
#
# 退出码：
#   0  = 已是最新，无需动作
#   10 = 合并成功，产生了新提交
#   78 = 有冲突/补丁丢失，已放弃（需要人工处理）
#   1  = 其它错误
# =============================================================
set -u

REMOTE="${UPSTREAM_REMOTE:-upstream}"
BRANCH="${UPSTREAM_BRANCH:-main}"

OUT="${GITHUB_OUTPUT:-/dev/null}"

# 永远保留本地版的工具链文件。
# 理由：二改锁定在验证过的 Gradle/JDK 组合上（9.0.0 + JDK17），
# 上游升到 9.9.1 能跑，但二改的其它改动是按 9.0.0 调的，跟着升可能悄悄坏掉。
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

log()  { printf '\033[36m[ci-sync]\033[0m %s\n' "$*"; }
warn() { printf '\033[33m[ci-sync]\033[0m %s\n' "$*"; }
die()  { printf '\033[31m[ci-sync] %s\033[0m\n' "$*" >&2; exit "${2:-1}"; }
put()  { printf '%s\n' "$*" >> "$OUT"; }

# ---------- 1. 取上游 ----------
log "fetch $REMOTE/$BRANCH"
git fetch --no-tags "$REMOTE" "$BRANCH" || die "fetch 失败"

UP_SHA=$(git rev-parse "FETCH_HEAD" 2>/dev/null) || die "取不到 FETCH_HEAD"
log "上游 HEAD: ${UP_SHA:0:8}"

# ---------- 2. 已经包含上游？ ----------
if git merge-base --is-ancestor "$UP_SHA" HEAD; then
  log "已经包含上游 ${UP_SHA:0:8}，无需同步"
  put "SYNC_RESULT=up-to-date"
  exit 0
fi

log "上游有新提交，开始合并"

BEFORE=$(git rev-parse HEAD)

# ---------- 3. 合并（不自动提交，先处理冲突） ----------
if ! git merge --no-commit --no-ff "$UP_SHA" >/tmp/merge.log 2>&1; then
  warn "有冲突，开始自动处理："
  sed 's/^/    /' /tmp/merge.log | head -20
fi

CONFLICTED=$(git diff --name-only --diff-filter=U 2>/dev/null || true)

if [ -z "$CONFLICTED" ]; then
  log "无冲突"
else
  # 3.1 工具链文件：保留本地版
  for f in $TOOLCHAIN_FILES; do
    if [ -n "$(git ls-files -u -- "$f" 2>/dev/null)" ]; then
      if git checkout --ours -- "$f" 2>/dev/null && git add -- "$f" 2>/dev/null; then
        warn "保留本地版：$f"
      fi
    fi
  done

  # 3.2 AndroidManifest.xml：两边都保留 + 按 name 去重
  if [ -n "$(git ls-files -u -- app/src/main/AndroidManifest.xml 2>/dev/null)" ]; then
    if [ -f tools/merge_manifest.py ]; then
      if python3 tools/merge_manifest.py app/src/main/AndroidManifest.xml; then
        git add -- app/src/main/AndroidManifest.xml
        warn "自动解决：AndroidManifest.xml"
      fi
    fi
  fi

  # 3.3 还有残局？
  LEFT=$(git diff --name-only --diff-filter=U 2>/dev/null || true)
  if [ -n "$LEFT" ]; then
    warn "以下文件冲突无法自动解决："
    printf '    %s\n' $LEFT
    git merge --abort 2>/dev/null || git reset --hard "$BEFORE"
    put "SYNC_RESULT=conflict"
    put "SYNC_CONFLICTS<<CONFLICT_EOF"
    printf '%s\n' $LEFT >> "$OUT"
    put "CONFLICT_EOF"
    die "放弃本次合并（需要人工处理）" 78
  fi
fi

# ---------- 4. 补丁存活核对 ----------
# 上游重构同一段代码时 git merge 不会冲突，补丁会静默消失而编译照样过。
# 这是最危险的情况，必须显式核对。
if [ -f tools/mod-guards.txt ]; then
  log "核对二改补丁是否还在"
  MISSING=0
  GUARD_MSG=""
  while IFS='|' read -r gpath gmarker gdesc; do
    case "$gpath" in ''|\#*) continue ;; esac
    [ -z "$gmarker" ] && continue
    if [ ! -f "$gpath" ]; then
      warn "补丁丢失（文件不存在）：$gpath  —— $gdesc"
      GUARD_MSG="$GUARD_MSG\n$gpath（文件不存在）"
      MISSING=$((MISSING+1))
    elif ! grep -qF -- "$gmarker" "$gpath" 2>/dev/null; then
      warn "补丁可能被覆盖（找不到特征串）：$gpath  —— $gdesc"
      GUARD_MSG="$GUARD_MSG\n$gpath（特征串消失）"
      MISSING=$((MISSING+1))
    fi
  done < tools/mod-guards.txt

  if [ "$MISSING" -gt 0 ]; then
    warn "有 $MISSING 处补丁丢失，放弃本次合并"
    git merge --abort 2>/dev/null || git reset --hard "$BEFORE"
    put "SYNC_RESULT=guard-lost"
    printf '%b\n' "$GUARD_MSG" > /tmp/guards.txt
    put "SYNC_CONFLICTS<<CONFLICT_EOF"
    printf '%b\n' "$GUARD_MSG" >> "$OUT"
    put "CONFLICT_EOF"
    die "补丁核对未通过" 78
  fi
  log "补丁全部存活"
fi

# ---------- 5. 提交 ----------
git -c user.name="github-actions[bot]" \
    -c user.email="41898282+github-actions[bot]@users.noreply.github.com" \
    commit --no-verify -m "chore(sync): 自动同步上游 ${UP_SHA:0:8}

上游：$REMOTE/$BRANCH @ $UP_SHA
本次由 GitHub Actions 自动合并，冲突按预设规则处理。
" || die "提交失败"

AFTER=$(git rev-parse HEAD)
log "合并完成：${BEFORE:0:8} → ${AFTER:0:8}"

put "SYNC_RESULT=merged"
put "SYNC_SHA=$AFTER"
put "SYNC_UPSTREAM_SHA=$UP_SHA"
put "SYNC_COUNT=$(git rev-list --count "$BEFORE..$AFTER")"

# 上游这次带来的提交（给 Release 说明用）。
# 只要应用相关目录的变化，和上游自己的发版日志口径一致。
RANGE="$BEFORE..$UP_SHA"
UP_LOG=$(git log "$RANGE" --no-merges --invert-grep --grep='^chore(release)' \
          --pretty='- %s' -- app core 2>/dev/null || true)
[ -n "$UP_LOG" ] || UP_LOG="- 无应用相关变化"

put "SYNC_UPSTREAM_LOG<<UPSTREAM_LOG_EOF"
printf '%s\n' "$UP_LOG" >> "$OUT"
put "UPSTREAM_LOG_EOF"

exit 10

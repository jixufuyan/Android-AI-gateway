#!/bin/sh
# =============================================================
# push-to-fork.sh —— 把二改推到你的 fork
#
# 用法：sh push-to-fork.sh
#
# 会做：
#   1. 检查 origin 是否指向你自己的 fork
#   2. 推送 ide 分支
#   3. 推送 main 分支（如果和上游一致，这一步可以跳过）
#
# 需要 GitHub Personal Access Token（当密码用）
# =============================================================
set -u

FORK_USER="${FORK_USER:-jixufuyan}"
UPSTREAM_REPO="xibai6668/Android-AI-gateway"

cd "$(dirname "$0")" || exit 1

echo "=== 当前 remote ==="
git remote -v
echo

# 检查 origin 指向
ORIGIN_URL=$(git remote get-url origin 2>/dev/null || echo "")
case "$ORIGIN_URL" in
  *"$FORK_USER/Android-AI-gateway"*)
    echo "✓ origin 指向你的 fork"
    ;;
  *)
    echo "✗ origin 目前是：$ORIGIN_URL"
    echo "  正在改成 https://github.com/$FORK_USER/Android-AI-gateway.git"
    git remote set-url origin "https://github.com/$FORK_USER/Android-AI-gateway.git" || exit 1
    ;;
esac

# 检查 upstream
git remote get-url upstream >/dev/null 2>&1 || {
  git remote add upstream "https://github.com/$UPSTREAM_REPO.git"
}
echo "✓ upstream = $(git remote get-url upstream)"
echo

echo "=== 待推送分支 ==="
git branch -vv
echo

echo "即将执行："
echo "  git push -u origin ide"
echo "  git push -u origin main"
echo
echo "提示：密码要填 Personal Access Token，不是账号密码。"
echo "     没有的话去 https://github.com/settings/tokens 生成，勾 repo 权限。"
echo
printf "继续？(y/N) "
read -r ANS
case "$ANS" in
  y|Y) ;;
  *) echo "已取消"; exit 0 ;;
esac

echo
echo "=== 推送 ide ==="
git push -u origin ide || { echo "✗ ide 推送失败"; exit 1; }

echo
echo "=== 推送 main ==="
git push -u origin main || echo "⚠ main 推送失败（可能和上游一致被拒绝，不影响使用）"

echo
echo "=== 完成 ==="
echo "去这里看：https://github.com/$FORK_USER/Android-AI-gateway/branches"
echo
echo "下一步："
echo "  1. Settings → Actions → General → Workflow permissions"
echo "     选 Read and write permissions"
echo "     勾 Allow GitHub Actions to create and approve pull requests"
echo "  2. Actions → Auto Sync Upstream & Build → Run workflow"
echo "     dry_run 勾上，先试跑"

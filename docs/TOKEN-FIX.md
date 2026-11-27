# 给 Token 加 workflow 权限（只差这一步）

## 为什么必须要这一步

GitHub 有个硬规定：**任何 token 想创建/修改 `.github/workflows/*.yml`，
必须单独具备 `workflow` 权限**。只有 `repo` 权限不够。

报错长这样：

```
! [remote rejected] ide -> ide (refusing to allow a Personal Access Token
  to create or update workflow `.github/workflows/auto-sync-upstream.yml`
  without `workflow` scope)
```

我试过用 API 直接建文件，返回 `Not Found` —— 这是 GitHub 的故意设计：
权限不够时它连文件存不存在都不告诉你。

**所以这一步只能你在网页上点，任何工具都绕不过去。**

---

## 操作（30 秒）

### 1. 打开 token 设置页

https://github.com/settings/tokens

### 2. 找到你那个 token，点进去

列表里找 Note 对应的那一行，点右边的 **token 名字**（不是 Edit 按钮的省略号）。

地址形如：
```
https://github.com/settings/tokens/<一串数字>
```

### 3. 勾上 workflow

页面上有个权限列表，找到 **`workflow`**（在 `admin:org` 下面几个），打勾。

```
☑ repo
☑ workflow          ← 勾这个
☐ admin:org
☐ admin:public_key
...
```

### 4. 拉到底，点绿色按钮

**Update token**

> 改完 token 的**值不会变**，还是原来那一串。只是权限变了。

---

## 改完之后告诉我

我立刻把工作流推上去。

或者你自己在终端跑也行：

```bash
cd /var/minis/workspace/gw/repo
GIT_ASKPASS=/tmp/askpass.sh GH_PAT=你的token git push origin ide
```

---

## 另一个选择：网页上传（不想改 token 就用这个）

### 1. 打开要放的目录

https://github.com/jixufuyan/Android-AI-gateway/new/ide/.github/workflows

### 2. 文件名填

```
auto-sync-upstream.yml
```

### 3. 内容

把 [fork-kit.zip](minis://workspace/fork-kit.zip) 里
`.github/workflows/auto-sync-upstream.yml` 的全部内容粘进去。

### 4. 拉到底，点

**Commit changes**

---

## 已经推上去的（不用再管）

```
✓ main                   838fe764  （和上游一致）
✓ ide                    34528ed4  （二改 + 全部工具）
✓ tools/ci-sync-upstream.sh
✓ tools/push-to-fork.sh
✓ tools/mod-guards.txt      （19 条锚点）
✓ docs/AUTO-SYNC.md
✓ docs/FORK-SETUP.md
```

**只差** `.github/workflows/auto-sync-upstream.yml` 这一个文件。

---

## 推完之后立刻要做的

### 1. 开 Actions 权限

https://github.com/jixufuyan/Android-AI-gateway/settings/actions

- ☑ **Read and write permissions**
- ☑ **Allow GitHub Actions to create and approve pull requests**

### 2. 试跑

https://github.com/jixufuyan/Android-AI-gateway/actions

选 **Auto Sync Upstream & Build** → **Run workflow**：

- Branch: **`ide`**
- `dry_run`: **勾上**

看到这个就对了：

```
[ci-sync] 上游 HEAD: 838fe764
[ci-sync] 已经包含上游 838fe764，无需同步
```

---

## 安全提醒

你刚才把 token 贴在聊天里了。虽然我只会用它推这一个仓库，但建议：

**用完就去 https://github.com/settings/tokens 把这个 token 删掉，重新生成一个。**

新建一个的时候记得把 `workflow` 一起勾上，省得再来一次。

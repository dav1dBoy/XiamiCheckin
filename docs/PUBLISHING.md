# 上传到自己的 GitHub 仓库

当前工程名为 **XiamiCheckIn**，App 显示名为 **虾米爱打卡**，Android 包名为 **com.xiami.checkin**。

本地工程已于 2026-10-12 验证 SSH 登录账号为 `dav1dBoy`，并配置以下远程：

- `origin`：`git@github.com:dav1dBoy/XiamiCheckIn.git`（自己的仓库，默认推送目标）。
- `upstream`：`https://github.com/yuwang365/FeishuAlarm.git`（原始来源）。

`main` 已配置为连接 `origin/main`。验证时新仓库为空，首次推送后才会建立对应的远程分支。本次仅完成本地连接配置，尚未提交或推送代码。以下创建、连接步骤适用于其他电脑或新克隆的工程，当前电脑可以从第 4 步开始。

SSH 身份验证使用本机密钥；`git config user.name` / `user.email` 决定提交署名，两者是不同设置。可用 `ssh -T git@github.com` 检查身份，成功时显示 `Hi dav1dBoy! You've successfully authenticated`；GitHub 不提供 Shell，因此该测试返回退出码 1 也是正常情况。

## 1. 创建仓库

在自己的 GitHub 账号下创建空仓库 `XiamiCheckIn`，按需要选择公开或私有。创建时不要额外生成 README、.gitignore 或许可证文件，以便直接推送本地历史。

## 2. 检查提交身份

```bash
git config user.name
git config user.email
```

如果需要修改，仅设置当前仓库的提交身份：

```bash
git config user.name "你的署名"
git config user.email "你的 GitHub 提交邮箱"
```

邮箱可使用 GitHub 设置中提供的隐私邮箱。身份设置只影响后续提交，原始作者历史保持不变。

## 3. 连接新仓库

先检查远程地址：

```bash
git remote -v
```

如果 `origin` 仍是 `https://github.com/yuwang365/FeishuAlarm.git`，先保留为来源远程，再添加自己的 SSH 仓库：

```bash
git remote rename origin upstream
git remote add origin git@github.com:dav1dBoy/XiamiCheckIn.git
git config --local remote.pushDefault origin
git config --local branch.main.remote origin
git config --local branch.main.merge refs/heads/main
```

若 `origin` 已经指向你自己的仓库，跳过这一步。不要把更名后的代码推到原仓库。

## 4. 检查并提交

```bash
git status --short
git diff
git diff --cached
```

已配置忽略 `local.properties`、`.idea/`、`.gradle/`、`.kotlin/`、构建产物、签名文件及常见密码配置。已有 `.idea` 文件已移出 Git 跟踪，磁盘文件保留，待本次提交记录这一变化。

确认改动后执行：

```bash
git add .
git diff --cached --stat
git commit -m "Rebrand as XiamiCheckIn"
git push -u origin main
```

推送时会使用已配置的 SSH 密钥进行认证。原始历史与新增改动会一起上传；README 已保留来源说明。SSH 验证与远程地址说明可参考 [GitHub 官方文档](https://docs.github.com/en/authentication/connecting-to-github-with-ssh/testing-your-ssh-connection)。

## 5. 工程目录与安装

Gradle 工程名已经是 `XiamiCheckIn`。本地工作区文件夹即使仍叫 `FeishuAlarm` 也不影响构建。需要统一文件夹名时，可关闭 Android Studio 后在文件管理器中重命名为 `XiamiCheckIn`，再从新目录打开。

新包名的 App 会独立安装，不覆盖旧包名 `com.ai.feishualarm` 的应用或数据。首次使用需重新授权并设置提醒时间，旧助手建议关闭监控。

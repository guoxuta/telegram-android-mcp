# Telegram Android DeepSeek Agent

该交互服务通过 ADB 将模拟器内的 debug MCP 服务连接到本机，并从应用私有目录动态读取 bearer token。MCP token、DeepSeek API Key、验证码和两步验证密码都不会写入仓库或会话文件。

## 当前计算机的通信方式

Windows 版 ADB server 在本机受到异常控制信号影响，无法稳定常驻。启动脚本默认使用 `Auto` 后端，在本机自动选择以下已验证链路：

```text
PowerShell -> WSL Linux adb -> 172.27.x.1:15555 -> 127.0.0.1:5555 -> Android Emulator
```

- Linux Platform Tools 位于 `D:\AndroidSdk\linux-platform-tools`。
- 脚本动态读取当前 WSL 网关；重启后地址变化时会自动补充对应转发。
- 只使用专用 TCP 端口 `15555`，并创建同名的窄范围防火墙规则 `Telegram MCP WSL ADB bridge 15555`。
- 不修改 Clash 配置，也不代理其他流量。
- WSL ADB server 使用端口 `15037`；模拟器内 MCP 通过 ADB forward 暴露到 WSL 回环端口 `19876`。

## 一条命令启动

```powershell
cd F:\project\android-open-source\telegram-android
$A = ".\Tools\MCP\run-telegram-deepseek-agent.ps1"

# 不需要 DeepSeek 的本地连通性检查；APK 不存在时自动安装
& $A doctor

# 构建出新 APK 后，原位覆盖安装且保留应用数据
& $A -InstallApk doctor

# 运行协议、鉴权、schema 和账号状态验收
& $A acceptance

# 安全输入 API Key 并启动多轮交互服务
& $A -PromptForApiKey chat
```

`-InstallApk` 使用 `install --no-streaming -r -t`，不会卸载包或清除数据。默认 APK 为：

```text
D:\TelegramBuild\gradle\_TMessagesProj_App\outputs\apk\afat\debug\app.apk
```

## 测试账号流程

MCP debug 包名是 `org.telegram.messenger.beta`，与正式 Telegram 包隔离。首次安装不会复制其他 Telegram 客户端的登录会话。

1. 先运行 `& $A doctor`，脚本会安装（若缺失）并打开 beta 包。
2. 在模拟器 GUI 中手工登录专用测试账号；验证码和 2FA 只输入在 Telegram GUI，绝不交给 Agent。
3. 登录后运行只读和可回滚写入闭环：

   ```powershell
   & $A acceptance --write-saved-messages
   ```

4. 写入闭环只在 Saved Messages 中创建带 `MCP-E2E-` 前缀的数据，并在回读验证后清理。`dialog.clear_history`、退群、封禁联系人、终止会话等高影响行为不会由自动验收执行。
5. 群组、频道、联系人和设备会话功能需要专门的可丢弃 fixture；缺少 fixture 时标记为已注册/schema 已验证或明确阻塞，不伪造成功。

未登录时，`acceptance` 的预期结果是协议检查通过，并有一项 `blocked_login`。登录后才运行 Saved Messages 写回闭环。

## 交互与工具发现

交互命令包括 `/new`、`/resume`、`/sessions`、`/history`、`/retry`、`/status` 和 `/quit`。每个会话单独存储在 `.cache/telegram-agent/sessions`，秘密字段会被脱敏。

```powershell
& $A catalog
& $A tools "发送消息"
& $A schema telegram.message.send_text
& $A context --dialog-limit 20
```

默认使用 WSL 后端。只有在另一个环境里确认 Windows ADB server 稳定时，才显式传入 `-Backend Windows`；多个设备并存时可传入 `-Serial`。
